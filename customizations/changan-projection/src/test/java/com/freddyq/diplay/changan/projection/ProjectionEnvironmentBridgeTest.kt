package com.freddyq.diplay.changan.projection

import android.os.Looper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import com.freddyq.diplay.customization.ProjectionActivationControl
import com.freddyq.diplay.customization.ProjectionEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ProjectionEnvironmentBridgeTest {
    private val app get() = RuntimeEnvironment.getApplication()
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
        bridge.observe(state, first, app); idle(); idle(1000)
        bridge.observe(state, first, app); bridge.observe(state, last, app); idle()
        assertEquals(0, first.activations); assertEquals(1, last.activations)
    }
    @Test fun clearRejectsStaleQueueAndPreservesNewQueueOwnership() {
        val old = Endpoint(); val fresh = Endpoint()
        bridge.observe(state, old, app); bridge.clear(); bridge.observe(state, fresh, app); idle()
        idle(1000); bridge.observe(state, fresh, app); idle()
        assertEquals(0, old.activations); assertEquals(1, fresh.activations)
    }
    @Test fun powerTransitionOnlyInvalidatesBeforeNextObservationActivates() {
        val control = Endpoint()
        bridge.observe(state.copy(displayOn = false), control, app); idle()
        bridge.observe(state, control, app); idle()
        assertEquals(1, control.invalidations); assertEquals(0, control.activations)
        idle(1000); bridge.observe(state, control, app); idle()
        assertEquals(1, control.activations)
    }
    @Test fun prerequisiteReturnsAndActivatesWithoutReplacingPhoneSession() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO).setPackage(StockAmapBootstrap.PACKAGE),
            ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = StockAmapBootstrap.PACKAGE; name = "StockMap" } },
        )
        var launched = 0; var returned = 0; var activated = 0; var contentInvalidated = 0
        val managed = ProjectionEnvironmentBridge { contentInvalidated++ }
        val control = object : ProjectionActivationControl {
            override fun invalidateChannel(): Boolean { managed.cancelPrerequisite(); return true }
            override fun activate(): Boolean { activated++; return true }
            override fun launchPrerequisite(intent: Intent): Boolean { launched++; return true }
            override fun returnToProjection(): Boolean { returned++; return true }
        }
        managed.observe(state, control, app); idle()
        assertTrue(managed.prerequisiteWaiting)
        idle(1500); managed.observe(state, control, app); idle()
        assertEquals(0, activated)
        idle(500)
        assertEquals(1, returned); assertEquals(1, contentInvalidated)
        managed.observe(state, control, app); idle(); idle(1000)
        managed.observe(state, control, app); idle()
        assertEquals(1, activated); assertEquals(1, launched)
    }
    @Test fun powerLossCancelsReturnEvenWithoutReleaseNotificationFromEndpoint() {
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO).setPackage(StockAmapBootstrap.PACKAGE),
            ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = StockAmapBootstrap.PACKAGE; name = "StockMap" } },
        )
        var returned = 0
        val control = object : ProjectionActivationControl {
            override fun invalidateChannel() = true
            override fun activate() = true
            override fun launchPrerequisite(intent: Intent) = true
            override fun returnToProjection(): Boolean { returned++; return true }
        }
        bridge.observe(state, control, app); idle()
        assertTrue(bridge.prerequisiteWaiting)
        bridge.observe(state.copy(displayOn = false), control, app); idle()
        idle(5000)
        assertEquals(0, returned); assertFalse(bridge.prerequisiteWaiting)
    }
}
