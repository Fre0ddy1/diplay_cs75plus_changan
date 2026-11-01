package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.*
import org.junit.Test

class PhoneMediaSnapshotAdapterTest {
    @Test fun liveTitleUpdatesDoNotRewindTheRealProgressAnchor() {
        val adapter = PhoneMediaSnapshotAdapter()
        val first = CarPlayNowPlaying(title = "歌词甲", artist = "曲名", album = "歌手", sourceApp = "QQ音乐",
            durationMillis = 225000, elapsedMillis = 1000, playing = true)
        adapter.update(first, true, 1000)
        val second = adapter.update(first.copy(title = "歌词乙"), true, 4000)
        assertEquals(4000L, second.positionMillis)
        assertEquals("QQ音乐", second.item.sourceApp)
    }
    @Test fun pauseAndResumeKeepElapsedTimeWithoutCountingPausedWallTime() {
        val adapter = PhoneMediaSnapshotAdapter()
        val playing = CarPlayNowPlaying(title = "QQ", durationMillis = 60000, elapsedMillis = 1000, playing = true)
        adapter.update(playing, true, 10000)
        val paused = adapter.update(playing.copy(playing = false), false, 13000)
        assertEquals(4000L, paused.positionAt(50000))
        val resumed = adapter.update(playing, true, 50000)
        assertEquals(6000L, resumed.positionAt(52000))
    }
    @Test fun authoritativeSeekAndNewTrackResetThePositionAnchor() {
        val adapter = PhoneMediaSnapshotAdapter()
        val info = CarPlayNowPlaying(title = "A", durationMillis = 60000, elapsedMillis = 20000, playing = true)
        adapter.update(info, true, 1000)
        assertEquals(3000L, adapter.update(info.copy(elapsedMillis = 3000), true, 3000).positionAt(3000))
        assertEquals(0L, adapter.update(info.copy(title = "B", elapsedMillis = 0), true, 4000).positionAt(4000))
    }
    @Test fun clearingPhoneSessionCannotReuseAnEarlierPositionOrArtworkId() {
        val adapter = PhoneMediaSnapshotAdapter()
        adapter.update(CarPlayNowPlaying(title = "Old", artworkTransferId = 3, elapsedMillis = 5000), true, 1000)
        adapter.clear()
        val value = adapter.update(CarPlayNowPlaying(), false, 2000)
        assertNull(value.positionMillis); assertNull(value.item.artworkId); assertNull(value.item.title)
    }
    @Test fun lateArtworkOrDurationDoesNotRewindAPlayingTrack() {
        val adapter = PhoneMediaSnapshotAdapter()
        val info = CarPlayNowPlaying(title = "A", elapsedMillis = 1000, playing = true)
        adapter.update(info, false, 1000)
        val result = adapter.update(info.copy(artworkTransferId = 4, durationMillis = 60000), false, 4000)
        assertEquals(4000L, result.positionMillis)
    }
}
