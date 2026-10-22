package com.shilapi.xcertplay.network

import android.net.TrafficStats
import android.os.Process
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Observes startup without changing connection deadlines, address selection or retry behavior. */
internal class WirelessStartupDiagnostics(
    private val sample: () -> String,
    private val log: (String) -> Unit,
    private val intervalMillis: Long = 10_000,
    private val nowNs: () -> Long = System::nanoTime,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val startedNanos = nowNs()
    private var authenticated = false
    private var wifiConfigs = 0
    private var startRequests = 0
    private var tcpAccepted = 0
    private var sessionActive = false
    private var firstStartRequestNs: Long? = null
    private var firstTcpAfterStartMs: Long? = null
    @Volatile private var lastSnapshot = ""
    private var lastStallNs = 0L
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var stallReported = false
    private val worker = Thread(::observe, "diplay-wireless-diagnostics").apply { isDaemon = true }

    private companion object {
        const val STALL_INTERVAL_NS = 30_000_000_000L
        /** Long enough for a slow phone to join, short enough to retry inside one attempt. */
        const val STALL_REPORT_MILLIS = 20_000L
    }

    init { require(intervalMillis > 0) }

    @Synchronized fun start() {
        if (!closed.get() && worker.state == Thread.State.NEW) worker.start()
    }

    @Synchronized fun controlProgress(message: String) {
        when (message) {
            "iap2 authentication accepted" -> authenticated = true
            "iap2 tx=0x5703 accessory-wifi-configuration",
            "iap2 tx=0x5703 post-transport accessory-wifi-configuration" -> wifiConfigs++
            "iap2 tx=0x4301 carplay-start-session" -> {
                startRequests++
                if (firstStartRequestNs == null) firstStartRequestNs = nowNs()
            }
        }
    }

    @Synchronized fun connectionAccepted() {
        tcpAccepted++
        if (firstTcpAfterStartMs == null) {
            firstStartRequestNs?.let { firstTcpAfterStartMs = elapsedMillis(it) }
        }
    }
    @Synchronized fun sessionActive() { sessionActive = true }

    /**
     * Milliseconds since CarPlay was asked to start while no AirPlay connection has arrived, or
     * null when the link is up or was never requested.
     *
     * Read by the controller's stall watchdog. Everything else here only observes; this is the one
     * value another component acts on, because "the phone was told to connect and never did" is
     * invisible to the rest of the stack - the hotspot, iAP2 and the Wi-Fi handover all look
     * healthy while the driver waits (field 2026-10-22).
     */
    @Synchronized fun stallAgeMs(): Long? {
        if (closed.get() || tcpAccepted > 0 || sessionActive) return null
        val started = firstStartRequestNs ?: return null
        return elapsedMillis(started)
    }

    @Synchronized fun summary(): String {
        val waitingFor = when {
            sessionActive -> "none"
            tcpAccepted > 0 -> "AirPlay_protocol"
            startRequests > 0 -> "WiFi_discovery_or_AirPlay_TCP"
            authenticated -> "WiFi_configuration_or_start_request"
            else -> "Bluetooth_iAP2_authentication"
        }
        return "wireless startup elapsedMs=${elapsedMillis(startedNanos)} " +
            "authenticated=$authenticated wifiConfigs=$wifiConfigs startRequests=$startRequests " +
            "tcpAccepted=$tcpAccepted sessionActive=$sessionActive waitingFor=$waitingFor " +
            "startRequestAgeMs=${firstStartRequestNs?.let(::elapsedMillis) ?: "none"} " +
            "firstTcpAfterStartMs=${firstTcpAfterStartMs ?: "none"}"
    }

    private fun elapsedMillis(since: Long): Long = (nowNs() - since).coerceAtLeast(0) / 1_000_000

    private fun observe() {
        try {
            while (!closed.get()) {
                val snapshot = try { sample() } catch (error: Exception) {
                    "sampling=unavailable failureClass=${error.javaClass.simpleName}"
                }
                if (closed.get()) return
                lastSnapshot = snapshot
                emit(summary())
                emitSnapshot(snapshot)
                maybeEmitStall()
                Thread.sleep(intervalMillis)
            }
        } catch (_: InterruptedException) {
            // Teardown interrupts sleep or an in-flight Android callback wait.
        }
    }

    /**
     * One line every half minute while the phone has been asked to start CarPlay but no AirPlay
     * connection has arrived. The neighbour table is the only L3 evidence available to an app on
     * modern Android: an ARP entry for the phone on the hotspot interface means it got an address
     * and is talking to us (so iOS simply never tried), while an empty table means it associated
     * but never completed DHCP - a host-side routing problem.
     */
    @Synchronized
    private fun maybeEmitStall() {
        if (closed.get() || tcpAccepted > 0 || startRequests == 0) return
        if (!stallReported && (firstStartRequestNs?.let(::elapsedMillis) ?: 0) >= STALL_REPORT_MILLIS) {
            stallReported = true
        }
        val now = nowNs()
        if (now - lastStallNs < STALL_INTERVAL_NS) return
        lastStallNs = now
        emit("wireless stall neighbours=${neighbourTable()} uidTraffic=${uidTrafficDelta()} " +
            "elapsedSinceStartRequestMs=${firstStartRequestNs?.let(::elapsedMillis) ?: "none"}")
    }

    /**
     * Bytes this app received/sent since the previous stall line.
     *
     * Kernel counters are unreadable on Android 14, but TrafficStats is public API. A non-zero
     * receive delta means packets really do reach us from the LAN (so the phone simply never opens
     * AirPlay), while a flat zero while the phone claims to be connecting means nothing is arriving
     * at all - a router that isolates clients, or a phone that never joined.
     */
    private fun uidTrafficDelta(): String {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx < 0 || tx < 0) return "unsupported"
        val rxDelta = (rx - lastRxBytes).coerceAtLeast(0)
        val txDelta = (tx - lastTxBytes).coerceAtLeast(0)
        lastRxBytes = rx
        lastTxBytes = tx
        return "rx+$rxDelta tx+$txDelta"
    }

    /** Interface -> count of neighbour entries, e.g. "p2p0:1,wlan0:0". Never prints an address. */
    private fun neighbourTable(): String = try {
        File("/proc/net/arp").readText().lineSequence().drop(1).mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (fields.size < 6) null else fields[5]
        }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            .entries.joinToString(",") { "${it.key}:${it.value}" }
            .ifEmpty { "none" }
    } catch (error: Exception) {
        "unreadable=${error.javaClass.simpleName}"
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.interrupt()
        emit("${summary()} observation=ended")
        emitSnapshot(lastSnapshot, cached = true)
        if (tcpAccepted == 0 && startRequests > 0) {
            emit("wireless stall neighbours=${neighbourTable()} (final)")
        }
    }

    private fun emitSnapshot(snapshot: String, cached: Boolean = false) {
        // The report redactor caps each log line at 700 characters. Never append the kernel
        // counters to the already detailed startup/interface/P2P/Bonjour state line.
        snapshot.lineSequence().filter { it.isNotBlank() }.take(4).forEach { line ->
            emit("wireless snapshot${if (cached) " cached=true" else ""} $line")
        }
    }

    private fun emit(message: String) {
        try { log(message) } catch (_: RuntimeException) {
            // An observer cannot fail startup or teardown.
        }
    }
}

/** Counts are useful in exported reports; literals, hardware identifiers and names are not. */
internal object WirelessInterfaceDiagnostics {
    fun snapshot(interfaceName: String?): String {
        if (interfaceName == null) return "interfaceState=unknown"
        return try {
            val network = NetworkInterface.getByName(interfaceName)
                ?: return "interfaceState=missing"
            "interfaceState=${if (network.isUp) "up" else "down"} multicast=${network.supportsMulticast()} " +
                addressSummary(Collections.list(network.inetAddresses))
        } catch (error: Exception) {
            "interfaceState=unavailable failureClass=${error.javaClass.simpleName}"
        }
    }

    fun addressSummary(addresses: List<InetAddress>): String =
        "ipv4Usable=${addresses.count { it is Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }} " +
            "ipv6LinkLocal=${addresses.count { it is Inet6Address && it.isLinkLocalAddress }} " +
            "ipv6Scoped=${addresses.count { it is Inet6Address && it.isLinkLocalAddress && it.scopeId > 0 }}"
}
