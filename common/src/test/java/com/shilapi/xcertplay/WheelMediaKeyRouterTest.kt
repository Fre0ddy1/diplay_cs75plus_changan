package com.shilapi.xcertplay

import android.os.Looper
import com.shilapi.xcertplay.airplay.CarPlayMediaButton as Key
import com.shilapi.xcertplay.media.CarPlayNowPlaying
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
    private fun next(external: Boolean = true) = router.route(Key.NEXT, external) { sent++ }

    @Test fun externalTrackChangeCancelsDuplicateCarPlayKey() {
        router.observe(CarPlayNowPlaying(title = "A", playing = true))
        next()
        idle(300)
        router.observe(CarPlayNowPlaying(title = "B", playing = true))
        idle(1500)
        assertEquals(0, sent)
    }
    @Test fun unchangedMetadataDoesNotMistakeElapsedUpdateForTrackChange() {
        router.observe(CarPlayNowPlaying(title = "A", elapsedMillis = 2000))
        next()
        router.observe(CarPlayNowPlaying(title = "A", elapsedMillis = 2500))
        idle(1000)
        assertEquals(1, sent)
    }
    @Test fun androidWindowAndSessionDuplicateUseOneFallback() {
        next(); idle(80); next(); idle(1000)
        assertEquals(1, sent)
    }
    @Test fun noExternalResponseStillControlsCarPlayOnce() {
        next(); idle(999); assertEquals(0, sent)
        idle(1); assertEquals(1, sent)
        idle(10000); assertEquals(1, sent)
    }
    @Test fun lateArtworkForSameSongIsNotAnExternalTrackChange() {
        router.observe(CarPlayNowPlaying(title = "A", artworkTransferId = 1))
        next()
        router.observe(CarPlayNowPlaying(title = "A", artworkTransferId = 2))
        idle(1000); assertEquals(1, sent)
    }
    @Test fun directModeHasNoWaitAndDeduplicatesAndroidPaths() {
        next(false); next(false)
        assertEquals(1, sent)
        idle(300); next(false); assertEquals(2, sent)
    }
    @Test fun externalPauseCancelsPendingToggle() {
        router.observe(CarPlayNowPlaying(playing = true))
        router.route(Key.PLAY_PAUSE, true) { sent++ }
        router.observe(CarPlayNowPlaying(playing = false))
        idle(2000); assertEquals(0, sent)
    }
    @Test fun sessionEndCancelsOldControllerKey() {
        next(); router.cancel(); idle(5000)
        assertEquals(0, sent)
    }
    @Test fun deliberateRepeatedPressIsRetained() {
        next(); idle(300); next(); idle(1000)
        assertEquals(2, sent)
    }
}
