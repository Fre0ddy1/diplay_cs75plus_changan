package com.freddyq.diplay.changan.projection

import android.os.Looper
import com.freddyq.diplay.customization.ProjectionActivationControl
import com.freddyq.diplay.customization.ProjectionEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ProjectionEnvironmentBridgeTest {
    private val state = ProjectionEnvironment(2, true, true, 1, true, false, true, true, false, 0)
    private val bridge = ProjectionEnvironmentBridge()
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    private class Endpoint : ProjectionActivationControl {
        var activations = 0; var invalidations = 0
        override fun invalidateChannel(): Boolean { invalidations++; return true }
        override fun activate(): Boolean { activations++; return true }
    }
    @Test fun coalescesQueuedObservationsToLatestEndpoint() {
        val first = Endpoint(); val last = Endpoint()
        bridge.observe(state, first); idle(); idle(1000)
        bridge.observe(state, first); bridge.observe(state, last); idle()
        assertEquals(0, first.activations); assertEquals(1, last.activations)
    }
    @Test fun clearRejectsStaleQueueAndPreservesNewQueueOwnership() {
        val old = Endpoint(); val fresh = Endpoint()
        bridge.observe(state, old); bridge.clear(); bridge.observe(state, fresh); idle()
        idle(1000); bridge.observe(state, fresh); idle()
        assertEquals(0, old.activations); assertEquals(1, fresh.activations)
    }
    @Test fun powerTransitionOnlyInvalidatesBeforeNextObservationActivates() {
        val control = Endpoint()
        bridge.observe(state.copy(displayOn = false), control); idle()
        bridge.observe(state, control); idle()
        assertEquals(1, control.invalidations); assertEquals(0, control.activations)
        idle(1000); bridge.observe(state, control); idle()
        assertEquals(1, control.activations)
    }
}
