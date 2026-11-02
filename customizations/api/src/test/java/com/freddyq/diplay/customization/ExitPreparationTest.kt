package com.freddyq.diplay.customization

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ExitPreparationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Test fun emptyRegistryCompletesImmediately() {
        var count = 0
        VehicleCustomizationRegistry().prepareApplicationExit(app) { count++ }
        assertEquals(1, count)
    }
    @Test fun waitsForEachClaimedCleanupAndIgnoresDuplicateCompletion() {
        val registry = VehicleCustomizationRegistry()
        val pending = mutableListOf<() -> Unit>()
        repeat(2) { index -> registry.register(object : VehicleCustomization {
            override val id = "cleanup-$index"
            override fun prepareApplicationExit(context: Context, completed: () -> Unit): Boolean {
                pending += completed; return true
            }
        }) }
        var count = 0; registry.prepareApplicationExit(app) { count++ }
        pending.first()(); pending.first()(); assertEquals(0, count)
        pending.last()(); pending.last()(); assertEquals(1, count)
    }
    @Test fun synchronousFalseAndThrowingModulesCannotStrandOtherCleanups() {
        val registry = VehicleCustomizationRegistry()
        registry.register(object : VehicleCustomization {
            override val id = "synchronous"
            override fun prepareApplicationExit(context: Context, completed: () -> Unit): Boolean { completed(); return false }
        })
        registry.register(object : VehicleCustomization {
            override val id = "throwing"
            override fun prepareApplicationExit(context: Context, completed: () -> Unit): Boolean { throw IllegalStateException("test") }
        })
        var count = 0; registry.prepareApplicationExit(app) { count++ }
        assertEquals(1, count)
    }
    @Test fun connectionLifecyclePreservesSelectedPeerAndIsolatesAnExtensionFailure() {
        val registry = VehicleCustomizationRegistry(); val owner = Any()
        val events = mutableListOf<Any>()
        registry.register(object : VehicleCustomization {
            override val id = "broken"
            override fun onBluetoothMediaConnectionStarting(context: Context, connection: WirelessAudioConnection) { error("test") }
        })
        registry.register(object : VehicleCustomization {
            override val id = "working"
            override fun onBluetoothMediaConnectionStarting(context: Context, connection: WirelessAudioConnection) {
                events += connection.owner; events += connection.deviceAddress
            }
            override fun onBluetoothMediaConnectionEnded(owner: Any) { events += owner }
        })
        registry.onBluetoothMediaConnectionStarting(app, WirelessAudioConnection(owner, "02:11:22:33:44:55"))
        registry.onBluetoothMediaConnectionEnded(owner)
        assertSame(owner, events[0]); assertEquals("02:11:22:33:44:55", events[1]); assertSame(owner, events[2])
    }
}
