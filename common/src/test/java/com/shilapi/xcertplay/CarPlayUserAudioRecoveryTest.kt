package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
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

/** v70 intentionally removes the v67 per-key Bluetooth preparation contract. */
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
        `when`(controller.isPhoneMediaResourceReady()).thenReturn(true)
        CarPlayMediaKeys.attach(app, controller)
        CarPlayMediaKeys.onPhoneSessionStarted()
        CarPlayMediaKeys.onMainAudioOwner(CarPlayAudioOwner.PHONE)
    }
    @After fun end() {
        Preparation.enabled = false; Preparation.callbacks.clear()
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        CarPlayMediaKeys.detach(controller)
    }
    @Test fun explicitPlayCanClaimFocusWithAConfirmedPhoneResource() {
        CarPlayMediaKeys.fromWindow(Key.PLAY)
        assertTrue(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
        verify(controller, times(1)).sendMediaButton(Key.PLAY)
    }
    @Test fun legacyPreparationIsNeverCalledAndPlayHasNoBluetoothDeadline() {
        Preparation.enabled = true
        val before = SystemClock.elapsedRealtime()
        CarPlayMediaKeys.fromWindow(Key.PLAY)
        assertTrue(Preparation.callbacks.isEmpty())
        verify(controller, times(1)).sendMediaButton(Key.PLAY)
        assertEquals(before, SystemClock.elapsedRealtime())
    }
    @Test fun nextDuringUnresolvedResourceLossIsImmediateWithoutFocusReclaimOrExtraPlay() {
        Preparation.enabled = true
        CarPlayMediaKeys.onMediaAudioChanged(true); idle()
        ReflectionHelpers.setField(CarPlayMediaKeys, "focusHeld", false)
        ReflectionHelpers.setField(CarPlayMediaKeys, "transientFocusInterrupted", true)
        `when`(controller.isPhoneMediaResourceReady()).thenReturn(false)
        CarPlayMediaKeys.fromWindow(Key.NEXT)
        assertTrue(Preparation.callbacks.isEmpty())
        verify(controller, times(1)).sendMediaButton(Key.NEXT)
        verify(controller, never()).sendMediaButton(Key.PLAY)
        assertFalse(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
    }
    @Test fun activeMediaSendsOneNextWithoutWaitingForLegacyHandoff() {
        Preparation.enabled = true
        CarPlayMediaKeys.onMediaAudioChanged(true); idle()
        CarPlayMediaKeys.fromWindow(Key.NEXT)
        assertTrue(Preparation.callbacks.isEmpty())
        verify(controller, times(1)).sendMediaButton(Key.NEXT)
        verify(controller, never()).sendMediaButton(Key.PLAY)
    }
    @Test fun confirmedResourceCanRecoverFocusForExactlyOneNext() {
        CarPlayMediaKeys.onMediaAudioChanged(true); idle()
        ReflectionHelpers.setField(CarPlayMediaKeys, "focusHeld", false)
        ReflectionHelpers.setField(CarPlayMediaKeys, "transientFocusInterrupted", true)
        CarPlayMediaKeys.fromWindow(Key.NEXT)
        verify(controller, times(1)).sendMediaButton(Key.NEXT)
        verify(controller, never()).sendMediaButton(Key.PLAY)
        assertTrue(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
    }
    @Test fun duplicateGestureIsStillOneAction() {
        Preparation.enabled = true
        CarPlayMediaKeys.fromWindow(Key.PLAY); CarPlayMediaKeys.fromWindow(Key.PLAY)
        assertTrue(Preparation.callbacks.isEmpty())
        verify(controller, times(1)).sendMediaButton(Key.PLAY)
    }
    @Test fun pauseAfterPlayIsSentOnceAndLateOwnershipCannotReplayEitherKey() {
        `when`(controller.isPhoneMediaResourceReady()).thenReturn(false)
        CarPlayMediaKeys.onMainAudioOwner(CarPlayAudioOwner.ACCESSORY)
        CarPlayMediaKeys.fromWindow(Key.PLAY); CarPlayMediaKeys.fromWindow(Key.PAUSE)
        `when`(controller.isPhoneMediaResourceReady()).thenReturn(true)
        CarPlayMediaKeys.onMainAudioOwner(CarPlayAudioOwner.PHONE); idle(2600)
        verify(controller, times(1)).sendMediaButton(Key.PLAY)
        verify(controller, times(1)).sendMediaButton(Key.PAUSE)
        assertFalse(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
    }
    @Test fun anOldFocusCallbackCannotAffectAReconnectedPhone() {
        val request = ReflectionHelpers.getField<android.media.AudioFocusRequest>(CarPlayMediaKeys, "focusRequest")
        CarPlayMediaKeys.onPhoneSessionEnded(); CarPlayMediaKeys.onPhoneSessionStarted()
        ReflectionHelpers.callInstanceMethod<AudioManager.OnAudioFocusChangeListener>(request, "getOnAudioFocusChangeListener")
            .onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
        verify(controller, never()).sendMediaButton(anyInt())
    }
    @Test fun aQueuedForeignThreadKeyCannotCrossThePhoneSessionBoundary() {
        `when`(controller.mediaSessionIdentity()).thenReturn(Any())
        Thread { CarPlayMediaKeys.fromWindow(Key.PLAY) }.apply { start(); join() }
        `when`(controller.mediaSessionIdentity()).thenReturn(Any())
        idle()
        verify(controller, never()).sendMediaButton(anyInt())
    }
    @Test fun externalKeyChoiceAppliesToFutureHardwareActions() {
        AirPlayPersistence.saveMediaViaCarBluetooth(app, true)
        CarPlayMediaKeys.fromWindow(Key.PLAY)
        verify(controller, never()).sendMediaButton(anyInt())
        assertTrue(Preparation.callbacks.isEmpty())
    }
    @Test fun laterTimersCannotResendTheAlreadyForwardedCommand() {
        Preparation.enabled = true
        CarPlayMediaKeys.fromWindow(Key.PLAY); idle(2600)
        assertTrue(Preparation.callbacks.isEmpty())
        verify(controller, times(1)).sendMediaButton(Key.PLAY)
        idle(4000); verify(controller, times(1)).sendMediaButton(Key.PLAY)
    }
    @Test fun failedPlaybackDoesNotHoldFocusForeverOrAutoplayAgain() {
        CarPlayMediaKeys.fromWindow(Key.PLAY); idle(5100)
        assertFalse(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
        verify(controller, times(1)).sendMediaButton(Key.PLAY)
    }
    @Test fun arrivingMediaStreamRetainsItsNormalFocus() {
        CarPlayMediaKeys.fromWindow(Key.PLAY)
        CarPlayMediaKeys.onMediaAudioChanged(true); idle(5100)
        assertTrue(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
    }
    @Test fun ongoingCallCannotSendMediaCommands() {
        Preparation.enabled = true
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        CarPlayMediaKeys.fromWindow(Key.PLAY)
        assertTrue(Preparation.callbacks.isEmpty())
        verify(controller, never()).sendMediaButton(anyInt())
    }
    @Test fun aPhoneGrantRestoresFocusAfterImmediatePlayWithoutResendingTheKey() {
        CarPlayMediaKeys.onMainAudioOwner(CarPlayAudioOwner.ACCESSORY)
        `when`(controller.isPhoneMediaResourceReady()).thenReturn(false)
        CarPlayMediaKeys.fromWindow(Key.PLAY)
        assertFalse(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
        verify(controller, times(1)).sendMediaButton(Key.PLAY)
        `when`(controller.isPhoneMediaResourceReady()).thenReturn(true)
        CarPlayMediaKeys.onMainAudioOwner(CarPlayAudioOwner.PHONE)
        assertTrue(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
        verify(controller, times(1)).sendMediaButton(Key.PLAY)
    }
    @Test fun unknownResourceCannotClaimFocusButDoesNotBlockTheExplicitKey() {
        CarPlayMediaKeys.onMainAudioOwner(CarPlayAudioOwner.UNKNOWN)
        `when`(controller.isPhoneMediaResourceReady()).thenReturn(false)
        CarPlayMediaKeys.fromWindow(Key.PLAY)
        assertFalse(ReflectionHelpers.getField<Boolean>(CarPlayMediaKeys, "focusHeld"))
        verify(controller, times(1)).sendMediaButton(Key.PLAY)
    }
    @Test fun pauseDoesNotStartResourceRecovery() {
        Preparation.enabled = true
        CarPlayMediaKeys.fromWindow(Key.PAUSE)
        verify(controller, times(1)).sendMediaButton(Key.PAUSE)
        verify(controller, never()).onUserMediaPlaybackRequested()
        assertTrue(Preparation.callbacks.isEmpty())
    }
}
