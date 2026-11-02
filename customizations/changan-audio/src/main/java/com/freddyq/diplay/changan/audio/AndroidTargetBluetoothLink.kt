package com.freddyq.diplay.changan.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build

/** Uses per-device framework profiles, never the native SDK's address-ignoring disconnectAll. */
@SuppressLint("MissingPermission") // All profile access is gated by the actual runtime grant below.
internal class AndroidTargetBluetoothLink(
    context: Context,
    address: String,
    private val report: (String) -> Unit,
) : TargetBluetoothLink {
    private val app = context.applicationContext
    private val permitted = if (Build.VERSION.SDK_INT >= 31) {
        app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        app.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
    }
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val target = address.takeIf { permitted && BluetoothAdapter.checkBluetoothAddress(it) }
        ?.let { adapter?.getRemoteDevice(it) }
    private val proxies = mutableMapOf<Int, BluetoothProfile>()
    private val unavailable = mutableSetOf<Int>()
    private var closed = false
    private var registered = false
    private var changed: () -> Unit = {}
    private var callStateFailureReported = false
    private var reflectiveCallsUnavailable = false
    private var peerStateFailureReported = false
    private var peerStateMethod: java.lang.reflect.Method? = null
    private val calls = AndroidHfpCallState(app, report)
    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (closed) { adapter?.closeProfileProxy(profile, proxy); return }
            unavailable.remove(profile)
            proxies.put(profile, proxy)?.takeIf { it !== proxy }?.let { adapter?.closeProfileProxy(profile, it) }
            changed()
        }
        override fun onServiceDisconnected(profile: Int) {
            // Retain the handle so close() can unbind it even if the service never comes back.
            unavailable += profile
            changed()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val callRefresh = intent.action == "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED" && device == null
            // Some Android 9 call broadcasts carry only a private call Parcelable. They may
            // trigger a fresh query of our selected peer, never supply another peer's state.
            if (!closed && (device?.address == target?.address || callRefresh)) changed()
        }
    }
    override fun start(changed: () -> Unit) {
        this.changed = changed
        if (target == null || adapter == null) {
            report("target profile access unavailable; permission=$permitted")
            return
        }
        val filter = IntentFilter().apply {
            ACTIONS.forEach(::addAction)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else { @Suppress("DEPRECATION") app.registerReceiver(receiver, filter) }
            registered = true
        } catch (error: RuntimeException) {
            report("profile monitor unavailable=${error.javaClass.simpleName}")
        }
        PROFILES.forEach { profile ->
            try {
                if (!adapter.getProfileProxy(app, listener, profile)) unavailable += profile
            } catch (error: RuntimeException) {
                unavailable += profile
                report("profile=$profile binding unavailable=${error.javaClass.simpleName}")
            }
        }
        calls.start { if (!closed) changed() }
    }
    override fun states(): Map<Int, LinkState> = PROFILES.associateWith(::state)
    private fun state(profile: Int): LinkState {
        val proxy = proxies[profile]
        if (target == null) return LinkState.UNKNOWN
        return try {
            // A globally disconnected role proves the target is disconnected even when an
            // optional PAN/PBAP proxy never binds. A connection of another peer proves nothing.
            if (proxy == null || profile in unavailable) {
                return if (adapter?.getProfileConnectionState(profile) == BluetoothProfile.STATE_DISCONNECTED)
                    LinkState.DISCONNECTED else LinkState.UNKNOWN
            }
            when (proxy.getConnectionState(target)) {
                BluetoothProfile.STATE_DISCONNECTED -> LinkState.DISCONNECTED
                BluetoothProfile.STATE_CONNECTING -> LinkState.CONNECTING
                BluetoothProfile.STATE_CONNECTED -> LinkState.CONNECTED
                BluetoothProfile.STATE_DISCONNECTING -> LinkState.DISCONNECTING
                else -> LinkState.UNKNOWN
            }
        } catch (error: RuntimeException) {
            unavailable += profile
            report("profile=$profile state unavailable=${error.javaClass.simpleName}")
            LinkState.UNKNOWN
        }
    }
    override fun canDisconnect(): Boolean {
        if (!permitted || app.getSystemService(AudioManager::class.java)?.mode != AudioManager.MODE_NORMAL) return false
        val device = target ?: return false
        if (state(16) == LinkState.DISCONNECTED) return true
        val hfp = proxies[16]
        if (hfp == null || reflectiveCallsUnavailable) return calls.idle(device) == true
        return try {
            if (hfp.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED) true
            else {
                val calls = hfp.javaClass.getMethod("getCurrentCalls", BluetoothDevice::class.java)
                    .invoke(hfp, device) as? List<*>
                calls != null && calls.isEmpty()
            }
        } catch (error: NoSuchMethodException) {
            if (!callStateFailureReported) report("HFP Java call query not exposed; use permission-checked read-only service")
            callStateFailureReported = true
            reflectiveCallsUnavailable = true
            calls.idle(device) == true
        } catch (error: Exception) {
            if (!callStateFailureReported) report("target call state unavailable=${(error.cause ?: error).javaClass.simpleName}; handoff deferred")
            callStateFailureReported = true
            false
        }
    }
    override fun supportsDisconnect(profile: Int): Boolean = profile in ACTIVE_PROFILES
    override fun peerConnected(): Boolean? {
        if (closed || !permitted || peerStateFailureReported) return null
        val device = target ?: return null
        return try {
            val method = peerStateMethod ?: device.javaClass.getMethod("isConnected").also { peerStateMethod = it }
            method.invoke(device) as? Boolean
        } catch (error: Exception) {
            if (!peerStateFailureReported) report("target ACL state unavailable=${(error.cause ?: error).javaClass.simpleName}; full handoff unverified")
            peerStateFailureReported = true
            null
        }
    }

    override fun disconnect(profile: Int): Boolean {
        if (closed || !canDisconnect() || profile !in ACTIVE_PROFILES) return false
        val device = target ?: return false
        val proxy = proxies[profile] ?: return false
        return try {
            // Hidden on Android 9: permission/hidden-API denial is a reported failure, not bypassed.
            proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java).invoke(proxy, device) != false
        } catch (error: Exception) {
            unavailable += profile
            report("profile=$profile disconnect unavailable=${(error.cause ?: error).javaClass.simpleName}")
            false
        }
    }
    override fun close() {
        closed = true
        calls.close()
        if (registered) runCatching { app.unregisterReceiver(receiver) }
        registered = false
        proxies.forEach { (profile, proxy) -> runCatching { adapter?.closeProfileProxy(profile, proxy) } }
        proxies.clear()
    }
    companion object {
        // Only the selected peer, after authenticated wireless handoff and a verified no-call state.
        private val ACTIVE_PROFILES = setOf(11, 16, 17, 5)
        private val PROFILES = ACTIVE_PROFILES + 12 // AVRCP is observed; no independent disconnect API.
        private val ACTIONS = listOf(
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED,
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED",
            "android.bluetooth.pbapclient.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.pan.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED",
        )
    }
}
