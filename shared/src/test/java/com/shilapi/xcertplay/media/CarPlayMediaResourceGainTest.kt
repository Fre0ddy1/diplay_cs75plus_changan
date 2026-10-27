package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayMediaResourceGainTest {
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private val track = AudioTrack.Builder().setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setBufferSizeInBytes(4096).build()
    private val coordinator = AudioFocusCoordinator(RuntimeEnvironment.getApplication(), false, { 75 })
    @After fun close() { coordinator.release(track); track.release() }
    @Test fun resourceDenialCannotBeUndoneByNavigationEnding() {
        coordinator.acquire(track, AudioChannel.MEDIA, attributes)
        coordinator.setMediaResourceAllowed(false)
        coordinator.setGuidanceDucking(false)
        coordinator.resetPlaybackVolume("navigation ended")
        assertEquals(0f, coordinator.currentMediaGain(), 0f)
    }
    @Test fun phoneOwnershipRestoresCurrentDuckRatherThanForcingFullVolume() {
        coordinator.acquire(track, AudioChannel.MEDIA, attributes)
        coordinator.setGuidanceDucking(true)
        coordinator.setMediaResourceAllowed(false)
        assertEquals(0f, coordinator.currentMediaGain(), 0f)
        coordinator.setMediaResourceAllowed(true)
        assertEquals(0.75f, coordinator.currentMediaGain(), 0.001f)
    }
    @Test fun mediaSessionOwnershipAvoidsSecondMediaFocusRequest() {
        val external = AudioFocusCoordinator(RuntimeEnvironment.getApplication(), true, mediaFocusManagedExternally = true)
        try {
            external.acquire(track, AudioChannel.MEDIA, attributes)
            assertNull(ReflectionHelpers.getField<Any?>(external, "request"))
        } finally { external.release(track) }
    }
    @Test fun alternateNavigationDoesNotJoinManagedMediaTracks() {
        coordinator.acquire(track, AudioChannel.NAVIGATION, attributes)
        coordinator.setMediaResourceAllowed(false)
        val entries = ReflectionHelpers.getField<Map<*, *>>(coordinator, "active")
        assertFalse(entries.containsKey(track))
    }
}
