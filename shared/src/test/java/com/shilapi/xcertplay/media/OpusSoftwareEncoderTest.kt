package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * The microphone uplink depends on a working Opus encoder on a head unit whose MediaCodec has
 * none (Android 9). This exercises the same call shape [MicrophoneUplink] uses, so a broken or
 * mismatched Concentus copy fails the build instead of failing on the road.
 */
class OpusSoftwareEncoderTest {
    @Test
    fun encodesOne20msFrameIntoASinglePacket() {
        val encoder = org.concentus.OpusEncoder(
            48_000,
            1,
            org.concentus.OpusApplication.OPUS_APPLICATION_VOIP,
        )
        encoder.setBitrate(24_000)
        val pcm = ShortArray(960) { index ->
            (sin(2.0 * Math.PI * 440.0 * index / 48_000.0) * 8_000).toInt().toShort()
        }
        val packet = ByteArray(1_500)
        val size = encoder.encode(pcm, 0, 960, packet, 0, packet.size)
        // 24 kbit/s for 20 ms is 60 bytes; the encoder may add a little, never an order of magnitude.
        assertTrue("packet size $size", size in 1..400)
        assertTrue("packet must not be all zeroes", packet.copyOf(size).any { it.toInt() != 0 })
    }

    @Test
    fun honoursSixteenKhzUplinks() {
        // Siri/voice-recognition uplinks negotiate 16 kHz; a 48 kHz-only encoder would emit a
        // frame at the wrong length and every later RTP timestamp would drift.
        val encoder = org.concentus.OpusEncoder(
            16_000,
            1,
            org.concentus.OpusApplication.OPUS_APPLICATION_VOIP,
        )
        encoder.setBitrate(16_000)
        val pcm = ShortArray(320) { index ->
            (sin(2.0 * Math.PI * 440.0 * index / 16_000.0) * 8_000).toInt().toShort()
        }
        val packet = ByteArray(1_500)
        val size = encoder.encode(pcm, 0, 320, packet, 0, packet.size)
        assertTrue("packet size $size", size in 1..400)
    }

    @Test
    fun twentyMillisecondUplinkCarriesTheStreamsOwnSampleCount() {
        // Units the packetizer and the recorder have to agree on.
        val fortyEight = microphoneConfig(sampleRate = 48_000)
        assertEquals(960, fortyEight.samplesPerPacket)
        assertEquals(1_920, fortyEight.frameBytes)
        val sixteen = microphoneConfig(sampleRate = 16_000)
        assertEquals(320, sixteen.samplesPerPacket)
        assertEquals(640, sixteen.frameBytes)
    }

    private fun microphoneConfig(sampleRate: Int) = com.shilapi.xcertplay.airplay.MicrophoneConfig(
        audioType = "speechrecognition",
        sampleRate = sampleRate,
        channels = 1,
        payloadType = 100,
        frameMillis = 20,
        host = java.net.InetAddress.getLoopbackAddress(),
        port = 10_000,
        key = ByteArray(32),
        codec = com.shilapi.xcertplay.airplay.AudioCodecKind.OPUS,
        bitrate = 24_000,
    )
}
