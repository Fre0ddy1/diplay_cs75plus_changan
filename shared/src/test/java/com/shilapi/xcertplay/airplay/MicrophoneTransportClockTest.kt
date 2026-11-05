package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class MicrophoneTransportClockTest {
    @Test fun pcmPacketsIncludingIpv6AndUdpFitTheLinkMtu() {
        assertEquals(10, MicrophoneConfig.pcmFrameMillis(48_000, 1))
        assertEquals(5, MicrophoneConfig.pcmFrameMillis(48_000, 2))
        assertEquals(20, MicrophoneConfig.pcmFrameMillis(24_000, 1))
        for ((rate, channels) in listOf(48_000 to 1, 48_000 to 2, 44_100 to 1, 24_000 to 1, 16_000 to 1, 8_000 to 1)) {
            val config = config(rate, channels, AudioCodecKind.LPCM)
            val packet = MicrophonePacketizer.sealPacket(ByteArray(32), 100, MicrophoneCounters(), ByteArray(config.frameBytes), config.rtpSamplesPerPacket)
            assertTrue("$rate x$channels: ${packet.size}", packet.size + 40 + 8 <= 1500)
        }
    }
    @Test fun opusCaptureAndRtpClockAreIndependentForAllNegotiatedRates() {
        for ((bits, clock) in listOf(0x10000000L to 16_000, 0x20000000L to 24_000, 0x40000000L to 48_000)) {
            val config = config(48_000, 1, AudioCodecKind.OPUS).copy(opusClockRate = MicrophoneConfig.opusClockRate(bits))
            assertEquals(960, config.samplesPerPacket)
            assertEquals(clock / 50, config.rtpSamplesPerPacket)
            val counters = MicrophoneCounters()
            repeat(50) { MicrophonePacketizer.sealPacket(ByteArray(32), 100, counters, byteArrayOf(1), config.rtpSamplesPerPacket) }
            assertEquals(clock, counters.timestamp)
        }
    }
    private fun config(rate: Int, channels: Int, codec: AudioCodecKind) = MicrophoneConfig("speechrecognition", rate,
        channels, 100, MicrophoneConfig.pcmFrameMillis(rate, channels), InetAddress.getLoopbackAddress(), 9, ByteArray(32), codec)
}
