package com.freddyq.diplay.mediabridge.contract

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaFrameTest {
    @Test fun unitsAndOptionalRealAudioSessionRoundTrip() {
        val f = MediaFrame("a", "曲目", "歌手", "专辑", true, true, true, 1320, 181000, 5729)
        assertEquals(f, MediaFrame.fromBundle(f.toBundle()))
    }
    @Test fun missingAudioSessionRemainsUnknown() { assertNull(MediaFrame.fromBundle(MediaFrame("a").toBundle()).audioSessionId) }
    @Test fun rejectsSchemaVersion() { rejected { MediaFrame.fromBundle(MediaFrame("a").toBundle().apply { putInt("version", 99) }) } }
    @Test fun rejectsSourceSpoofing() { rejected { MediaFrame.fromBundle(MediaFrame("a").toBundle().apply { putInt("provider", 3) }) } }
    @Test fun rejectsWrongPrimitiveType() { rejected { MediaFrame.fromBundle(MediaFrame("a").toBundle().apply { putString("positionMs", "1") }) } }
    @Test fun rejectsMissingPlaying() { rejected { MediaFrame.fromBundle(MediaFrame("a").toBundle().apply { remove("playing") }) } }
    @Test fun rejectsUnboundedText() { rejected { MediaFrame("a", title = "x".repeat(257)) } }
    @Test fun rejectsNegativeAndBeyondDurationPositions() {
        rejected { MediaFrame("a", positionMs = -1) }; rejected { MediaFrame("a", positionMs = 1001, durationMs = 1000) }
    }
    @Test fun rejectsFakeZeroAudioSession() { rejected { MediaFrame("a", audioSessionId = 0) } }
    @Test fun rejectsUnknownCoverOperation() { rejected { MediaFrame("a", cover = "http") } }
    private fun rejected(block: () -> Unit) { try { block(); fail("Should reject") } catch (_: IllegalArgumentException) { } }
}
