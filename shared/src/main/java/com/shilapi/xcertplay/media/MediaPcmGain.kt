package com.shilapi.xcertplay.media

/** One media attenuation stage. A gain change ramps over one PCM16 buffer, equally per channel. */
internal class MediaPcmGain(private val channels: Int) {
    private var previous = 1f

    fun apply(data: ByteArray, offset: Int, length: Int, gain: Float) {
        val target = gain.coerceIn(0f, 1f)
        val frameBytes = channels * 2
        val frames = length / frameBytes
        if (frames <= 0) return
        val from = previous
        previous = target
        if (target == 1f && from == 1f) return
        // Resource denial is an immediate mute, never a fade leaking a previous owner's audio.
        for (frame in 0 until frames) {
            val applied = if (target == 0f) 0f else from + (target - from) * (frame + 1) / frames
            for (channel in 0 until channels) {
                val i = offset + frame * frameBytes + channel * 2
                val sample = (data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)
                val scaled = (sample * applied).toInt().coerceIn(-32768, 32767)
                data[i] = scaled.toByte()
                data[i + 1] = (scaled shr 8).toByte()
            }
        }
    }
}
