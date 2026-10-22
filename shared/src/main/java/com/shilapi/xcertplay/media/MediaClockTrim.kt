package com.shilapi.xcertplay.media

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Keeps the head unit's audio clock in step with the iPhone's during music (#610).
 *
 * The iPhone sends music at its own 48 kHz and the head unit plays it at its own. A Humax BHCN-T3103C played
 * 0.76 % fast, so the 300 ms start buffer ran dry about every 70 s and music paused while it refilled. After
 * each write the renderer reports how many frames are queued; a slow average of that level drives a PI
 * controller that, once a second, trims the track's playback rate so the level stays at the start threshold.
 * The trim is bounded by [MAX_TRIM] (26 cents at most) and settles at the head unit's own drift, which is a
 * hardware constant, so the next music stream starts from the trim this one learned.
 */
internal class MediaClockTrim(
    private val sampleRate: Int,
    private val targetFrames: Long,
    private var integral: Double = learnedTrim,
) {
    private var level: Double? = null
    private var lastSampleNs = 0L
    private var lastUpdateNs: Long? = null
    private var compressedBacklog = false

    /** The playback rate last asked of the track, in Hz. */
    var rate = sampleRate
        private set

    /** Include compressed packets waiting for decode: those frames are already queued audio. */
    fun sample(queuedFrames: Long, nowNs: Long, compressedFrames: Long = 0L) {
        if (targetFrames <= 0L) return
        // Decoder backpressure is not evidence that the head unit's clock runs at a different
        // rate; do not teach the *next* music stream an artificial clock correction.
        compressedBacklog = compressedFrames > targetFrames
        val buffered = queuedFrames.coerceAtLeast(0L) +
            compressedFrames.coerceIn(0L, sampleRate.toLong() * 16L)
        val error = (buffered - targetFrames).toDouble() / targetFrames
        val previous = level
        // After a pause, a rebuffer or the first write the average restarts from what is queued now.
        level = if (previous == null || nowNs - lastSampleNs > STALE_NS) error
        else previous + (error - previous) * (1.0 - exp(-(nowNs - lastSampleNs).toDouble() / AVERAGE_NS))
        lastSampleNs = nowNs
    }

    /** The playback rate to set now, or null to keep the current one. */
    fun rateDue(nowNs: Long): Int? {
        val error = level ?: return null
        // Without new audio the level says nothing about the clocks: keep the trim as it is.
        if (nowNs - lastSampleNs > STALE_NS) return null
        val previousUpdate = lastUpdateNs
        if (previousUpdate != null && nowNs - previousUpdate < UPDATE_NS) return null
        // A pause between updates is not drift: integrate at most one normal step over it.
        val seconds = if (previousUpdate == null) 0.0 else ((nowNs - previousUpdate) / 1e9).coerceAtMost(MAX_STEP_SECONDS)
        lastUpdateNs = nowNs
        if (!compressedBacklog) {
            integral = (integral + KI * error * seconds).coerceIn(-MAX_TRIM, MAX_TRIM)
            learnedTrim = integral
        }
        val next = (sampleRate * (1.0 + (KP * error + integral).coerceIn(-MAX_TRIM, MAX_TRIM))).roundToInt()
        // Slew-limit instead of jumping to the target. setPlaybackRate changes the resampling
        // ratio, so it changes pitch as well as speed: a 1.5 % correction at once is heard as a
        // slide. On the first stream of a process the integral has not learned the head unit's
        // drift yet, so the target can swing the whole range within a couple of updates (field
        // 2026-10-21: "the first track slides in pitch, later ones are fine"). Moving at most
        // MAX_RATE_STEP per update turns that into a slow, inaudible drift.
        val maxDelta = (sampleRate * MAX_RATE_STEP).roundToInt().coerceAtLeast(1)
        val limited = rate + (next - rate).coerceIn(-maxDelta, maxDelta)
        if (limited == rate) return null
        // Every setPlaybackRate rebuilds the resampler, and rebuilding it mid-stream is audible as a
        // click. Never ask for a change too small to matter: the field log for 2026-10-22 shows the
        // rate moving once a second by a few hundredths of a percent, which is nothing but a stream
        // of clicks ("隐约的炸炸声").
        val minChange = (sampleRate * MIN_RATE_CHANGE).roundToInt().coerceAtLeast(1)
        if (kotlin.math.abs(limited - rate) < minChange) return null
        rate = limited
        return limited
    }

    internal companion object {
        /** At most 1.5 % slower or faster: 26 cents, above the 0.76 % seen on #610. */
        const val MAX_TRIM = 0.015
        /**
         * Largest playback-rate change per update (0.3 %). The trim still reaches its target, just
         * over several seconds, so a large correction is heard as drift rather than as a slide.
         */
        const val MAX_RATE_STEP = 0.003
        /**
         * Smallest change worth a setPlaybackRate call (0.05 %).
         *
         * The resampler is rebuilt on every call, which is audible. With the old one-second update
         * period the controller asked for a few hundredths of a percent at a time, so it called
         * setPlaybackRate continuously for changes nobody could hear - and the rebuilds themselves
         * were the noise.
         */
        const val MIN_RATE_CHANGE = 0.0005
        const val KP = 0.03
        /** Per second, per unit of level error. */
        const val KI = 0.001
        const val AVERAGE_NS = 5_000_000_000.0
        /**
         * How often the rate may change at all.
         *
         * One second was too eager: at that period a slow drift produces a constant trickle of tiny
         * resampler rebuilds. Three seconds still corrects a real drift long before the buffer
         * drains (the trim only ever needs to move by the head unit's own ~1 %), while cutting the
         * number of rebuilds to a third.
         */
        const val UPDATE_NS = 3_000_000_000L
        const val STALE_NS = 250_000_000L
        const val MAX_STEP_SECONDS = 2.0

        /** The drift the last music stream settled on. Each process plays to one head unit. */
        @Volatile var learnedTrim = 0.0
    }
}
