package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.Closeable

/**
 * Encodes 20 ms chunks of 48 kHz mono PCM into raw Opus access units for the CarPlay
 * microphone uplink.
 *
 * Two back ends:
 *  - the platform encoder (`MediaCodec` "audio/opus"), available from Android 10 on;
 *  - a pure-Java Opus encoder (Concentus) for the older head units.
 *
 * The fallback matters on this car: the UNI-V's Feiyu OS is Android 9 (API 28), which has no
 * Opus *encoder* in MediaCodec (Opus encoding arrived with Android 10). Before the fallback
 * `createEncoderByType` threw, the uplink gave up with
 * `Microphone: failure ... stage=ENCODER`, and the iPhone received no microphone audio at all:
 * the other side heard nothing on a call and a WeChat voice message recorded silence
 * (field report 2026-10-20).
 */
internal class OpusEncoder(bitrate: Int, private val sampleRate: Int = SAMPLE_RATE) : Closeable {
    private val codec: MediaCodec? = createPlatformEncoder(bitrate, sampleRate)

    /**
     * Platform encoding is preferred when it exists: it is a native encoder and costs a fraction
     * of the pure-Java one. Only when the platform has none do we run Concentus.
     */
    private val software: org.concentus.OpusEncoder? =
        if (codec == null) createSoftwareEncoder(bitrate, sampleRate) else null

    private val softwarePcm = ShortArray(SOFTWARE_MAX_SAMPLES)
    private val softwarePacket = ByteArray(SOFTWARE_MAX_PACKET_BYTES)
    private var softwareFrames = 0
    private var softwareTotalMicros = 0L

    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationTimeUs = 0L
    private var closed = false
    private var outputPackets = 0

    val available: Boolean get() = !closed && (codec != null || software != null)

    /** Which back end is in use, for the diagnostics report. */
    val backend: String get() = when {
        codec != null -> "mediacodec"
        software != null -> "concentus"
        else -> "none"
    }

    /**
     * Queues one 20 ms PCM frame and returns all Opus access units made available by the codec.
     */
    fun encode(pcm: ByteArray): List<ByteArray> {
        if (closed) return emptyList()
        software?.let { return encodeSoftware(it, pcm) }
        return encodePlatform(pcm)
    }

    private fun encodeSoftware(encoder: org.concentus.OpusEncoder, pcm: ByteArray): List<ByteArray> {
        val samples = minOf(pcm.size / 2, softwarePcm.size)
        if (samples <= 0) return emptyList()
        for (index in 0 until samples) {
            val low = pcm[index * 2].toInt() and 0xff
            val high = pcm[index * 2 + 1].toInt()
            softwarePcm[index] = ((high shl 8) or low).toShort()
        }
        val startedAt = System.nanoTime()
        val size = try {
            encoder.encode(softwarePcm, 0, samples, softwarePacket, 0, softwarePacket.size)
        } catch (error: Exception) {
            Log.w(TAG, "Opus software encode failed samples=$samples", error)
            return emptyList()
        }
        softwareTotalMicros += (System.nanoTime() - startedAt) / 1000
        softwareFrames++
        if (size <= 0) return emptyList()
        val packet = softwarePacket.copyOf(size)
        if (softwareFrames <= FIRST_PACKET_LOG_COUNT) {
            Log.i(
                TAG,
                "Opus software packet=$softwareFrames backend=concentus samples=$samples " +
                    "bytes=${packet.size} encodeUs=${softwareTotalMicros / softwareFrames}",
            )
        }
        outputPackets++
        return listOf(packet)
    }

    private fun encodePlatform(pcm: ByteArray): List<ByteArray> {
        val codec = codec ?: return emptyList()
        if (closed) return emptyList()
        val inputIndex = try {
            codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        } catch (error: Exception) {
            Log.w(TAG, "Opus microphone input dequeue failed", error)
            return emptyList()
        }
        if (inputIndex >= 0) {
            val input = codec.getInputBuffer(inputIndex)
            if (input == null || pcm.size > input.remaining()) {
                codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
            } else {
                input.clear()
                input.put(pcm)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    pcm.size,
                    presentationTimeUs,
                    0,
                )
                presentationTimeUs += INPUT_DURATION_US
            }
        }
        return drain(codec)
    }

    private fun drain(codec: MediaCodec): List<ByteArray> {
        val output = ArrayList<ByteArray>()
        while (!closed) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, 0)
            } catch (error: Exception) {
                Log.w(TAG, "Opus microphone output dequeue failed", error)
                return output
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return output
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codec.releaseOutputBuffer(index, false)
                        continue
                    }
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && bufferInfo.size > 0) {
                        val bytes = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(bytes)
                        output.add(bytes)
                        outputPackets++
                        if (outputPackets <= FIRST_PACKET_LOG_COUNT) {
                            Log.i(
                                TAG,
                                "Opus microphone packet=$outputPackets backend=mediacodec " +
                                    "bytes=${bytes.size} head=${bytes.copyOf(minOf(bytes.size, 16)).toHexString()}",
                            )
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        return output
    }

    override fun close() {
        if (closed) return
        closed = true
        software?.let {
            // Concentus holds plain arrays: there is no native resource to release.
            if (softwareFrames > 0) {
                Log.i(
                    TAG,
                    "Opus software encoder closed frames=$softwareFrames " +
                        "avgEncodeUs=${softwareTotalMicros / softwareFrames}",
                )
            }
        }
        val codec = codec ?: return
        try {
            codec.stop()
        } catch (_: Exception) {
            // Best effort.
        }
        try {
            codec.release()
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val INPUT_TIMEOUT_US = 10_000L
        const val INPUT_DURATION_US = 20_000L
        const val MAX_INPUT_BYTES = 4_096
        const val FIRST_PACKET_LOG_COUNT = 3

        /** 60 ms at 48 kHz leaves room for any frame length the phone may ask for. */
        const val SOFTWARE_MAX_SAMPLES = 2_880
        const val SOFTWARE_MAX_PACKET_BYTES = 1_500

        fun createPlatformEncoder(bitrate: Int, sampleRate: Int): MediaCodec? = try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_OPUS,
                sampleRate,
                CHANNELS,
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            }
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).also {
                it.configure(
                    format,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                it.start()
                Log.i(TAG, "Opus microphone encoder started backend=mediacodec bitrate=$bitrate")
            }
        } catch (error: Exception) {
            // Expected on Android 9 and older: the platform has an Opus decoder but no encoder.
            Log.i(TAG, "Opus microphone encoder unavailable backend=mediacodec; using the software encoder")
            null
        }

        fun createSoftwareEncoder(bitrate: Int, sampleRate: Int): org.concentus.OpusEncoder? = try {
            org.concentus.OpusEncoder(
                sampleRate,
                CHANNELS,
                org.concentus.OpusApplication.OPUS_APPLICATION_VOIP,
            ).apply {
                setBitrate(bitrate)
                // Complexity 3 instead of the default 9. This is a pure-Java encoder running on a
                // head-unit SoC, and the uplink has exactly 20 ms of budget per frame; measured on
                // a desktop JVM the frame costs 0.4 ms at 3 against 1.5 ms at 9, and the head unit
                // is several times slower than a desktop. Opus complexity mostly buys music
                // fidelity, which a phone microphone does not need.
                setComplexity(SOFTWARE_COMPLEXITY)
                Log.i(TAG, "Opus microphone encoder started backend=concentus bitrate=$bitrate " +
                    "rate=$sampleRate complexity=$SOFTWARE_COMPLEXITY")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Opus software encoder unavailable", error)
            null
        }

        const val SOFTWARE_COMPLEXITY = 3
    }
}
