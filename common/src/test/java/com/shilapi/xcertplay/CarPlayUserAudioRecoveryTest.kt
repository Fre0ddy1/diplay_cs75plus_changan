package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import com.freddyq.diplay.customization.VehicleCustomization
import com.freddyq.diplay.customization.VehicleCustomizations
import com.shilapi.xcertplay.airplay.CarPlayAudioOwner
import com.shilapi.xcertplay.airplay.CarPlayMediaButton as Key
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayUserAudioRecoveryTest {
    private object Preparation : VehicleCustomization {
        override val id = "test-user-audio-recovery"
        var enabled = false
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        override fun prepareMediaPlayback(context: Context, completed: (Boolean) -> Unit): Boolean {
            if (!enabled) return false
            callbacks += completed
            return true
        }
    }
    private val app get() = RuntimeEnvironment.getApplication()
    private val controller = mock(CarPlayController::class.java)
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    @Before fun start() {
        Preparation.enabled = false; Preparation.callbacks.clear()
        VehicleCustomizations.register(Preparation)
        AirPlayPersistence.saveMediaViaCarBluetooth(app, false)
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
        CarPlayMediaKeys.attach(app, controller)
        CarPlayMediaKeys.onPhoneSessionStarted()
        CarPlayMediaKeys.onMainAudioOwner(CarPlayAudioOwner.PHONE)
    }
    @After fun end() {
        Preparation.enabled = false; Preparation.callbacks.clear()
        CarPlayMediaKeys.detach(controller)
    }
    @Test fun explicitPlayCanClaimFocusWithoutAnExistingMediaStream() {
        CarPlayMediaKeys.fromWindow(Key.PLAY)
        assertTrue(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
        verify(controller, times(1)).sendMediaButton(Key.PLAY)
    }
    @Test fun preparationRunsBeforeOneMediaCommandAndDuplicateCallbackCannotReplay() {
        Preparation.enabled = true
        CarPlayMediaKeys.fromWindow(Key.PLAY)
        verify(controller, never()).sendMediaButton(anyInt())
        assertEquals(1, Preparation.callbacks.size)
        Preparation.callbacks.single()(true); Preparation.callbacks.single()(true); idle()
        verify(controller, times(1)).sendMediaButton(Key.PLAY)
    }
    @Test fun duplicateGestureWhileRecoveryPendingIsStillOneAction() {
        Preparation.enabled = true
        CarPlayMediaKeys.fromWindow(Key.PLAY); CarPlayMediaKeys.fromWindow(Key.PLAY)
        assertEquals(1, Preparation.callbacks.size)
        Preparation.callbacks.single()(true); idle()
        verify(controller, times(1)).sendMediaButton(Key.PLAY)
    }
    @Test fun pauseCancelsAnOlderPendingPlay() {
        Preparation.enabled = true
        CarPlayMediaKeys.fromWindow(Key.PLAY)
        CarPlayMediaKeys.fromWindow(Key.PAUSE)
        Preparation.callbacks.single()(true); idle()
        verify(controller, never()).sendMediaButton(Key.PLAY)
        verify(controller, times(1)).sendMediaButton(Key.PAUSE)
    }
    @Test fun oldRecoveryCannotSendIntoAReconnectedPhoneSession() {
        Preparation.enabled = true
        CarPlayMediaKeys.fromWindow(Key.PLAY)
        CarPlayMediaKeys.onPhoneSessionEnded(); CarPlayMediaKeys.onPhoneSessionStarted()
        Preparation.callbacks.single()(true); idle()
        verify(controller, never()).sendMediaButton(Key.PLAY)
    }
    @Test fun phoneIdentityStopsLateDeliveryEvenBeforeUiReceivesDisconnect() {
        Preparation.enabled = true
        `when`(controller.mediaSessionIdentity()).thenReturn(Any())
        CarPlayMediaKeys.fromWindow(Key.PLAY)
        `when`(controller.mediaSessionIdentity()).thenReturn(Any())
        Preparation.callbacks.single()(true); idle()
        verify(controller, never()).sendMediaButton(Key.PLAY)
    }
    @Test fun switchingToExternalKeysCancelsPendingCarPlayKey() {
        Preparation.enabled = true
        CarPlayMediaKeys.fromWindow(Key.PLAY)
        AirPlayPersistence.saveMediaViaCarBluetooth(app, true)
        Preparation.callbacks.single()(true); idle()
        verify(controller, never()).sendMediaButton(Key.PLAY)
    }
    @Test fun hungPreparationHasOneBoundedFallbackAndLateResultDoesNotReplay() {
        Preparation.enabled = true
        CarPlayMediaKeys.fromWindow(Key.PLAY); idle(2600)
        verify(controller, times(1)).sendMediaButton(Key.PLAY)
        Preparation.callbacks.single()(true); idle()
        verify(controller, times(1)).sendMediaButton(Key.PLAY)
    }
    @Test fun failedRecoveryDoesNotHoldFocusForeverOrAutoplayAgain() {
        CarPlayMediaKeys.fromWindow(Key.PLAY); idle(5100)
        assertFalse(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
        verify(controller, times(1)).sendMediaButton(Key.PLAY)
    }
    @Test fun arrivingMediaStreamRetainsItsNormalFocus() {
        CarPlayMediaKeys.fromWindow(Key.PLAY)
        CarPlayMediaKeys.onMediaAudioChanged(true); idle(5100)
        assertTrue(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
    }
    @Test fun ongoingCallCannotStartRecoveryOrSendMediaCommands() {
        Preparation.enabled = true
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        CarPlayMediaKeys.fromWindow(Key.PLAY)
        assertTrue(Preparation.callbacks.isEmpty())
        verify(controller, never()).sendMediaButton(anyInt())
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
    }
}
