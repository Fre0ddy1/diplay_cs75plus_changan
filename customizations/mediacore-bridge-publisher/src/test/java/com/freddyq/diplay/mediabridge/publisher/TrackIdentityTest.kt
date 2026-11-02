package com.freddyq.diplay.mediabridge.publisher

import com.freddyq.diplay.customization.MediaItem
import org.junit.Assert.*
import org.junit.Test

class TrackIdentityTest {
    @Test fun lyricCaptionsDoNotChangeArtworkIdentityWhenPhoneProvidesId() {
        val item = MediaItem("第一句歌词", "歌手", "专辑", 180000, 3)
        assertEquals(MediaCorePublisher.trackKey(item), MediaCorePublisher.trackKey(item.copy(title = "第二句歌词")))
    }
    @Test fun noArtworkIdFallsBackToFullMetadata() {
        val item = MediaItem("曲名", "歌手", "专辑", null, null)
        assertNotEquals(MediaCorePublisher.trackKey(item), MediaCorePublisher.trackKey(item.copy(artist = "另一歌手")))
    }
}
