package com.freddyq.diplay.diagnostics

import org.junit.Assert.*
import org.junit.Test

class CompactDiagnosticPolicyTest {
    private val policy = CompactDiagnosticPolicy()
    @Test fun routineCountersAreOmitted() {
        for (line in listOf("audio stats writeErrors=0 rx=250", "Video: video stats rx=60fps", "Receive: audio packets=250", "Audio: decoder stats inputDroppedTotal=0", "THEME_DIAGNOSTIC sample source=poll")) {
            assertFalse(line, policy.keep(line))
        }
    }
    @Test fun actualCounterFailuresRemainVisible() {
        assertTrue(policy.keep("audio stats writeErrors=2 rx=0"))
        assertTrue(policy.keep("Audio: decoder stats inputDroppedTotal=3"))
    }
    @Test fun essentialStateAndErrorsRemainVisible() {
        for (line in listOf("CONNECTION_DIAGNOSTIC stage=WirelessActive", "CarPlay audio resource: owner=PHONE", "Android media focus change=-2", "ChanganHUD: state=9", "ClusterBridge: tx2[1] -> true", "Audio: renderer failed stage=decode", "===== CRASH =====")) {
            assertTrue(line, policy.keep(line))
        }
    }
    @Test fun repeatedMetadataAndProtocolChatterAreOmitted() {
        assertFalse(policy.keep("mainAudio unchanged=PHONE; Android focus left unchanged"))
        assertFalse(policy.keep("iPhone playback metadata playing=true"))
        assertFalse(policy.keep("airplay rx GET /info bytes=100"))
        assertFalse(policy.keep("airplay modesChanged detail={resources}"))
    }
    @Test fun protocolFailuresAreRetained() {
        assertTrue(policy.keep("airplay tx status=500 cseq=4"))
        assertFalse(policy.keep("airplay tx status=200 cseq=4"))
    }
    @Test fun quotaHasNoUnboundedHistoryOrQueue() {
        assertEquals(256 * 1024L, policy.maxFileBytes)
        assertEquals(2, policy.archiveCount)
        assertEquals(128, policy.maxQueuedLines)
    }
}
