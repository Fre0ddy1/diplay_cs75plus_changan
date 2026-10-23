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
    /**
     * Default jitter buffer for music, in milliseconds.
     *
     * 1000, not 500. The field comparison on the UNI-V is unambiguous: 500 ms recorded
     * `maxGapMs` of 500-760 ms (occasionally ~800), repeated underruns and rebuffers, while
     * 1000 ms is stable. The cost is that sound sits about a second behind the picture; the
     * presets stay available for a driver who prefers tighter lyrics (150/300/500) or a worse
     * link (1500).
     */
    const val DEFAULT_MILLIS = 1000
    /**
     * Selectable music buffers, smallest first. 150 ms is there for a driver who wants the lyrics
     * tight like 0.2.13 (which had no buffer at all) and accepts the extra stutter risk; 1500 ms is
     * for a link worse than the one we measured (~400 ms worst-case arrival gap).
     */
    val presets = listOf(150, 300, 500, 1000, 1500)
    // 500 used to be the default after a highway drive; repeated field logs on this head unit
    // showed 400-760 ms arrival gaps and rebuffers at 500, so 1000 is the default now. 500/300
    // remain for a genuinely clean link; raising the buffer is the fix for stutter, not lowering it.

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
