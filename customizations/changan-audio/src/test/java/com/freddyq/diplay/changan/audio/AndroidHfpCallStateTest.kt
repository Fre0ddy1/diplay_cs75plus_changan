package com.freddyq.diplay.changan.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.os.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidHfpCallStateTest {
    private val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("02:11:22:33:44:55")
    private var connected = 2
    private var count = 0
    private var denied = false
    private var garbage = false
    private var unbound = 0
    private val transactions = mutableListOf<Int>()
    private val binder = object : Binder() {
        init { attachInterface(null, AndroidHfpCallState.TOKEN) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            transactions += code
            assertTrue(code in setOf(5, 10)) // Only getConnectionState and getCurrentCalls.
            data.enforceInterface(AndroidHfpCallState.TOKEN)
            assertEquals(1, data.readInt())
            assertEquals(device.address, BluetoothDevice.CREATOR.createFromParcel(data).address)
            if (denied) { reply!!.writeException(SecurityException("denied")); return true }
            reply!!.writeNoException(); reply.writeInt(if (code == 5) connected else count)
            if (garbage && code == 10) reply.writeInt(9)
            return true
        }
    }
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            assertEquals("com.android.bluetooth.hfpclient.HeadsetClientService", intent.component!!.className)
            assertEquals(AndroidHfpCallState.TOKEN, intent.action)
            connection.onServiceConnected(intent.component, binder); return true
        }
        override fun unbindService(connection: ServiceConnection) { unbound++ }
    }
    private fun reader() = AndroidHfpCallState(context, {}).apply { start {} }
    @Test fun verifiedIdleQueriesOnlyThisPeerAndNeverCallControl() {
        val reader = reader(); assertEquals(true, reader.idle(device))
        assertEquals(listOf(5, 10), transactions)
        reader.close(); assertEquals(1, unbound)
        assertNull(reader.idle(device)); assertEquals(2, transactions.size)
    }
    @Test fun activeOrRingingCallsBlockDisconnectWithoutDecodingPhoneNumbers() {
        count = 1; val reader = reader(); assertEquals(false, reader.idle(device)); reader.close()
    }
    @Test fun unavailableServiceCannotTurnItsEmptyListIntoAnIdleConfirmation() {
        connected = 0; val reader = reader(); assertNull(reader.idle(device))
        assertEquals(listOf(5), transactions); reader.close()
    }
    @Test fun permissionFailureRemainsUnknown() {
        denied = true; val reader = reader(); assertNull(reader.idle(device)); reader.close()
    }
    @Test fun malformedOrNullListRemainsUnknown() {
        count = -1; val reader = reader(); assertNull(reader.idle(device))
        count = 0; garbage = true; assertNull(reader.idle(device)); reader.close()
    }
    @Test fun unboundOrWrongDescriptorCannotBeQueried() {
        val reader = AndroidHfpCallState(context, {}); assertNull(reader.idle(device))
        ReflectionHelpers.setField(reader, "remote", Binder().apply { attachInterface(null, "wrong") })
        assertNull(reader.idle(device)); assertTrue(transactions.isEmpty()); reader.close()
    }
    @Test fun transactionNumbersMatchTheAndroid9FrameworkStub() {
        val stub = Class.forName("android.bluetooth.IBluetoothHeadsetClient\$Stub")
        assertEquals(5, ReflectionHelpers.getStaticField<Int>(stub, "TRANSACTION_getConnectionState"))
        assertEquals(10, ReflectionHelpers.getStaticField<Int>(stub, "TRANSACTION_getCurrentCalls"))
    }
}
