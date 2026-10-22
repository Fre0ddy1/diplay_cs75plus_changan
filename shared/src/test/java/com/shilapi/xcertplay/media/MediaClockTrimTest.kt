package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClockTrimTest {
    @Test
    fun `a full buffer drops a stale slow trim and speeds up`() {
        // The field state: integral pinned at the -1.5 % limit while the track sat full.
        val trim = MediaClockTrim(sampleRate = 48_000, targetFrames = 48_000, integral = -0.015)

        trim.sample(queuedFrames = 50_000, nowNs = 0)

        assertEquals(1, trim.resetCount)
        val rate = trim.rateDue(nowNs = 0)
        assertNotNull(rate)
        assertTrue("expected a speed-up, got $rate", rate!! > 48_000)
    }

    @Test
    fun `an empty buffer drops a stale fast trim and slows down`() {
        val trim = MediaClockTrim(sampleRate = 48_000, targetFrames = 48_000, integral = 0.015)

        trim.sample(queuedFrames = 46_000, nowNs = 0)

        assertEquals(1, trim.resetCount)
        val rate = trim.rateDue(nowNs = 0)
        assertNotNull(rate)
        assertTrue("expected a slow-down, got $rate", rate!! < 48_000)
    }

    @Test
    fun `a small level error keeps the learned trim`() {
        val trim = MediaClockTrim(sampleRate = 48_000, targetFrames = 48_000, integral = -0.005)

        trim.sample(queuedFrames = 48_500, nowNs = 0)

        assertEquals(0, trim.resetCount)
        val rate = trim.rateDue(nowNs = 0)
        assertNotNull(rate)
        assertTrue("expected the learned slow trim, got $rate", rate!! < 48_000)
    }
}
