package com.shilapi.xcertplay

import android.media.AudioManager
import android.os.Looper
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayMediaFocusOwnershipTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controller = mock(CarPlayController::class.java)
    @After fun end() {
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        CarPlayMediaKeys.detach(controller)
    }

    @Test fun bluetoothKeyPreferenceKeepsMediaSessionAndFocus() {
        AirPlayPersistence.saveMediaViaCarBluetooth(app, true)
        CarPlayMediaKeys.attach(app, controller)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "session"))
        assertNotNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "focusRequest"))
        assertNotNull(shadowOf(app.getSystemService(AudioManager::class.java)).lastAudioFocusRequest)
    }

    @Test fun wheelPreferenceDoesNotChangeSavedMediaAndNavigationChannels() {
        AirPlayPersistence.saveMediaAudioChannel(app, 12)
        AirPlayPersistence.saveNavigationAudioChannel(app, 10)
        for (enabled in listOf(true, false, true)) {
            AirPlayPersistence.saveMediaViaCarBluetooth(app, enabled)
            assertEquals(12, AirPlayPersistence.loadMediaAudioChannel(app))
            assertEquals(10, AirPlayPersistence.loadNavigationAudioChannel(app))
        }
    }

    @Test fun disconnectReleasesFocusWhileControllerCanReconnect() {
        CarPlayMediaKeys.attach(app, controller)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        CarPlayMediaKeys.onPhoneSessionEnded()
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "session"))
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "focusRequest"))
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "session"))
    }

    @Test fun externalTrackResponseRestoresLostCarPlayFocusWithoutSecondKey() {
        CarPlayMediaKeys.attach(app, controller)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        val router = ReflectionHelpers.getField<WheelMediaKeyRouter>(CarPlayMediaKeys, "wheelRouter")
        router.observe(CarPlayNowPlaying(title = "A"))
        var duplicateKeys = 0
        router.route(CarPlayMediaButton.NEXT, true) { duplicateKeys++ }
        ReflectionHelpers.setField(CarPlayMediaKeys, "focusHeld", false)
        ReflectionHelpers.callInstanceMethod<Unit>(CarPlayMediaKeys, "onNowPlayingChanged",
            ReflectionHelpers.ClassParameter.from(CarPlayController::class.java, controller),
            ReflectionHelpers.ClassParameter.from(CarPlayNowPlaying::class.java, CarPlayNowPlaying(title = "B")))
        shadowOf(Looper.getMainLooper()).idleFor(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
        assertEquals(0, duplicateKeys)
    }

    @Test fun playbackFeedbackDoesNotReclaimFocusDuringAnOngoingCall() {
        CarPlayMediaKeys.attach(app, controller)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        ReflectionHelpers.setField(CarPlayMediaKeys, "focusHeld", false)
        ReflectionHelpers.callInstanceMethod<Unit>(CarPlayMediaKeys, "onIphonePlaying",
            ReflectionHelpers.ClassParameter.from(CarPlayController::class.java, controller),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
    }
}
