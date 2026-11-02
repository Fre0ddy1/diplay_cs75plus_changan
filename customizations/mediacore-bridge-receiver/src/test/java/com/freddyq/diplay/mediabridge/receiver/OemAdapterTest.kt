package com.freddyq.diplay.mediabridge.receiver

import com.freddyq.diplay.mediabridge.contract.BridgeReceipt
import com.freddyq.diplay.mediabridge.contract.MediaFrame
import org.junit.Assert.*
import org.junit.Test

class OemAdapterTest {
    // Authored fixtures with the verified method signatures, not copied OEM SDK classes.
    class Model {
        var id = ""; var mediaName = ""; var title = ""; var mediaAuthor = ""; var mediaGroupName = ""
        var mediaType = ""; var mediaImage = ""; var duration = 0L; var progress = 0L
    }
    enum class State { UNKNOWN, PLAY, PAUSE, STOP }
    @Test fun mapsRealSetterSignaturesAndKeepsSessionAndProviderDistinct() {
        var update: NativeProviderUpdate? = null
        val adapter = OemProviderAdapter({ update = it }, Model::class.java, State::class.java)
        val f = MediaFrame("a", "曲名", "歌手", "专辑", true, true, true, 3500, 180000, 5729)
        adapter.stage(PublishedMedia(f, "content://test/cover"))
        val u = update!!; val model = u.mediaInfo as Model
        assertEquals(12, u.provider); assertEquals(5729, u.audioSessionId)
        assertEquals("曲名", model.mediaName); assertEquals("专辑", model.mediaGroupName)
        assertEquals(3500, model.progress); assertEquals(180000, model.duration)
        assertEquals("song", model.mediaType); assertEquals(State.PLAY, u.playState)
        assertEquals(BridgeReceipt.OEM_PROVIDER_STAGED, adapter.stage)
    }
    @Test fun pauseMapsPauseAndDisconnectMapsStopWithoutLosingPauseImage() {
        var update: NativeProviderUpdate? = null
        val adapter = OemProviderAdapter({ update = it }, Model::class.java, State::class.java)
        adapter.stage(PublishedMedia(MediaFrame("a", playing = false), "content://test/cover"))
        assertEquals(State.PAUSE, update!!.playState)
        assertEquals("content://test/cover", (update!!.mediaInfo as Model).mediaImage)
        adapter.stage(null); assertEquals(State.STOP, update!!.playState); assertNull(update!!.mediaInfo)
    }
    @Test fun carplayNeedsRealOutputAndSelectionWhileExistingProvidersKeepGate() {
        fun gate(id: Int, owned: Boolean, selected: Boolean) = SceneCompatibility.musicEligible(id, true, "song", true, false, owned, selected)
        assertTrue(gate(12, true, true)); assertFalse(gate(12, false, true)); assertFalse(gate(12, true, false))
        for (id in 1..4) assertTrue(gate(id, false, false))
        assertFalse(gate(14, true, true)); assertFalse(gate(0, true, true))
    }
    @Test fun disabledLinkageSuppressionAndPauseRemainEffective() {
        assertFalse(SceneCompatibility.musicEligible(12, false, "song", true, false, true, true))
        assertFalse(SceneCompatibility.musicEligible(12, true, "song", false, false, true, true))
        assertFalse(SceneCompatibility.musicEligible(12, true, "song", true, true, true, true))
        assertFalse(SceneCompatibility.musicEligible(12, true, "podcast", true, false, true, true))
    }
    @Test fun progressOnlyUpdateDoesNotInvalidateMetadataOrCoverObservers() {
        var update: NativeProviderUpdate? = null
        val adapter = OemProviderAdapter({ update = it }, Model::class.java, State::class.java)
        val media = PublishedMedia(MediaFrame("a", playing = true, positionMs = 1000, cover = MediaFrame.REPLACE), "content://test/cover")
        adapter.stage(media)
        adapter.stage(media.copy(frame = media.frame.copy(positionMs = 2000, cover = MediaFrame.KEEP)))
        val c = update!!.changes
        assertTrue(c.progress); assertFalse(c.media); assertFalse(c.cover); assertFalse(c.playback); assertFalse(c.source)
        adapter.stage(media.copy(frame = media.frame.copy(playing = false, positionMs = 2000, cover = MediaFrame.KEEP)))
        assertTrue(update!!.changes.playback); assertFalse(update!!.changes.cover); assertFalse(update!!.changes.media)
    }
}
