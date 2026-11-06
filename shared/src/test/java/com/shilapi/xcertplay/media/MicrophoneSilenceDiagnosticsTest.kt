package com.shilapi.xcertplay.media
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class MicrophoneSilenceDiagnosticsTest {
    private val config = MicrophoneConfig("telephony", 48_000, 1, 100, 20,
        InetAddress.getLoopbackAddress(), 1234, ByteArray(32), AudioCodecKind.OPUS)
    @Test fun contextIsQueriedOnlyForDueSilentWindowsAndFailuresCannotBreakCapture() {
        var now = 0L; var queries = 0; val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { now }
        stats.useSource(6)
        val context = { queries++; "audioMode=3 otherRecordings=MIC" }
        stats.samples(ByteArray(20), 20); stats.flush(silence = context)
        assertEquals(0, queries)
        now = 5_000_000_000; stats.flush(silence = context)
        assertEquals(1, queries); assertTrue(reports.last().contains("source=VOICE_RECOGNITION"))
        assertTrue(reports.last().contains("audioMode=3"))
        stats.samples(byteArrayOf(0, -128), 2)
        now += 5_000_000_000; stats.flush(silence = context)
        assertEquals(1, queries); assertTrue(reports.last().contains("peakSample=32768"))
        now += 5_000_000_000; stats.flush(silence = { throw SecurityException("denied") })
        assertTrue(reports.last().endsWith("ended=false"))
    }
    @Test fun partialAndOversizedReadsNeverReadBeyondTheBuffer() {
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add)
        stats.samples(byteArrayOf(1), 999)
        stats.samples(byteArrayOf(-1, -1, 3), 999)
        stats.flush(ended = true)
        assertTrue(reports.last().contains("peakSample=1"))
    }
}
