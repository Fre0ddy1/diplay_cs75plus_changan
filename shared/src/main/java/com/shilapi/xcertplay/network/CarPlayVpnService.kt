package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the AirPlay TCP listener for both NCM/VPN and local-only Wi-Fi transports.
 *
 * The wired path also owns the Android VPN tunnel and NCM IPv6 bridge. VPN consent is requested
 * with [prepare] before binding.
 */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }

    private data class AirPlayAttachment(
        val address: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val additionalAddresses: List<InetAddress> = emptyList(),
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private var serverSocket: ServerSocket? = null
    private var additionalServers: List<ServerSocket> = emptyList()
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale NCM/VPN attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val tunFd = Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
                .setBlocking(true)
                // An empty app list routes every UID through this VPN. Scope it before establish;
                // rejection must reach the existing attachment cleanup, never an unscoped retry.
                .addAllowedApplication(packageName)
                .establish()
                ?: throw IOException("VpnService.establish returned null")
            tun = tunFd

            val ipv6Bridge = Ipv6NcmBridge(ncm, tunFd, hostMac) { error ->
                onTransportError(generation, listener, error)
            }
            ipv6Bridge.start()
            bridge = ipv6Bridge

            startAirPlayServer(
                generation,
                AirPlayAttachment(address, config, identity, pairings, mfi, listener, media),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Starts the AirPlay listener on the local-only Wi-Fi AP address without establishing a VPN or
     * NCM bridge.
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        additionalBindAddresses: List<InetAddress> = emptyList(),
    ): AttachResult {
        if (active.get()) {
            Log.i(TAG, "replacing stale local-only Wi-Fi attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        return try {
            startAirPlayServer(
                generation,
                AirPlayAttachment(bindAddress, config, identity, pairings, mfi, listener, media, additionalBindAddresses),
            )
            AttachResult.Started
        } catch (error: Exception) {
            releaseLocked()
            AttachResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Releases the active AirPlay listener and whichever VPN/NCM transport resources are active. */
    @Synchronized
    fun detach() {
        releaseLocked()
    }

    fun isAttached(): Boolean = active.get() && attachment != null

    /** Port the AirPlay listener actually bound, which may differ from the configured port. */
    fun boundPort(): Int? = attachment?.config?.port

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
    ) {
        val servers = if (replacement.additionalAddresses.isEmpty()) {
            listOf(AirPlayPortSelector.bind(replacement.address, replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            })
        } else {
            AirPlayPortSelector.bindAll(listOf(replacement.address) + replacement.additionalAddresses,
                replacement.config.port) { busy, bound ->
                Log.w(TAG, "AirPlay port $busy is in use; listening on $bound instead")
            }
        }
        val server = servers.first()
        attachment = replacement.copy(config = replacement.config.copy(port = server.localPort))
        serverSocket = server
        additionalServers = servers.drop(1)
        servers.forEach { bound ->
            runCatching { replacement.listener.onDebugLog(
                "airplay listener ready family=${if (bound.inetAddress is Inet6Address) "IPv6" else "IPv4"} port=${bound.localPort}",
            ) }
            Thread(
                { acceptLoop(generation, bound) },
                "airplay-accept",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                val session = synchronized(this) {
                    if (!active.get()) {
                        socket.close()
                        return
                    }
                    val current = attachment
                    if (current == null) {
                        socket.close()
                        return
                    }
                    runCatching { current.listener.onDebugLog(
                        "airplay TCP accepted family=${if (socket.inetAddress is Inet6Address) "IPv6" else "IPv4"}",
                    ) }
                    AirPlaySession(
                        socket = socket,
                        config = current.config,
                        identity = current.identity,
                        pairings = current.pairings,
                        mfi = current.mfi,
                        listener = object : AirPlaySessionListener by current.listener {
                            override fun onRemoteControlMessage(
                                session: AirPlaySession,
                                streamId: Long,
                                message: Map<String, Any?>,
                            ) {
                                current.listener.onRemoteControlMessage(session, streamId, message)
                            }

                            override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
                                current.listener.onVideoPlaybackUiRequested(session)
                            }

                            override fun onSessionEnded(session: AirPlaySession) {
                                removeSession(session)
                                current.listener.onSessionEnded(session)
                            }
                        },
                        media = current.media,
                    ).also(::addSession)
                }
                session.start()
            }
        } catch (error: IOException) {
            if (active.get()) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun addSession(session: AirPlaySession) {
        synchronized(sessionsLock) { sessions.add(session) }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        synchronized(sessionsLock) {
            sessions.toList().forEach { session ->
                try {
                    session.close()
                } catch (error: Exception) {
                    Log.w(TAG, "AirPlay session replacement failed", error)
                }
            }
            sessions.clear()
        }
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "CarPlay transport stopped: $message", error)
        Thread(
            {
                synchronized(this) {
                    if (generation != attachGeneration) return@Thread
                    releaseLocked()
                }
                listener.onTransportError(message)
                stopSelf()
            },
            "airplay-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        attachGeneration += 1
        active.set(false)
        attachment = null
        serverSocket?.close()
        serverSocket = null
        additionalServers.forEach { it.close() }
        additionalServers = emptyList()
        closeSessionsLocked()
        bridge?.close()
        bridge = null
        tun?.close()
        tun = null
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "xcertplay CarPlay"
        private const val TUN_MTU = 1500

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)

        /** AppOps operation that gates whether an app may bring up a tunnel at all. */
        private const val OP_ACTIVATE_VPN = "android:activate_vpn"

        /**
         * Whether the platform allows this app to activate a VPN.
         *
         * `establish()` returns null either because the app is not a *prepared* VPN (the consent
         * dialog this ROM deleted) or because the ACTIVATE_VPN AppOps operation is not allowed.
         * Both must be satisfied, and only the first is visible through [prepare]. This is the
         * second half, and it is the one a slimmed ROM can fix without any dialog:
         *
         *     adb shell appops set <package> ACTIVATE_VPN allow
         *
         * (the documented workaround for the same missing-dialog situation on Windows Subsystem
         * for Android). The mode persists across reboots; MODE_DEFAULT means "not allowed here".
         */
        fun activateVpnAllowed(context: Context): Boolean? = runCatching {
            val appOps = context.getSystemService(android.app.AppOpsManager::class.java) ?: return null
            val mode = appOps.checkOpNoThrow(OP_ACTIVATE_VPN, android.os.Process.myUid(), context.packageName)
            mode == android.app.AppOpsManager.MODE_ALLOWED
        }.getOrNull()

        /**
         * One-line state of the VPN bring-up for the diagnostic report.
         *
         * `establish()` returning null has two causes with opposite fixes, and the wired CarPlay
         * path cannot tell them apart on its own:
         *
         *  - `prepare()` keeps returning an intent -> the app is not an approved VPN, so the
         *    platform refuses the tunnel. Fix: grant consent (the dialog this ROM deleted, or
         *    restore com.android.vpndialogs, or DeviceOwner).
         *  - ACTIVATE_VPN AppOps not allowed -> the tunnel is refused even when the consent path
         *    was never the blocker. Fix: `appops set <package> ACTIVATE_VPN allow` as root/shell.
         *
         * The report states both, because the field log so far only proves the dialog is missing.
         */
        fun diagnostic(context: Context): String {
            val prepared = try {
                prepare(context) == null
            } catch (error: Throwable) {
                return "vpn prepare threw ${error.javaClass.simpleName}"
            }
            val appOps = when (activateVpnAllowed(context)) {
                true -> "activateVpn=allow"
                false -> "activateVpn=denied"
                null -> "activateVpn=unknown"
            }
            val pkg = runCatching {
                context.packageManager.getPackageInfo("com.android.vpndialogs", 0)
                "installed"
            }.getOrElse { "absent" }
            val deviceOwner = runCatching {
                val manager = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                if (manager?.isDeviceOwnerApp(context.packageName) == true) "deviceOwner=yes" else "deviceOwner=no"
            }.getOrElse { "deviceOwner=unknown" }
            return "vpn approved=$prepared $appOps consentDialogPkg=$pkg $deviceOwner"
        }

        /**
         * Best-effort root grant of [OP_ACTIVATE_VPN]: on a rooted head unit this fixes the wired
         * path with no dialog and no PC.
         *
         * `su` is not one program. The Chang'an Feiyu ROM ships a minimal su that rejects `-c`
         * outright ("su: invalid uid/gid '-c'") and asks for a password on its own stdin, so the
         * standard form cannot work there and the command has to be fed through stdin instead.
         * Every style is tried and the one that worked is reported, rather than assuming.
         */
        fun grantActivateVpnWithRoot(context: Context, timeoutMillis: Long = 12_000L): String {
            val pkg = context.packageName
            val script = "appops set $pkg ACTIVATE_VPN allow; appops get $pkg ACTIVATE_VPN; exit"
            val attempts = listOf(
                SuAttempt("su -c", listOf("su", "-c", script), stdin = null),
                SuAttempt("su 0 0", listOf("su", "0", "0"), stdin = script),
                SuAttempt("su", listOf("su"), stdin = script),
                SuAttempt("sh su -c", listOf("sh", "-c", "su -c '$script'"), stdin = null),
            )
            val problems = mutableListOf<String>()
            for (attempt in attempts) {
                val output = runSu(attempt, timeoutMillis)
                if (activateVpnAllowed(context) == true) {
                    return "已授权 ACTIVATE_VPN（方式 ${attempt.label}）"
                }
                problems += "${attempt.label}: ${output.take(80).ifEmpty { "无输出" }}"
            }
            // No su that works. The head unit's own adbd is a better route: the shell user already
            // holds MANAGE_APP_OPS_MODES, so the operation can be set with no root and no password
            // at all - only the network-ADB port has to be open (127.0.0.1:5555).
            val adb = grantActivateVpnWithLocalAdb(context)
            if (activateVpnAllowed(context) == true) return adb
            problems += adb
            return "未生效；${problems.joinToString(" ")}"
        }

        private fun grantActivateVpnWithLocalAdb(context: Context): String {
            val pkg = context.packageName
            return runCatching {
                com.shilapi.xcertplay.adb.LocalAdb(com.shilapi.xcertplay.adb.AdbKeys.load(context)).use { adb ->
                    // Never offer a key here: an unapproved key must stay unapproved.
                    if (adb.connect(mayAsk = false) != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) {
                        return@use "adb: 车机网络 ADB(127.0.0.1:5555) 未开启或未授权"
                    }
                    val output = adb.shell("appops set $pkg ACTIVATE_VPN allow", 8_000)
                    "adb: ${output?.trim().orEmpty().ifEmpty { "已执行" }}"
                }
            }.getOrElse { "adb: ${it.javaClass.simpleName}" }
        }

        private data class SuAttempt(val label: String, val command: List<String>, val stdin: String?)

        private fun runSu(attempt: SuAttempt, timeoutMillis: Long): String {
            val process = runCatching {
                ProcessBuilder(attempt.command).redirectErrorStream(true).start()
            }.getOrElse { return "启动失败 ${it.javaClass.simpleName}" }
            val output = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { input ->
                        var line = input.readLine()
                        while (line != null && output.length < 2_048) {
                            output.append(line).append('\n')
                            line = input.readLine()
                        }
                    }
                }
            }
            reader.isDaemon = true
            reader.start()
            if (attempt.stdin != null) {
                runCatching {
                    process.outputStream.bufferedWriter().use { writer ->
                        writer.write(attempt.stdin)
                        writer.flush()
                    }
                }
            }
            val finished = runCatching {
                process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!finished) {
                runCatching { process.destroy() }
                reader.join(500L)
                return "无响应（超时）${output.toString().trim().take(60)}"
            }
            reader.join(500L)
            return output.toString().trim()
        }
    }
}