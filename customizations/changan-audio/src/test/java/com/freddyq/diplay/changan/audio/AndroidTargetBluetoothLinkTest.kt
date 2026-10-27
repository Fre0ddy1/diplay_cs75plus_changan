package com.freddyq.diplay.changan.audio

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidTargetBluetoothLinkTest {
    class Profile : BluetoothProfile {
        var state = BluetoothProfile.STATE_CONNECTED
        var calls = emptyList<Any>()
        val disconnected = mutableListOf<BluetoothDevice>()
        override fun getConnectedDevices() = emptyList<BluetoothDevice>()
        override fun getDevicesMatchingConnectionStates(states: IntArray) = emptyList<BluetoothDevice>()
        override fun getConnectionState(device: BluetoothDevice) = state
        fun getCurrentCalls(device: BluetoothDevice): List<Any> = calls
        fun disconnect(device: BluetoothDevice): Boolean { disconnected += device; return true }
    }
    private val app get() = RuntimeEnvironment.getApplication()
    private val selected = "02:11:22:33:44:55"
    private lateinit var link: AndroidTargetBluetoothLink
    private val media = Profile()
    private val hfp = Profile()
    @Before fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        link = AndroidTargetBluetoothLink(app, selected, {})
        ReflectionHelpers.getField<MutableMap<Int, BluetoothProfile>>(link, "proxies").apply {
            put(11, media); put(16, hfp)
        }
    }
    @Test fun frameworkDisconnectUsesExactlyTheSelectedDevice() {
        assertTrue(link.disconnect(11))
        assertEquals(listOf(selected), media.disconnected.map { it.address })
        assertEquals(LinkState.CONNECTED, link.states()[11]) // accepted is not confirmed
        link.close()
    }
    @Test fun nativeCallBlocksEvenWhenAudioManagerStillReportsNormalMode() {
        hfp.calls = listOf(Any())
        assertFalse(link.canDisconnect())
        assertFalse(link.disconnect(11))
        assertTrue(media.disconnected.isEmpty())
        link.close()
    }
    @Test fun otherPhoneBroadcastCannotTriggerTargetRecovery() {
        var updates = 0
        val callback: () -> Unit = { updates++ }
        ReflectionHelpers.setField(link, "changed", callback)
        val receiver = ReflectionHelpers.getField<BroadcastReceiver>(link, "receiver")
        @Suppress("DEPRECATION")
        val other = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:11:22:33:44:66")
        receiver.onReceive(app, Intent().putExtra(BluetoothDevice.EXTRA_DEVICE, other))
        assertEquals(0, updates)
        @Suppress("DEPRECATION")
        val target = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(selected)
        receiver.onReceive(app, Intent().putExtra(BluetoothDevice.EXTRA_DEVICE, target))
        assertEquals(1, updates)
        link.close()
    }
    @Test fun closedLinkNeverSendsMoreDisconnects() {
        link.close()
        assertFalse(link.disconnect(11))
        assertTrue(media.disconnected.isEmpty())
    }
}
