package com.shilapi.xcertplay.media

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Keeps the head unit's audio clock in step with the iPhone's during music (#610).
 *
 * The iPhone sends music at its own 48 kHz and the head unit plays it at its own. A Humax BHCN-T3103C played
 * 0.76 % fast, so the 300 ms start buffer ran dry about every 70 s and music paused while it refilled. After
 * each write the renderer reports how many frames are queued; a slow average of that level over time, as the
 * track drains it between writes, drives a PI controller that, once a second, trims the track's playback rate
 * so the level stays at its target.
 * The trim is bounded by [MAX_TRIM] (26 cents at most) and settles at the head unit's own drift, which is a
 * hardware constant, so the next music stream starts from the trim this one learned.
 * The level music holds once it plays is not the start threshold. Buffered music (#1031) is fed a second ahead of
 * real time, and realtime music over Wi-Fi comes in bursts, so playback starts partway through one and the rest
 * lands on top (#1068).
 */
internal class MediaClockTrim(
    private val sampleRate: Int,
    private var targetFrames: Long,
    private var integral: Double = learnedTrim,
    private val measuresTarget: Boolean = false,
    private val capacityFrames: Long = 0L,
) {
    private var stressedSinceNs = 0L
    private var lastResetNs = 0L
    private var lastOverflowAtNs = 0L
    private var forceUntilNs = 0L
    var resetCount = 0
        private set

    private class Measuring(val halfStartNs: Long? = null, val earlierHalfMean: Double? = null) {
        var levelFrameNs = 0.0
    }

    private var level: Double? = null
    /** When the level was last sampled. */
    var lastSampleNs = 0L
        private set
    private var lastBuffered = 0L
    private var lastUpdateNs: Long? = null
    private var compressedBacklog = false
    private var measuring: Measuring? = if (measuresTarget) Measuring() else null

    fun measureAgain() {
        if (measuresTarget) measuring = Measuring()
    }

    /** The playback rate last asked of the track, in Hz. */
    var rate = sampleRate
        private set

    /**
     * Include compressed packets waiting for decode: those frames are already queued audio. [arrivedFrameNs] sums,
     * over each frame that arrived since [lastSampleNs], how long it has been queued.
     */
    fun sample(queuedFrames: Long, nowNs: Long, compressedFrames: Long = 0L, headMoving: Boolean = true,
        arrivedFrameNs: Double = 0.0) {
        if (targetFrames <= 0L) return
        // Decoder backpressure is not evidence that the head unit's clock runs at a different
        // rate; do not teach the *next* music stream an artificial clock correction.
        compressedBacklog = compressedFrames > targetFrames
        val buffered = queuedFrames.coerceAtLeast(0L) +
            compressedFrames.coerceIn(0L, sampleRate.toLong() * 16L)
        val elapsedNs = nowNs - lastSampleNs
        // Samples come only after writes, and over Wi-Fi the writes come in bursts 0.4 s apart: the level
        // just after one is its peak. Average what was queued over the time since the last write, while
        // the track drained it, or a 300 ms target reads as overfull and the trim plays fast (#807).
        // A write that finds the track full blocks while more audio arrives; that audio counts from its
        // arrival, or a full track reads up to 300 ms low and the trim wanders for minutes (#1074).
        val meanSinceLastWrite = drainedMean(lastBuffered.toDouble(), rate * elapsedNs / 1e9) +
            if (elapsedNs > 0) arrivedFrameNs / elapsedNs else 0.0
        val measure = measuring
        if (measure != null) {
            if (!headMoving) return
            addToMeasurement(measure, nowNs, elapsedNs, meanSinceLastWrite)
        } else {
            val previous = level
            level = if (previous == null || elapsedNs > STALE_NS) error(buffered.toDouble())
            else previous + (error(meanSinceLastWrite) - previous) * (1.0 - exp(-elapsedNs.toDouble() / AVERAGE_NS))
        }
        lastBuffered = buffered
        lastSampleNs = nowNs
        guardCapacity(queuedFrames.coerceAtLeast(0L), nowNs)
    }

    /** Preserve the car's bounded end-stop protection; an ordinary Wi-Fi burst is not an overflow. */
    private fun guardCapacity(buffered: Long, nowNs: Long) {
        if (capacityFrames <= 0L) return
        val overflow = buffered >= capacityFrames * 0.97
        val underflow = buffered <= capacityFrames * 0.03
        // A measured target can legitimately keep a preloaded track full. Require the mean
        // to corroborate an end-stop fault; decoder backlog and target measurement are not drift.
        if (measuresTarget && (measuring != null || compressedBacklog || level == null ||
                (overflow && level!! <= 0.05) || (underflow && level!! >= -0.05))) {
            stressedSinceNs = 0L
            return
        }
        if (!((overflow && integral < 0.0) || (underflow && integral > 0.0))) {
            stressedSinceNs = 0L
            return
        }
        if (stressedSinceNs == 0L) stressedSinceNs = nowNs
        if (nowNs - stressedSinceNs < 8_000_000_000L ||
            (lastResetNs != 0L && nowNs - lastResetNs < 20_000_000_000L)) return
        integral = if (overflow) MAX_TRIM * 0.5 else -MAX_TRIM * 0.5
        learnedTrim = integral
        lastResetNs = nowNs
        stressedSinceNs = 0L
        forceUntilNs = nowNs + 30_000_000_000L
        lastUpdateNs = null
        resetCount++
    }

    /** Actual lost PCM is stronger evidence than a slow blocking write. */
    fun noteOverflow(nowNs: Long) {
        if (lastOverflowAtNs != 0L && nowNs - lastOverflowAtNs < 5_000_000_000L) return
        lastOverflowAtNs = nowNs
        integral = MAX_TRIM * 0.5
        learnedTrim = integral
        stressedSinceNs = 0L
        forceUntilNs = nowNs + 30_000_000_000L
        lastUpdateNs = null
        resetCount++
    }

    private fun addToMeasurement(measure: Measuring, nowNs: Long, elapsedNs: Long, meanSinceLastWrite: Double) {
        val halfStartNs = measure.halfStartNs
        if (halfStartNs == null) {
            measuring = Measuring(nowNs)
            return
        }
        measure.levelFrameNs += meanSinceLastWrite * elapsedNs
        if (nowNs - halfStartNs < MEASURE_HALF_NS) return
        val halfMean = measure.levelFrameNs / (nowNs - halfStartNs)
        val earlierHalfMean = measure.earlierHalfMean
        if (earlierHalfMean == null || abs(halfMean - earlierHalfMean) > sampleRate * HALVES_AGREE_SECONDS) {
            measuring = Measuring(nowNs, halfMean)
            return
        }
        targetFrames = earlierHalfMean.roundToLong()
        level = 0.0
        measuring = null
    }

    private fun error(frames: Double): Double = (frames - targetFrames) / targetFrames

    /** The mean of a level that starts at [start] and drains [drained] frames at a steady pace, stopping at 0. */
    private fun drainedMean(start: Double, drained: Double): Double = when {
        drained <= 0.0 -> start
        drained <= start -> start - drained / 2
        else -> start * start / (2 * drained)
    }

    /** The playback rate to set now, or null to keep the current one. */
    fun rateDue(nowNs: Long): Int? {
        if (measuring != null) {
            val learned = (sampleRate * (1.0 + integral)).roundToInt()
            if (learned == rate) return null
            rate = learned
            return learned
        }
        val error = level ?: return null
        // Without new audio the level says nothing about the clocks: keep the trim as it is.
        if (nowNs - lastSampleNs > STALE_NS) return null
        // The fixed-target fallback retains the existing small-error hold; measured targets use the upstream PI loop.
        if (!measuresTarget && capacityFrames > 0L && nowNs >= forceUntilNs && abs(error) < 0.05) return null
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
        if (next == rate) return null
        rate = next
        return next
    }

    internal companion object {
        /** At most 1.5 % slower or faster: 26 cents, above the 0.76 % seen on #610. */
        const val MAX_TRIM = 0.015
        const val KP = 0.03
        /** Per second, per unit of level error. */
        const val KI = 0.001
        const val AVERAGE_NS = 5_000_000_000.0
        const val UPDATE_NS = 1_000_000_000L
        /** Longer than the 0.4 s between the iPhone's bursts over Wi-Fi: a longer silence is a pause or a stall. */
        const val STALE_NS = 1_000_000_000L
        const val MAX_STEP_SECONDS = 2.0
        const val MEASURE_HALF_NS = 1_500_000_000L
        const val HALVES_AGREE_SECONDS = 0.2

        /** The drift the last music stream settled on. Each process plays to one head unit. */
        @Volatile var learnedTrim = 0.0
    }
}
