package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayMediaCallbackTest {
    private val sent = mutableListOf<Int>()
    private val callback = CarPlayMediaCallback { index, _, _ -> sent += index }

    @Test
    fun controllerPlayAndPauseAreExplicit() {
        callback.onPlay()
        callback.onPause()
        callback.onSkipToNext()
        callback.onSkipToPrevious()

        assertEquals(
            listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE, CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS),
            sent,
        )
    }

    @Test
    fun hardwarePlayAndPauseKeysToggle() {
        press(KeyEvent.KEYCODE_MEDIA_PLAY)
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        press(CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE)

        assertEquals(List(3) { CarPlayMediaButton.PLAY_PAUSE }, sent)
    }

    @Test
    fun duplicateDeliveryWithinDebounceSendsOnce() {
        // Some ROMs deliver the same press through both the media-button event and the skip
        // callback; the debounce must collapse them into a single CarPlay press.
        callback.onSkipToNext()
        callback.onMediaButtonEvent(button(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0)))

        assertEquals(listOf(CarPlayMediaButton.NEXT), sent)
    }

    @Test
    fun hardwarePressesAreMarkedAsWheelButControllerSkipsAreNot() {
        // Only the steering wheel may be silenced; the head unit's own on-screen control must
        // keep skipping, so the flag has to travel with every press.
        val fromWheel = mutableListOf<Boolean>()
        val probe = CarPlayMediaCallback { _, _, wheel -> fromWheel += wheel }

        probe.onMediaButtonEvent(button(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0)))
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        probe.onSkipToNext()
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        probe.onSkipToPrevious()

        assertEquals(listOf(true, false, false), fromWheel)
    }

    @Test
    fun aHeldKeySendsOnePress() {
        press(KeyEvent.KEYCODE_MEDIA_NEXT, repeat = 1)
        callback.onMediaButtonEvent(button(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0)))

        assertEquals(listOf(CarPlayMediaButton.NEXT), sent)
    }

    @Test
    fun nowPlayingFieldsBecomeAndroidMediaMetadata() {
        val artwork = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val metadata = CarPlayMediaKeys.androidMetadata(
            CarPlayNowPlaying(
                title = "Dreams",
                album = "Rumours",
                artist = "Fleetwood Mac",
                sourceApp = "Music",
                durationMillis = 257_000,
            ),
            artwork,
        )

        assertEquals("Dreams", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals("Dreams", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        assertEquals("Fleetwood Mac", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals("Fleetwood Mac", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
        assertEquals("Rumours", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM))
        assertEquals("Music", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION))
        assertEquals(257_000, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        assertEquals(artwork, metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertEquals(artwork, metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
    }

    private fun press(keyCode: Int, repeat: Int = 0) {
        for (count in 0..repeat) {
            callback.onMediaButtonEvent(button(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, count)))
        }
    }

    private fun button(event: KeyEvent) = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event)

    @Test
    fun positionAndPlayStateDoNotRepublishMetadata() {
        val song = CarPlayNowPlaying(title = "Song", artist = "Artist", artworkTransferId = 7, elapsedMillis = 1_000, playing = true)
        assertEquals(false, CarPlayMediaKeys.metadataChanged(song, song.copy(elapsedMillis = 1_450)))
        assertEquals(false, CarPlayMediaKeys.metadataChanged(song, song.copy(playing = false)))
        assertEquals(true, CarPlayMediaKeys.metadataChanged(song, song.copy(title = "Next")))
        assertEquals(true, CarPlayMediaKeys.metadataChanged(song, song.copy(artworkTransferId = 8)))
        assertEquals(true, CarPlayMediaKeys.metadataChanged(CarPlayNowPlaying(), song))
    }
}
