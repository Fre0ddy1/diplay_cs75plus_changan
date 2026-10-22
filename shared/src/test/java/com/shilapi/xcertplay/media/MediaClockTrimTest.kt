package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClockTrimTest {
    @Test
    fun `a track pinned at capacity drops a stale slow trim`() {
        // 57 600 frames is the 1.2 s track used with the 1000 ms music buffer.
        val trim = MediaClockTrim(
            sampleRate = 48_000, targetFrames = 48_000, integral = -0.015, capacityFrames = 57_600,
        )

        trim.sample(queuedFrames = 57_000, nowNs = 1_000_000_000)
        assertEquals("one burst must not reset", 0, trim.resetCount)

        trim.sample(queuedFrames = 57_000, nowNs = 10_000_000_000)
        assertEquals(1, trim.resetCount)
        val before = trim.rate
        val rate = trim.rateDue(nowNs = 10_000_000_000)
        assertNotNull(rate)
        assertTrue("expected a speed-up from $before, got $rate", rate!! > before)
    }

    @Test
    fun `an empty track drops a stale fast trim`() {
        val trim = MediaClockTrim(
            sampleRate = 48_000, targetFrames = 48_000, integral = 0.015, capacityFrames = 57_600,
        )

        trim.sample(queuedFrames = 1_000, nowNs = 1_000_000_000)
        trim.sample(queuedFrames = 1_000, nowNs = 10_000_000_000)

        assertEquals(1, trim.resetCount)
        val before = trim.rate
        val rate = trim.rateDue(nowNs = 10_000_000_000)
        assertNotNull(rate)
        assertTrue("expected a slow-down from $before, got $rate", rate!! < before)
    }

    @Test
    fun `a level burst below capacity does not reset the learn`() {
        val trim = MediaClockTrim(
            sampleRate = 48_000, targetFrames = 48_000, integral = -0.005, capacityFrames = 57_600,
        )

        // 4 % over target but only 87 % of capacity: exactly the post-gap burst that used to
        // trigger the old 2 % reset every few seconds.
        trim.sample(queuedFrames = 50_000, nowNs = 1_000_000_000)
        trim.sample(queuedFrames = 50_000, nowNs = 61_000_000_000)

        assertEquals(0, trim.resetCount)
    }

    @Test
    fun `a small level error holds the rate`() {
        val trim = MediaClockTrim(
            sampleRate = 48_000, targetFrames = 48_000, integral = -0.005, capacityFrames = 57_600,
        )

        trim.sample(queuedFrames = 49_000, nowNs = 1_000_000_000)

        assertNull(trim.rateDue(nowNs = 1_000_000_000))
    }

    @Test
    fun `a sustained level error moves the rate`() {
        val trim = MediaClockTrim(
            sampleRate = 48_000, targetFrames = 48_000, integral = 0.0, capacityFrames = 57_600,
        )

        trim.sample(queuedFrames = 44_000, nowNs = 1_000_000_000)

        val rate = trim.rateDue(nowNs = 1_000_000_000)
        assertNotNull(rate)
        assertTrue("expected a slow-down, got $rate", rate!! < 48_000)
    }
}
