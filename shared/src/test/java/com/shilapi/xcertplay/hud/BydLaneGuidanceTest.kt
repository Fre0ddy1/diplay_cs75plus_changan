package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the verified 0x5204 layout: per-lane index/status/repeated angles/highlight.
 * The old decoder guessed a count/current/bitmask frame; this test would fail on that layout.
 */
class BydLaneGuidanceTest {
    @Test
    fun lanesAreSelectedByTheRouteUpdateLaneIndex() {
        val route = BydHudRouteState()
        val lanePayload = laneMessage(
            eventIndex = 7,
            lanes = listOf(
                lane(position = 0, status = 2, angles = listOf(0, -45), highlight = 0),
                lane(position = 1, status = 1, angles = listOf(45), highlight = 45),
            ),
        )

        // iOS sends 0x5204 before the 0x5201 that selects the event.
        assertEquals(BydHudRouteChange.GUIDANCE, route.accept(0x5204, lanePayload))
        assertNull(route.currentLanes())

        assertEquals(BydHudRouteChange.GUIDANCE, route.accept(0x5201, routeUpdate(eventIndex = 7, showing = 1)))
        val lanes = route.currentLanes()
        assertNotNull(lanes)
        assertEquals(2, lanes!!.lanes.size)
        assertEquals(7, lanes.eventIndex)
        assertEquals(0, lanes.lanes[0].primaryAngle)
        assertEquals(2, lanes.lanes[0].status)
        assertEquals(45, lanes.lanes[1].primaryAngle)
        assertEquals(0, lanes.currentLane)
    }

    @Test
    fun unknownAnglesAndHiddenLanesNeverBecomeArrows() {
        val route = BydHudRouteState()
        route.accept(
            0x5204,
            laneMessage(
                eventIndex = 3,
                lanes = listOf(
                    lane(position = 0, status = 0, angles = listOf(1000), highlight = 1000),
                    lane(position = 1, status = 1, angles = emptyList(), highlight = null),
                ),
            ),
        )
        route.accept(0x5201, routeUpdate(eventIndex = 3, showing = 1))

        val lanes = route.currentLanes()
        assertNotNull(lanes)
        assertNull(lanes!!.lanes[0].primaryAngle)
        assertNull(lanes.lanes[1].primaryAngle)
        assertEquals(-1, lanes.currentLane)

        route.accept(0x5201, routeUpdate(eventIndex = 3, showing = 0))
        assertNull(route.currentLanes())
    }

    private fun routeUpdate(eventIndex: Int, showing: Int): ByteArray =
        tlv(0x01, byteArrayOf(1)) +
            tlv(0x10, u16(eventIndex)) +
            tlv(0x11, u16(2)) +
            tlv(0x12, byteArrayOf(showing.toByte()))

    private fun laneMessage(eventIndex: Int, lanes: List<ByteArray>): ByteArray =
        tlv(0x01, u16(eventIndex)) + tlv(0x02, lanes.reduce { a, b -> a + b })

    private fun lane(
        position: Int,
        status: Int,
        angles: List<Int>,
        highlight: Int?,
    ): ByteArray {
        val angleBytes = angles.flatMap { listOf((it shr 8).toByte(), it.toByte()) }.toByteArray()
        var result = tlv(0x00, u16(position)) + tlv(0x01, byteArrayOf(status.toByte()))
        result += tlv(0x02, angleBytes)
        if (highlight != null) result += tlv(0x03, u16(highlight))
        return result
    }

    private fun tlv(id: Int, value: ByteArray): ByteArray {
        val length = 4 + value.size
        return byteArrayOf(
            (length shr 8).toByte(), length.toByte(),
            (id shr 8).toByte(), id.toByte(),
        ) + value
    }

    private fun u16(value: Int): ByteArray =
        byteArrayOf((value shr 8).toByte(), value.toByte())
}
