package com.shilapi.xcertplay.media

import android.util.Log

/** Five-second video counters that separate network/iPhone gaps from decoder throughput. */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
    private val streamType: Int = 0,
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
    private var received = 0
    private var rendered = 0
    private var recoveries = 0
    private var bytes = 0L
    private var maxArrivalGapNs = 0L
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L

    @Synchronized fun onReceived(size: Int) {
        val now = nanoTime()
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap < IDLE_GAP_NS) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
        lastArrivalNs = now
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            touchSamples++
            touchLatencySumNs += touchLatency
            maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
        }
        received++
        bytes += size
    }

    @Synchronized fun onRendered() { rendered++ }

    @Synchronized fun onRecovery() { recoveries++ }

    @Synchronized fun logIfDue(): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        if (received == 0 && touchSamples == 0) { windowStartNs = now; return null }
        val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
        val kbps = (bytes * 8 / 1000 / seconds).toLong()
        val fps = received / seconds
        // Publish the newest window for the freeze detector. A picture can stop changing while the
        // stream still delivers frames at full rate, and that only shows up as a bitrate collapse -
        // which is exactly the "whole CarPlay screen is stuck" report.
        if (streamType != 0) {
            lastKbps[streamType] = kbps
            lastFps[streamType] = fps
            lastWindowAtMs[streamType] = now / 1_000_000
        }
        val line = ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d recoveries=%d " +
            "touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms").format(
            fps, rendered / seconds, maxArrivalGapNs / 1_000_000,
            kbps, recoveries,
            touchAvgMs, maxTouchLatencyNs / 1_000_000, touchSamples, TouchLatencyProbe.maxSendNs / 1_000_000,
        )
        TouchLatencyProbe.maxSendNs = 0
        Log.i(TAG, line)
        windowStartNs = now
        received = 0; rendered = 0; recoveries = 0; bytes = 0; maxArrivalGapNs = 0
        touchSamples = 0; touchLatencySumNs = 0; maxTouchLatencyNs = 0
        return line
    }

    internal companion object {
        private const val TAG = "DiPlay-VideoStats"
        private const val WINDOW_NS = 5_000_000_000L
        private const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag

        // Newest published window per stream, read by the freeze detector on the UI side.
        private val lastKbps = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        private val lastFps = java.util.concurrent.ConcurrentHashMap<Int, Double>()
        private val lastWindowAtMs = java.util.concurrent.ConcurrentHashMap<Int, Long>()

        /** Latest complete five-second bitrate for [streamType], or -1 when nothing was measured. */
        fun latestKbps(streamType: Int): Long = lastKbps[streamType] ?: -1L

        /** Latest complete five-second frame rate for [streamType], or -1 when nothing was measured. */
        fun latestFps(streamType: Int): Double = lastFps[streamType] ?: -1.0

        /** When that window closed, on the nanoTime clock divided to milliseconds. */
        fun latestWindowAtMs(streamType: Int): Long = lastWindowAtMs[streamType] ?: 0L
    }
}
