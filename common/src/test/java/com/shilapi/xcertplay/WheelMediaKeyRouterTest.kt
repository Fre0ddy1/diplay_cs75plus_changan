package com.shilapi.xcertplay
import android.os.Looper
import com.shilapi.xcertplay.airplay.CarPlayMediaButton as Key
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelMediaKeyRouterTest {
    private val router = WheelMediaKeyRouter()
    private var sent = 0
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    private fun next(external: Boolean = false) = router.route(Key.NEXT, external) { sent++ }
    @Test fun carPlayModeSendsImmediately() { next(); assertEquals(1, sent) }
    @Test fun windowAndMediaSessionDuplicateIsOneAction() { next(); idle(80); next(); assertEquals(1, sent) }
    @Test fun explicitExternalModeNeverSchedulesCarPlayFallback() { next(true); idle(10000); assertEquals(0, sent) }
    @Test fun deliberateRepeatedPressRemainsTwoActions() { next(); idle(300); next(); assertEquals(2, sent) }
    @Test fun modeChangeAppliesToNextKeyWithoutOldRouteDebounce() { next(true); next(false); assertEquals(1, sent) }
    @Test fun cancelDoesNotLeaveAnyDelayedActions() { next(true); router.cancel(); idle(10000); assertEquals(0, sent) }
    @Test fun differentDirectionsAreNotCollapsed() {
        next(); router.route(Key.PREVIOUS, false) { sent++ }; assertEquals(2, sent)
    }
}
