package com.freddyq.diplay.changan.projection

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
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
class StockAmapBootstrapTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val state = ProjectionEnvironment(1, true, true, 1, true, false, true, true, false, 0)
    private var rearms = 0
    private val bootstrap = StockAmapBootstrap { rearms++ }
    private val endpoint = Endpoint()
    private inner class Endpoint : ProjectionActivationControl {
        var launches = 0; var returns = 0; var invalidations = 0
        var launchAccepted = true
        var valid = true
        var onInvalidate: () -> Unit = {}
        override fun invalidateChannel(): Boolean { invalidations++; onInvalidate(); return valid }
        override fun activate() = valid
        override fun launchPrerequisite(intent: Intent): Boolean {
            assertEquals(StockAmapBootstrap.PACKAGE, intent.`package`)
            launches++
            return launchAccepted
        }
        override fun returnToProjection(): Boolean { returns++; return valid }
    }
    private fun installLauncher() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_INFO).setPackage(StockAmapBootstrap.PACKAGE)
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = StockAmapBootstrap.PACKAGE; name = "StockMap" }
        })
    }
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    @Test fun serviceVisibilityIsNotUsedAsMapReadiness() {
        installLauncher()
        endpoint.onInvalidate = { bootstrap.cancel() }
        assertFalse(bootstrap.ready(app, state, endpoint))
        repeat(5) { assertFalse(bootstrap.ready(app, state, endpoint)) }
        assertEquals(1, endpoint.launches)
        idle(2000)
        assertEquals(1, endpoint.returns)
        assertEquals(1, rearms)
        assertTrue(bootstrap.ready(app, state, endpoint))
        assertEquals(1, endpoint.launches)
    }
    @Test fun sessionEndNeverReturnsOldHostOrRearmsOldBudget() {
        installLauncher(); bootstrap.ready(app, state, endpoint)
        bootstrap.sessionEnded(); idle(5000)
        assertEquals(0, endpoint.returns); assertEquals(0, rearms)
    }
    @Test fun userCloseAndPowerLossCancelDeferredForegroundReturn() {
        installLauncher(); bootstrap.ready(app, state, endpoint)
        assertFalse(bootstrap.ready(app, state.copy(userClosed = true), endpoint))
        idle(5000); assertEquals(0, endpoint.returns)
        bootstrap.sessionEnded()
        bootstrap.ready(app, state, endpoint)
        bootstrap.ready(app, state.copy(displayOn = false), endpoint)
        idle(5000); assertEquals(0, endpoint.returns)
    }
    @Test fun healthyWarmReconnectDoesNotRelaunchMap() {
        installLauncher()
        assertTrue(bootstrap.ready(app, state.copy(channelOpen = true), endpoint))
        bootstrap.sessionEnded()
        assertTrue(bootstrap.ready(app, state, endpoint))
        assertEquals(0, endpoint.launches)
    }
    @Test fun replacedServiceNeedsInitializationButOnlyCurrentEndpointReturns() {
        installLauncher(); bootstrap.ready(app, state, endpoint)
        idle(1000)
        val fresh = Endpoint()
        bootstrap.ready(app, state.copy(serviceEpoch = 2), fresh)
        idle(2000)
        assertEquals(0, endpoint.returns); assertEquals(1, fresh.returns); assertEquals(1, rearms)
    }
    @Test fun launchFailureIsBoundedAndDoesNotBlockDirectRecovery() {
        installLauncher(); endpoint.launchAccepted = false
        assertTrue(bootstrap.ready(app, state, endpoint))
        repeat(20) { assertTrue(bootstrap.ready(app, state, endpoint)) }
        idle(10000)
        assertEquals(1, endpoint.launches); assertEquals(0, endpoint.returns)
    }
    @Test fun missingStockMapDoesNotBlockOtherVehicles() {
        assertTrue(bootstrap.ready(app, state, endpoint))
        assertEquals(0, endpoint.launches)
    }
    @Test fun exhaustedWarmRecoveryGetsExactlyOneBootstrapFallback() {
        installLauncher()
        bootstrap.ready(app, state.copy(channelOpen = true), endpoint)
        bootstrap.sessionEnded()
        bootstrap.retryAfterExhaustion()
        assertFalse(bootstrap.ready(app, state, endpoint)); idle(2000)
        repeat(10) { bootstrap.retryAfterExhaustion(); bootstrap.ready(app, state, endpoint) }
        assertEquals(1, endpoint.launches); assertEquals(1, rearms)
    }
}
