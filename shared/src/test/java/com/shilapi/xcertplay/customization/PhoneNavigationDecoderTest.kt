package com.shilapi.xcertplay.customization

import org.junit.Assert.*
import org.junit.Test

class PhoneNavigationDecoderTest {
    private var now = 0L

    @Test fun `default adapter retains upstream silence expiry and maneuver only traffic cannot revive it`() {
        val decoder = populated(PhoneNavigationDecoder(nanoTime = { now }))
        now = 30_000_000_000L
        decoder.accept(0x5202, tlv(1,0,7) + tlv(3,2))
        assertNull(decoder.current())
        decoder.accept(0x5201, byteArrayOf(0,8,0,1,1))
        assertNull(decoder.current())
        decoder.accept(0x5201, tlv(0x0a,0,0,0,27))
        assertEquals(27, decoder.current()!!.distanceMeters)
    }

    @Test fun `session retention is opt in and explicit end still clears it`() {
        val decoder = populated(PhoneNavigationDecoder({ now }, retainGuidanceUntilSessionEnds = true))
        now = 3_600_000_000_000L
        assertEquals(159, decoder.current()!!.distanceMeters)
        decoder.accept(0x5201, tlv(1,0))
        assertNull(decoder.current())
        decoder.accept(0x5202, tlv(1,0,7) + tlv(3,2))
        assertNull(decoder.current())
    }

    private fun populated(decoder: PhoneNavigationDecoder): PhoneNavigationDecoder {
        decoder.accept(0x5202, tlv(1,0,7) + tlv(3,2))
        decoder.accept(0x5201, tlv(1,1) + tlv(0x0a,0,0,0,159) + tlv(0x0d,0,7))
        assertEquals(159, decoder.current()!!.distanceMeters)
        return decoder
    }

    private fun tlv(type: Int, vararg values: Int): ByteArray {
        val size = values.size + 4
        return byteArrayOf((size ushr 8).toByte(),size.toByte(),(type ushr 8).toByte(),type.toByte()) + values.map(Int::toByte)
    }
}
