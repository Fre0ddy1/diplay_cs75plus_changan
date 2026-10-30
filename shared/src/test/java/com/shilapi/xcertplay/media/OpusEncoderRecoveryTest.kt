package com.shilapi.xcertplay.media

import android.media.MediaCodec
import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [OpusEncoderRecoveryTest.NoPlatformOpus::class])
class OpusEncoderRecoveryTest {
    @Implements(MediaCodec::class)
    class NoPlatformOpus {
        companion object {
            @JvmStatic @Implementation
            fun createEncoderByType(type: String): MediaCodec = throw IllegalArgumentException("no $type encoder on API28")
        }
    }
    @Test fun assertionLosesOneFrameAndTheNextFrameDecodesAtBothSupportedRates() {
        for (rate in listOf(16_000, 48_000)) {
            var resets = 0
            val software = object : org.concentus.OpusEncoder(rate, 1, OpusApplication.OPUS_APPLICATION_VOIP) {
                var frames = 0
                override fun encode(pcm: ShortArray, pcmOffset: Int, frameSize: Int, output: ByteArray,
                    outputOffset: Int, maxBytes: Int): Int {
                    if (frames++ == 1) throw AssertionError("Concentus check")
                    return super.encode(pcm, pcmOffset, frameSize, output, outputOffset, maxBytes)
                }
                override fun resetState() { resets++; super.resetState() }
            }
            withSoftware(rate, software) { encoder ->
                val pcm = ByteArray(rate / 50 * 2)
                assertEquals(1, encoder.encode(pcm).size)
                val before = resets
                assertTrue(encoder.encode(pcm).isEmpty())
                assertEquals(before + 1, resets)
                val packet = encoder.encode(pcm).single()
                val samples = ShortArray(rate / 50)
                assertEquals(samples.size, OpusDecoder(rate, 1).decode(packet, 0, packet.size, samples, 0, samples.size, false))
                assertTrue(encoder.available)
            }
        }
    }

    @Test fun repeatedExceptionsResetStateWithoutUnboundedWarnings() {
        var resets = 0
        val software = object : org.concentus.OpusEncoder(48_000, 1, OpusApplication.OPUS_APPLICATION_VOIP) {
            override fun encode(pcm: ShortArray, pcmOffset: Int, frameSize: Int, output: ByteArray,
                outputOffset: Int, maxBytes: Int): Int = throw IllegalStateException("bad frame")
            override fun resetState() { resets++; super.resetState() }
        }
        withSoftware(48_000, software) { encoder ->
            val before = resets
            ShadowLog.clear()
            repeat(20) { assertTrue(encoder.encode(ByteArray(1920)).isEmpty()) }
            assertEquals(before + 20, resets)
            assertEquals(3, ShadowLog.getLogs().count { it.msg.startsWith("Opus software encode failed") })
        }
    }

    private fun withSoftware(rate: Int, software: org.concentus.OpusEncoder, test: (OpusEncoder) -> Unit) {
        OpusEncoder(24_000, rate).use { encoder ->
            assertEquals("concentus", encoder.backend)
            ReflectionHelpers.setField(encoder, "software", software)
            test(encoder)
        }
    }
}
