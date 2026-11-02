package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoDecoderRetryTest {
    @Test fun successfulFirstRoundNeverWaitsOrRetries() {
        var attempts=0
        assertEquals("codec",configureDecoderWithRetry({true},{attempts++; "codec"},{fail("must not wait")}))
        assertEquals(1,attempts)
    }
    @Test fun exhaustedFirstRoundSettlesOnceBeforeTheSecondRound() {
        val events=mutableListOf<String>(); var attempts=0
        assertEquals("codec",configureDecoderWithRetry({true},{events+="attempt"; if (++attempts==2) "codec" else null},{events+="settle"}))
        assertEquals(listOf("attempt","settle","attempt"),events)
    }
    @Test fun permanentFailureIsBoundedToTwoRounds() {
        var attempts=0; var waits=0
        assertNull(configureDecoderWithRetry<Any>({true},{attempts++;null},{waits++}))
        assertEquals(2,attempts); assertEquals(1,waits)
    }
    @Test fun closingDuringSettleCancelsTheSecondRound() {
        var running=true; var attempts=0
        assertNull(configureDecoderWithRetry<Any>({running},{attempts++;null},{running=false}))
        assertEquals(1,attempts)
    }
    @Test fun interruptPreservesCancellationAndDoesNotRetry() {
        var attempts=0
        try {
            assertNull(configureDecoderWithRetry<Any>({true},{attempts++;null},{throw InterruptedException()}))
            assertTrue(Thread.currentThread().isInterrupted); assertEquals(1,attempts)
        } finally { Thread.interrupted() }
    }
}
