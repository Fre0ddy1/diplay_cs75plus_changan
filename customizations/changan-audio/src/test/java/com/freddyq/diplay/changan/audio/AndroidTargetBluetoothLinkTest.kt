package com.freddyq.diplay.changan.audio

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Binder
import android.os.Parcel
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
    class HfpWithoutTheHiddenCallMethod : BluetoothProfile {
        val disconnected = mutableListOf<BluetoothDevice>()
        fun disconnect(device: BluetoothDevice): Boolean { disconnected += device; return true }
        override fun getConnectedDevices() = emptyList<BluetoothDevice>()
        override fun getDevicesMatchingConnectionStates(states: IntArray) = emptyList<BluetoothDevice>()
        override fun getConnectionState(device: BluetoothDevice) = BluetoothProfile.STATE_CONNECTED
    }
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
    @Test fun missingCallIntrospectionDefersEveryRoleUntilTheReadEndpointProvesIdle() {
        ReflectionHelpers.getField<MutableMap<Int, BluetoothProfile>>(link, "proxies")[16] = HfpWithoutTheHiddenCallMethod()
        assertFalse(link.disconnect(11))
        assertTrue(media.disconnected.isEmpty())
        assertFalse(link.disconnect(16)); assertFalse(link.disconnect(17)); assertFalse(link.disconnect(5))
        assertEquals(setOf(11, 12, 16, 17, 5), link.states().keys)
        link.close()
    }
    @Test fun permissionCheckedReadEndpointCanPermitCompleteSelectedPeerHandoffWithoutTheHiddenJavaQuery() {
        val phone = HfpWithoutTheHiddenCallMethod()
        val profiles = ReflectionHelpers.getField<MutableMap<Int, BluetoothProfile>>(link, "proxies")
        profiles[16] = phone
        val reader = ReflectionHelpers.getField<AndroidHfpCallState>(link, "calls")
        ReflectionHelpers.setField(reader, "remote", object : Binder() {
            init { attachInterface(null, AndroidHfpCallState.TOKEN) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertTrue(code in setOf(AndroidHfpCallState.GET_CURRENT_CALLS, AndroidHfpCallState.GET_CONNECTION_STATE))
                data.enforceInterface(AndroidHfpCallState.TOKEN); assertEquals(1, data.readInt())
                assertEquals(selected, BluetoothDevice.CREATOR.createFromParcel(data).address)
                reply!!.writeNoException(); reply.writeInt(if (code == AndroidHfpCallState.GET_CONNECTION_STATE) BluetoothProfile.STATE_CONNECTED else 0); return true
            }
        })
        profiles[17] = Profile(); profiles[5] = Profile()
        for (role in listOf(11, 16, 17, 5)) assertTrue(link.disconnect(role))
        assertFalse(link.disconnect(12))
        assertEquals(listOf(selected), phone.disconnected.map { it.address })
        assertEquals(listOf(selected), media.disconnected.map { it.address })
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
    @Test fun aCallBroadcastWithoutAPublicDeviceCanOnlyTriggerAReadOfTheSelectedPeer() {
        var updates = 0
        ReflectionHelpers.setField(link, "changed", { updates++ })
        val receiver = ReflectionHelpers.getField<BroadcastReceiver>(link, "receiver")
        receiver.onReceive(app, Intent("android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED"))
        assertEquals(1, updates)
        assertTrue(media.disconnected.isEmpty())
        link.close()
    }
}
