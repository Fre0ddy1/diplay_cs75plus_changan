package com.freddyq.diplay.changan.audio

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel

/** Android 9's exported A2DP Sink service, with its normal caller permission checks intact. */
internal class AndroidA2dpMediaPolicyPort(
    private val context: Context,
    private val report: (String) -> Unit,
) : MediaAudioPolicyPort {
    private var remote: IBinder? = null
    private var bound = false
    private var closed = false
    private var ready: () -> Unit = {}
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (closed) return
            remote = service
            ready()
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null }
        override fun onBindingDied(name: ComponentName?) { remote = null }
        override fun onNullBinding(name: ComponentName?) { remote = null; ready() }
    }
    override fun start(ready: () -> Unit): Boolean {
        this.ready = ready
        if (closed || Build.VERSION.SDK_INT != 28 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) return false
        if (context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true) return false
        bound = runCatching { context.bindService(Intent(TOKEN).setComponent(ComponentName(
            "com.android.bluetooth", "com.android.bluetooth.a2dpsink.A2dpSinkService")),
            connection, Context.BIND_AUTO_CREATE) }.onFailure {
            report("media audio policy bind failed=${it.javaClass.simpleName}")
        }.getOrDefault(false)
        if (closed && bound) { runCatching { context.unbindService(connection) }; bound = false }
        return bound
    }
    override fun priority(address: String): Int? = call(address, GET_PRIORITY)
    override fun setPriority(address: String, value: Int): Boolean {
        if (value !in setOf(0, 100, 1000)) return false
        return call(address, SET_PRIORITY, value) == 1
    }
    override fun disconnectMedia(address: String) = call(address, DISCONNECT) == 1
    private fun call(address: String, transaction: Int, priority: Int? = null): Int? {
        if (closed || Build.VERSION.SDK_INT != 28 || !BluetoothAdapter.checkBluetoothAddress(address)) return null
        val binder = remote ?: return null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return null
        if (runCatching { device.bondState != BluetoothDevice.BOND_BONDED }.getOrDefault(true)) return null
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            if (!binder.isBinderAlive || binder.interfaceDescriptor != TOKEN) return null
            data.writeInterfaceToken(TOKEN)
            data.writeInt(1); device.writeToParcel(data, 0)
            priority?.let(data::writeInt)
            if (!binder.transact(transaction, data, reply, 0)) return null
            reply.readException()
            reply.readInt()
        } catch (error: Exception) {
            report("media audio policy transaction=$transaction unavailable=${error.javaClass.simpleName}")
            null
        } finally { reply.recycle(); data.recycle() }
    }
    override fun close() {
        closed = true; remote = null
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
    }
    companion object {
        const val TOKEN = "android.bluetooth.IBluetoothA2dpSink"
        // API28 Stub ABI and the shipped service were both checked offline. Not OEM transaction IDs.
        const val DISCONNECT = 2
        const val SET_PRIORITY = 7
        const val GET_PRIORITY = 8
    }
}
