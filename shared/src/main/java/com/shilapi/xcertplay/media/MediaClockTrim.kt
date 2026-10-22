package com.shilapi.xcertplay.media

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Keeps the head unit's audio clock in step with the iPhone's during music (#610).
 *
 * The iPhone sends music at its own 48 kHz and the head unit plays it at its own. A Humax BHCN-T3103C played
 * 0.76 % fast, so the 300 ms start buffer ran dry about every 70 s and music paused while it refilled. After
 * each write the renderer reports how many frames are queued; a slow average of that level drives a PI
 * controller that trims the track's playback rate so the average level stays near the start threshold.
 * The trim is bounded by [MAX_TRIM] (26 cents at most) and settles at the head unit's own drift, which is a
 * hardware constant, so the next music stream starts from the trim this one learned.
 *
 * The controller deliberately ignores short excursions. Wireless CarPlay delivers audio in bursts, and a
 * 400-700 ms gap swings a 1 s buffer by tens of percent; treating that as clock drift made the rate hunt
 * between 47293 Hz and 48203 Hz and was audible as rushing and pitch wobble (field 2026-10-22). Only a
 * sustained level outside [DEADBAND] moves the rate, and the learned offset is dropped only when the track
 * has actually been pinned at its capacity (or empty) for [RESET_HOLD_NS] - never on a normal burst.
 */
internal class MediaClockTrim(
    private val sampleRate: Int,
    private val targetFrames: Long,
    private var integral: Double = learnedTrim,
    /** AudioTrack capacity in frames; 0 (tests, unknown) disables the overflow/underflow guard. */
    private val capacityFrames: Long = 0L,
) {
    private var level: Double? = null
    private var lastSampleNs = 0L
    private var lastUpdateNs: Long? = null
    private var compressedBacklog = false
    private var stressedSinceNs = 0L
    private var lastResetNs = 0L
    private var forceUntilNs = 0L

    /** How many times a stale learned offset was dropped, for the diagnostic report. */
    var resetCount = 0
        private set

    /** The playback rate last asked of the track, in Hz; starts from the drift last stream learned. */
    var rate = (sampleRate * (1.0 + integral.coerceIn(-MAX_TRIM, MAX_TRIM))).roundToInt()
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
        val nextLevel = if (previous == null || nowNs - lastSampleNs > STALE_NS) error
        else previous + (error - previous) * (1.0 - exp(-(nowNs - lastSampleNs).toDouble() / AVERAGE_NS))
        level = nextLevel
        lastSampleNs = nowNs

        // A stale learned offset is only proven by a *real* overflow or underrun, not by the normal
        // level ripple of a Wi-Fi burst. The earlier 2 % test fired 76 times in one drive, reset
        // the learn every few seconds and made the rate hunt. Require the track itself to be
        // pinned at its capacity (or empty) for several seconds first.
        val overflow = capacityFrames > 0L && buffered >= capacityFrames * OVERFLOW_FRACTION
        val underflow = capacityFrames > 0L && buffered <= capacityFrames * UNDERFLOW_FRACTION
        val stressed = (overflow && integral < 0.0) || (underflow && integral > 0.0)
        if (!stressed) {
            stressedSinceNs = 0L
            return
        }
        if (stressedSinceNs == 0L) stressedSinceNs = nowNs
        if (nowNs - stressedSinceNs < RESET_HOLD_NS) return
        if (lastResetNs != 0L && nowNs - lastResetNs < RESET_COOLDOWN_NS) return
        // The offset cannot be right while the track is pinned at an end stop: drop it and let the
        // proportional term pull the level back into the band. Keep correcting for a short while
        // even if the averaged level returns to the band, otherwise the deadband would freeze the
        // wrong rate again before the slew-limited correction has moved more than a step.
        integral = 0.0
        learnedTrim = 0.0
        lastResetNs = nowNs
        stressedSinceNs = 0L
        forceUntilNs = nowNs + FORCE_NS
        lastUpdateNs = null
        resetCount++
    }

    /** The playback rate to set now, or null to keep the current one. */
    fun rateDue(nowNs: Long): Int? {
        val error = level ?: return null
        // Without new audio the level says nothing about the clocks: keep the trim as it is.
        if (nowNs - lastSampleNs > STALE_NS) return null
        // A level this close to the target is normal burst ripple. Holding the rate here is what
        // stops a slow drift from being corrected with a stream of audible resampler changes.
        // The held rate is the best estimate of the drift, so keep the learn in sync with it.
        if (nowNs >= forceUntilNs && abs(error) < DEADBAND) {
            if (!compressedBacklog) {
                integral = (rate.toDouble() / sampleRate - 1.0).coerceIn(-MAX_TRIM, MAX_TRIM)
                learnedTrim = integral
            }
            return null
        }
        val previousUpdate = lastUpdateNs
        if (previousUpdate != null && nowNs - previousUpdate < UPDATE_NS) return null
        // A pause between updates is not drift: integrate at most one normal step over it.
        val seconds = if (previousUpdate == null) 0.0 else ((nowNs - previousUpdate) / 1e9).coerceAtMost(MAX_STEP_SECONDS)
        lastUpdateNs = nowNs
        if (!compressedBacklog) {
            // Conditional integration: do not wind the integral further into a saturated limit.
            // A long Wi-Fi gap used to park it at -1.5 % for minutes; this stops that at the limit.
            val p = KP * error
            val saturatedLow = p + integral <= -MAX_TRIM && error < 0.0
            val saturatedHigh = p + integral >= MAX_TRIM && error > 0.0
            if (!saturatedLow && !saturatedHigh) {
                integral = (integral + KI * error * seconds).coerceIn(-MAX_TRIM, MAX_TRIM)
            }
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
        if (abs(limited - rate) < minChange) return null
        rate = limited
        return limited
    }

    internal companion object {
        /** At most 1.5 % slower or faster: 26 cents, above the 0.76 % seen on #610. */
        const val MAX_TRIM = 0.015
        /**
         * Largest playback-rate change per update (0.2 %). The trim still reaches its target, just
         * over several seconds, so a large correction is heard as drift rather than as a slide.
         */
        const val MAX_RATE_STEP = 0.002
        /**
         * Smallest change worth a setPlaybackRate call (0.1 %).
         *
         * The resampler is rebuilt on every call, which is audible. Bigger steps mean fewer
         * rebuilds while still converging in a few updates.
         */
        const val MIN_RATE_CHANGE = 0.001
        const val KP = 0.05
        /** Per second, per unit of level error. */
        const val KI = 0.001
        /**
         * Time constant of the level average.
         *
         * Long enough to average a 400-700 ms Wi-Fi gap (a 0.7 s gap is under 5 % of a 15 s window),
         * short enough to see a real clock drift within a few tens of seconds.
         */
        const val AVERAGE_NS = 15_000_000_000.0
        /**
         * How often the rate may change at all.
         *
         * Six seconds, with 0.2 % steps, keeps a correction gradual and bounds resampler rebuilds
         * to a few per minute at most.
         */
        const val UPDATE_NS = 6_000_000_000L
        const val STALE_NS = 250_000_000L
        const val MAX_STEP_SECONDS = 2.0
        /**
         * Level error ignored by the controller, as a fraction of the target.
         *
         * 5 % of a 1 s buffer is 50 ms, comfortably above the smoothed effect of one Wi-Fi gap and
         * below the point where a real clock drift needs attention.
         */
        const val DEADBAND = 0.05
        /** A track at this fraction of capacity (or above) has started dropping audio. */
        const val OVERFLOW_FRACTION = 0.97
        /** A track at this fraction of capacity (or below) is effectively empty. */
        const val UNDERFLOW_FRACTION = 0.03
        /** How long the track must remain pinned at an end stop before the learn is dropped. */
        const val RESET_HOLD_NS = 8_000_000_000L
        /** Do not reset again immediately: one correction is enough to start draining/refilling. */
        const val RESET_COOLDOWN_NS = 20_000_000_000L
        /** Keep correcting for this long after a reset even if the averaged level looks normal. */
        const val FORCE_NS = 30_000_000_000L

        /** The drift the last music stream settled on. Each process plays to one head unit. */
        @Volatile var learnedTrim = 0.0
    }
}
