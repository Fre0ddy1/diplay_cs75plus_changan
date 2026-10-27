package com.freddyq.diplay.changan.projection

import android.os.Looper
import com.freddyq.diplay.customization.ProjectionContentControl
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
class ContentHandoffRecoveryTest {
    private val policy = ContentHandoffRecovery()
    private var sent = 0
    private val window = Any()
    private val endpoint = ProjectionContentControl { result -> sent++; result(true) }
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    @Test fun waitsForRealLocalFrameThenDispatchesOnce() {
        idle(10000); assertEquals(0, sent)
        policy.frameReady(window, endpoint); idle(1499); assertEquals(0, sent)
        idle(1); policy.frameReady(window, endpoint); idle(10000); assertEquals(1, sent)
    }
    @Test fun retainedWindowDoesNotRepeatSourceSwitchAfterSessionCancellation() {
        policy.frameReady(window, endpoint); idle(1500)
        policy.cancel(); policy.frameReady(window, endpoint); idle(10000)
        assertEquals(1, sent)
    }
    @Test fun newWindowMustHandoffAgain() {
        policy.frameReady(window, endpoint); idle(1500)
        policy.frameReady(Any(), endpoint); idle(1500)
        assertEquals(2, sent)
    }
    @Test fun deadBinderOrMissingReplyHasFiniteBudget() {
        policy.frameReady(window, ProjectionContentControl { sent++ })
        idle(60000); policy.frameReady(window, endpoint); idle(60000)
        assertEquals(2, sent)
    }
    @Test fun sessionEndCancelsQueuedSourceSwitch() {
        policy.frameReady(window, endpoint); policy.cancel(); idle(10000)
        assertEquals(0, sent)
    }
    @Test fun manualControlStopsAutomaticTakeover() {
        policy.frameReady(window, endpoint); policy.suspend()
        policy.frameReady(window, endpoint); idle(10000); assertEquals(0, sent)
    }
    @Test fun expiredWindowReplyCannotCompleteNewWindow() {
        var stale: ((Boolean) -> Unit)? = null
        policy.frameReady(window, ProjectionContentControl { stale = it }); idle(1500)
        policy.frameReady(Any(), endpoint); stale!!(true); idle(1500)
        assertEquals(1, sent)
    }
}
