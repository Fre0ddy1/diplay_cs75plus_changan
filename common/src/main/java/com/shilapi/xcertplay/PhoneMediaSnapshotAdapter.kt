package com.shilapi.xcertplay

import com.freddyq.diplay.customization.MediaItem
import com.freddyq.diplay.customization.MediaSnapshot
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** Keeps a monotonic position anchor without changing Android's audio or media-session policy. */
internal class PhoneMediaSnapshotAdapter {
    private var previous: CarPlayNowPlaying? = null
    private var retained: MediaSnapshot? = null

    fun update(info: CarPlayNowPlaying, outputOwned: Boolean, now: Long): MediaSnapshot {
        val item = MediaItem(info.title, info.artist, info.album, info.durationMillis, info.artworkTransferId, info.sourceApp)
        val old = retained
        // Captions can be live lyrics. Only an actual elapsed update resets the monotonic anchor.
        val position = if (old == null || previous?.elapsedMillis != info.elapsedMillis) {
            info.elapsedMillis
        } else old?.positionAt(now)
        return MediaSnapshot(item, info.playing, position, now, outputOwned).also {
            previous = info
            retained = it
        }
    }

    fun clear() { previous = null; retained = null }
}
