package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BluetoothRetryWakeupTest {
    @Test fun onlyBluetoothOnWakesAnExistingBluetoothRetry() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val receiver = ReflectionHelpers.getField<BroadcastReceiver>(host, "bluetoothOnReceiver")
        var runs = 0
        ReflectionHelpers.setField(host, "pendingReconnect", Runnable { runs++ })
        ReflectionHelpers.setField(host, "reconnectScheduled", true)
        fun notify(state: Int) = receiver.onReceive(host,
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, state))
        notify(BluetoothAdapter.STATE_ON)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, runs)
        ReflectionHelpers.setField(host, "reconnectWaitsForBluetooth", true)
        notify(BluetoothAdapter.STATE_TURNING_ON)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, runs)
        notify(BluetoothAdapter.STATE_ON)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, runs)
        val cancel = host.javaClass.getDeclaredMethod("cancelPendingReconnect").apply { isAccessible = true }
        cancel.invoke(host)
        notify(BluetoothAdapter.STATE_ON)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, runs)
        assertNull(ReflectionHelpers.getField<Runnable?>(host, "pendingReconnect"))
    }
}
