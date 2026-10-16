package com.shilapi.xcertplay.media

/**
 * Jitter buffer for streams mapped to the media channel. Wireless CarPlay delivers audio over the same Wi-Fi link as
 * video; radio gaps of several hundred milliseconds are normal, so music needs a buffer that
 * outlasts them. Calls, Siri and navigation prompts keep the small low-latency buffer.
 */
object MediaAudioBuffer {
    // Wi-Fi Direct on head units shows radio gaps of several hundred ms routinely and ~1.1 s
    // occasionally (measured maxGapMs=1114 with a 19:40 underrun). 300 ms + 200 ms headroom
    // was too shallow and rebuffered; 1000 ms + 200 ms outlasts the measured gaps.
    const val DEFAULT_MILLIS = 1000
    val presets = listOf(300, DEFAULT_MILLIS, 1500)

    /**
     * Jitter buffer for short spoken prompts (navigation guidance, assistant).
     *
     * These used to take the low-latency plan, and the field log shows why that is wrong: the nav
     * channel's track holds only 342 ms (trackBufferFrames=16416 at 48 kHz) while radio arrival
     * gaps reach 208 ms, and the same session recorded underruns=+2 - one Wi-Fi gap drains the
     * prompt and the driver hears it stutter. 400 ms covers the measured gaps; it adds nothing
     * perceptible to a spoken prompt.
     *
     * Calls deliberately do NOT use this: a conversation needs the small low-latency buffer, so
     * [plan] keeps returning that when neither the media nor the prompt tier applies.
     */
    const val PROMPT_MILLIS = 400

    private const val HEADROOM_MILLIS = 200 // room above the start level so bursts after a gap fit
    private const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
    private const val MIN_START_BUFFER_BYTES = 4 * 1024

    fun sanitize(millis: Int): Int = millis.takeIf { it in presets } ?: DEFAULT_MILLIS

    data class Plan(val trackBufferBytes: Int, val startBytes: Int)

    /** AudioTrack capacity and the amount to queue before play() for one output stream. */
    fun plan(
        isMedia: Boolean,
        sampleRate: Int,
        channels: Int,
        minBufferBytes: Int,
        mediaMillis: Int,
        /** True for spoken prompts (navigation, assistant); false for calls, which need low latency. */
        prompt: Boolean = false,
    ): Plan {
        val lowLatency = Plan(
            trackBufferBytes = maxOf(minBufferBytes * 4, MIN_TRACK_BUFFER_BYTES),
            startBytes = maxOf(minBufferBytes, MIN_START_BUFFER_BYTES),
        )
        if (!isMedia && !prompt) return lowLatency
        val millis = if (isMedia) sanitize(mediaMillis) else PROMPT_MILLIS
        val bytesPerSecond = sampleRate.toLong() * channels.coerceIn(1, 2) * 2
        val start = (bytesPerSecond * millis / 1000).toInt()
        val capacity = (bytesPerSecond * (millis + HEADROOM_MILLIS) / 1000).toInt()
        return Plan(
            trackBufferBytes = maxOf(capacity, lowLatency.trackBufferBytes),
            startBytes = maxOf(start, lowLatency.startBytes),
        )
    }

    /**
     * The device may grant a smaller AudioTrack than requested. Writes block while the track is
     * paused and full, so the start level must stay below the real capacity or play() never runs.
     */
    fun startBytesFor(plannedStartBytes: Int, actualCapacityBytes: Int, writeChunkBytes: Int): Int {
        if (actualCapacityBytes <= 0) return plannedStartBytes
        return minOf(plannedStartBytes, actualCapacityBytes - writeChunkBytes).coerceAtLeast(writeChunkBytes)
    }
}
