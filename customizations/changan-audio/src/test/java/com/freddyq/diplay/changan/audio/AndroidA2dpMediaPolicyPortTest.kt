package com.freddyq.diplay.changan.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidA2dpMediaPolicyPortTest {
    private val address = "02:11:22:33:44:55"
    private val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
    private var priority = 1000
    private var denied = false
    private var permission = true
    private var binds = 0
    private var unbinds = 0
    private val transactions = mutableListOf<Int>()
    private val binder = object : Binder() {
        init { attachInterface(null, AndroidA2dpMediaPolicyPort.TOKEN) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(AndroidA2dpMediaPolicyPort.TOKEN)
            assertEquals(1, data.readInt())
            assertEquals(address, BluetoothDevice.CREATOR.createFromParcel(data).address)
            assertTrue(code in setOf(2, 7, 8))
            transactions += code
            if (denied) { reply!!.writeException(SecurityException("denied")); return true }
            if (code == 7) priority = data.readInt()
            reply!!.writeNoException(); reply.writeInt(if (code == 8) priority else 1)
            return true
        }
    }
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun checkSelfPermission(name: String) = if (permission) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            binds++
            assertEquals("com.android.bluetooth.a2dpsink.A2dpSinkService", intent.component!!.className)
            assertEquals(AndroidA2dpMediaPolicyPort.TOKEN, intent.action)
            connection.onServiceConnected(intent.component, binder)
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }
    private fun port() = AndroidA2dpMediaPolicyPort(context, {}).also {
        shadowOf(BluetoothAdapter.getDefaultAdapter()).setState(BluetoothAdapter.STATE_ON)
        shadowOf(device).setBondState(BluetoothDevice.BOND_BONDED)
        assertTrue(it.start {})
    }
    @Test fun policyCanBeReadDisabledAndRestoredExactlyWithoutOtherProfiles() {
        val port = port()
        assertEquals(1000, port.priority(address))
        assertTrue(port.setPriority(address, 0)); assertEquals(0, port.priority(address))
        assertTrue(port.disconnectMedia(address))
        assertTrue(port.setPriority(address, 1000)); assertEquals(1000, port.priority(address))
        assertEquals(listOf(8, 7, 8, 2, 7, 8), transactions)
        port.close(); assertEquals(1, unbinds)
    }
    @Test fun deniedReadIsUnknownAndNeverMistakenForAlreadyDisabled() {
        val port = port(); denied = true
        assertNull(port.priority(address)); assertFalse(port.setPriority(address, 0)); port.close()
    }
    @Test fun unpairedOrInvalidAddressCannotBeModified() {
        val port = port(); shadowOf(device).setBondState(BluetoothDevice.BOND_NONE)
        assertNull(port.priority(address)); assertFalse(port.setPriority(address, 0))
        assertFalse(port.setPriority("invalid", 0)); assertTrue(transactions.isEmpty()); port.close()
    }
    @Test fun unknownPriorityValuesAreNeverWritten() {
        val port = port()
        assertFalse(port.setPriority(address, -1)); assertFalse(port.setPriority(address, 78))
        assertTrue(transactions.isEmpty()); port.close()
    }
    @Test fun permissionDenialStopsBeforeBinding() {
        permission = false
        val port = AndroidA2dpMediaPolicyPort(context, {})
        assertFalse(port.start {}); assertEquals(0, binds); port.close()
    }
    @Test fun closedOrWrongDescriptorDoesNotSendAnyTransaction() {
        val port = port()
        ReflectionHelpers.setField(port, "remote", Binder().apply { attachInterface(null, "wrong") })
        assertNull(port.priority(address)); assertTrue(transactions.isEmpty())
        port.close(); assertFalse(port.setPriority(address, 0)); assertTrue(transactions.isEmpty())
    }
    @Test fun transactionNumbersMatchTheAndroid9Framework() {
        val stub = Class.forName("android.bluetooth.IBluetoothA2dpSink\$Stub")
        assertEquals(AndroidA2dpMediaPolicyPort.DISCONNECT, ReflectionHelpers.getStaticField<Int>(stub, "TRANSACTION_disconnect"))
        assertEquals(AndroidA2dpMediaPolicyPort.SET_PRIORITY, ReflectionHelpers.getStaticField<Int>(stub, "TRANSACTION_setPriority"))
        assertEquals(AndroidA2dpMediaPolicyPort.GET_PRIORITY, ReflectionHelpers.getStaticField<Int>(stub, "TRANSACTION_getPriority"))
    }
    @Test @Config(sdk = [33]) fun anotherAndroidVersionDoesNotUseAnUnverifiedBinderAbi() {
        val port = AndroidA2dpMediaPolicyPort(context, {})
        assertFalse(port.start {}); assertEquals(0, binds); port.close()
    }
}
