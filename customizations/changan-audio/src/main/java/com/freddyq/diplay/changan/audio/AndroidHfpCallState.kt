package com.freddyq.diplay.changan.audio

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.*
import android.os.*
import java.io.Closeable

/** Android 9 HFP's exported, permission-checked read endpoint. No call-control transactions. */
internal class AndroidHfpCallState(private val context: Context, private val report: (String) -> Unit) : Closeable {
    private var remote: IBinder? = null
    private var bound = false
    private var closed = false
    private var changed: () -> Unit = {}
    private var failureReported = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (closed) return
            remote = service
            changed()
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null; changed() }
        override fun onBindingDied(name: ComponentName?) { remote = null; changed() }
    }
    fun start(changed: () -> Unit) {
        this.changed = changed
        if (closed || bound || Build.VERSION.SDK_INT != 28) return
        try {
            bound = context.bindService(Intent(TOKEN).setComponent(ComponentName("com.android.bluetooth",
                "com.android.bluetooth.hfpclient.HeadsetClientService")), connection, Context.BIND_AUTO_CREATE)
        } catch (error: RuntimeException) { reportFailure(error) }
    }
    /** null means unknown; an empty list is the only affirmative no-call result. */
    fun idle(device: BluetoothDevice): Boolean? {
        // An unavailable/unauthorized service can return an empty list. First require the same
        // endpoint to positively identify this device's live HFP connection.
        if (read(device, GET_CONNECTION_STATE) { it.readInt() } != BluetoothProfile.STATE_CONNECTED) return null
        return read(device, GET_CURRENT_CALLS) { reply ->
            when (reply.readInt()) {
                0 -> if (reply.dataAvail() == 0) true else null
                in 1..128 -> false
                else -> null
            }
        }
    }
    private fun <T> read(device: BluetoothDevice, transaction: Int, parse: (Parcel) -> T): T? {
        if (closed || Build.VERSION.SDK_INT != 28) return null
        val binder = remote ?: return null
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            if (!binder.isBinderAlive || binder.interfaceDescriptor != TOKEN) return null
            data.writeInterfaceToken(TOKEN)
            data.writeInt(1); device.writeToParcel(data, 0)
            // Android 9 IBluetoothHeadsetClient ABI, verified against the API28 framework stub.
            if (!binder.transact(transaction, data, reply, 0)) return null
            reply.readException()
            parse(reply)
        } catch (error: Exception) { reportFailure(error); null }
        finally { reply.recycle(); data.recycle() }
    }
    private fun reportFailure(error: Exception) {
        if (!failureReported) report("HFP read-only call query unavailable=${error.javaClass.simpleName}; full handoff deferred")
        failureReported = true
    }
    override fun close() {
        closed = true; remote = null
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
    }
    companion object {
        const val TOKEN = "android.bluetooth.IBluetoothHeadsetClient"
        const val GET_CONNECTION_STATE = 5
        const val GET_CURRENT_CALLS = 10
    }
}
