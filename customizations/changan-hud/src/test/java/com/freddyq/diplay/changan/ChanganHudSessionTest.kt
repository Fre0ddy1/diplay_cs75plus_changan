package com.freddyq.diplay.changan

import com.freddyq.diplay.customization.StandardNavigationGuidance

import org.junit.Assert.*
import org.junit.Test

class ChanganHudSessionTest {
    private var now = 0L
    private var projection = ChanganHudProjection()
    private val packets = mutableListOf<ChanganHudPacket>()
    private val session = ChanganHudSession({ now }, { projection }) { packets += it; true }

    @Test fun `idle startup does not clear the factory map`() {
        session.start()
        session.tick(true)
        session.stop()
        assertTrue(packets.isEmpty())
    }

    @Test fun `real route TLVs produce the tested right turn and road`() {
        populate()
        assertEquals(ChanganHudPacket.State(8), packets[0])
        assertEquals(ChanganHudFrame(3,159,"HUD测试路A","当前路",0), (packets[1] as ChanganHudPacket.Guidance).frame)
        assertEquals(2, packets.size)
    }

    @Test fun `uturn uses NEW_ICON 8 rather than vehicle icon 23`() {
        populate(type = 4, distance = 33)
        assertEquals(8, (packets[1] as ChanganHudPacket.Guidance).frame.icon)
        assertEquals(33, (packets[1] as ChanganHudPacket.Guidance).frame.distanceMeters)
    }

    @Test fun `unchanged route repeats at one second without repeating start`() {
        populate()
        now = 999_000_000
        session.tick(true)
        assertEquals(2, packets.size)
        now = 1_000_000_000
        session.tick(true)
        assertEquals(3, packets.size)
        assertTrue(packets.last() is ChanganHudPacket.Guidance)
        assertEquals(1, packets.count { it == ChanganHudPacket.State(8) })
    }

    @Test fun `changed distance is sent immediately`() {
        populate()
        session.onFrame(0x5201, tlv(0x0a, 0,0,0,150), true)
        assertEquals(150, (packets.last() as ChanganHudPacket.Guidance).frame.distanceMeters)
    }

    @Test fun `arrival clears once and later ticks cannot revive it`() {
        populate()
        session.onFrame(0x5201, tlv(1,2), true)
        session.tick(true)
        assertEquals(ChanganHudPacket.State(9), packets.last())
        assertEquals(1, packets.count { it == ChanganHudPacket.State(9) })
    }

    @Test fun `disconnect rejects delayed frames and reconnect needs a fresh route`() {
        populate()
        session.stop()
        val count = packets.size
        session.onFrame(0x5201, tlv(0x0a,0,0,0,100), true)
        session.tick(true)
        assertEquals(count, packets.size)
        session.start()
        session.tick(true)
        assertEquals(count, packets.size)
    }

    @Test fun `disabled output is silent then enabling uses current phone data`() {
        populate(enabled = false)
        assertTrue(packets.isEmpty())
        session.tick(true)
        assertEquals(ChanganHudPacket.State(8), packets.first())
        session.tick(false)
        assertEquals(ChanganHudPacket.State(9), packets.last())
        val count = packets.size
        session.tick(false)
        assertEquals(count, packets.size)
    }

    @Test fun `stationary change-driven guidance remains visible beyond thirty seconds`() {
        populate()
        for (seconds in listOf(30L, 60L, 90L, 600L, 3600L)) {
            now = seconds * 1_000_000_000L
            session.tick(true)
            assertEquals(ChanganHudFrame(3,159,"HUD测试路A","当前路",0), (packets.last() as ChanganHudPacket.Guidance).frame)
        }
        assertEquals(1, packets.count { it == ChanganHudPacket.State(8) })
        assertFalse(packets.contains(ChanganHudPacket.State(9)))
    }

    @Test fun `temporary empty maneuver list does not flash the HUD`() {
        populate()
        session.onFrame(0x5201,tlv(0x0d),true)
        now = 2_000_000_000
        session.tick(true)
        assertFalse(packets.contains(ChanganHudPacket.State(9)))
        now = 3_100_000_000
        session.tick(true)
        assertEquals(ChanganHudPacket.State(9),packets.last())
    }

    @Test fun `projection clear states settle before a single state and frame restore`() {
        populate()
        val count = packets.size
        projection = ChanganHudProjection(1,1_500_000_000)
        now = 1_000_000_000
        session.tick(true)
        assertEquals(count,packets.size)
        projection = ChanganHudProjection(2,2_000_000_000)
        now = 1_600_000_000
        session.tick(true)
        assertEquals(count,packets.size)
        now = 2_000_000_000
        session.tick(true)
        assertEquals(listOf(ChanganHudPacket.State(8), packets[1]),packets.takeLast(2))
        assertFalse(packets.contains(ChanganHudPacket.State(9)))
    }

    @Test fun `route ending during projection pause cannot be restored`() {
        populate()
        projection = ChanganHudProjection(1,1_500_000_000)
        session.onFrame(0x5201,tlv(1,0),true)
        val count = packets.size
        now = 2_000_000_000
        session.tick(true)
        assertEquals(count,packets.size)
        assertEquals(ChanganHudPacket.State(9),packets.last())
    }

    @Test fun `unknown maneuver never becomes false straight guidance`() {
        populate(type=255)
        assertTrue(packets.isEmpty())
    }

    @Test fun `malformed traffic does not corrupt retained stationary guidance`() {
        populate()
        now = 60_000_000_000
        session.onFrame(0x5201,byteArrayOf(0,8,0,1,1),true)
        assertEquals(ChanganHudFrame(3,159,"HUD测试路A","当前路",0), (packets.last() as ChanganHudPacket.Guidance).frame)
        assertFalse(packets.contains(ChanganHudPacket.State(9)))
    }

    @Test fun `phone cancellation after prolonged silence clears and cannot resurrect`() {
        populate()
        now = 600_000_000_000L
        session.tick(true)
        session.onFrame(0x5201, tlv(1,0), true)
        assertEquals(ChanganHudPacket.State(9), packets.last())
        val count = packets.size
        now += 600_000_000_000L
        session.onFrame(0x5202, tlvs(tlv(1,0,7),tlv(3,2)), true)
        session.tick(true)
        assertEquals(count, packets.size)
    }

    @Test fun `disconnect after prolonged silence clears and reconnect cannot reuse cached route`() {
        populate()
        now = 600_000_000_000L
        session.tick(true)
        session.stop()
        assertEquals(ChanganHudPacket.State(9), packets.last())
        val count = packets.size
        session.onFrame(0x5201, tlv(0x0a,0,0,0,27), true)
        session.start()
        session.tick(true)
        assertEquals(count, packets.size)
    }

    @Test fun `projection restores stationary guidance after prolonged silence`() {
        populate()
        now = 600_000_000_000L
        val count = packets.size
        projection = ChanganHudProjection(1, now + 1_500_000_000L)
        session.tick(true)
        assertEquals(count, packets.size)
        now += 2_000_000_000L
        session.tick(true)
        assertEquals(listOf(ChanganHudPacket.State(8), packets[1]), packets.takeLast(2))
        assertFalse(packets.contains(ChanganHudPacket.State(9)))
    }

    @Test fun `UTF8 road truncation preserves Chinese and emoji boundaries`() {
        assertEquals("路".repeat(21),ChanganHudFrame.roadName("路".repeat(21)+"🚗"))
        assertEquals(64,ChanganHudFrame.roadName("🚗".repeat(20)).toByteArray(Charsets.UTF_8).size)
        assertEquals("无名道路",ChanganHudFrame.roadName("无名道路"))
    }

    @Test fun `only states that reset navigation trigger projection coordination`() {
        listOf(0,9,12,25,311).forEach { assertTrue(ChanganHudSession.projectionStateClearsGuidance(it)) }
        listOf(3,4,8,40,2001,417).forEach { assertFalse(ChanganHudSession.projectionStateClearsGuidance(it)) }
    }

    @Test fun `failed start is retried before any guidance`() {
        var fail = true
        val sent = mutableListOf<ChanganHudPacket>()
        val output = ChanganHudSession({now}) { packet ->
            if (packet == ChanganHudPacket.State(8) && fail) { fail = false; false }
            else { sent += packet; true }
        }
        output.start()
        output.onFrame(0x5202,tlvs(tlv(1,0,7),tlv(3,2)),true)
        output.onFrame(0x5201,tlvs(tlv(1,1),tlv(0x0d,0,7)),true)
        assertTrue(sent.isEmpty())
        output.tick(true)
        assertEquals(ChanganHudPacket.State(8),sent.first())
        assertTrue(sent.last() is ChanganHudPacket.Guidance)
    }

    @Test fun `failed clear on route end is retried without restarting navigation`() {
        var failClear = true
        val sent = mutableListOf<ChanganHudPacket>()
        val output = ChanganHudSession({now}) { packet ->
            if (packet == ChanganHudPacket.State(9) && failClear) { failClear = false; false }
            else { sent += packet; true }
        }
        output.start()
        output.onFrame(0x5202,tlvs(tlv(1,0,7),tlv(3,2)),true)
        output.onFrame(0x5201,tlvs(tlv(1,1),tlv(0x0d,0,7)),true)
        output.onFrame(0x5201,tlv(1,0),true)
        output.tick(true)
        output.tick(true)
        assertEquals(1,sent.count { it == ChanganHudPacket.State(9) })
        assertEquals(1,sent.count { it == ChanganHudPacket.State(8) })
    }

    @Test fun `unsupported roundabout exit does not masquerade as exit ten`() {
        val frame = ChanganHudFrame.from(StandardNavigationGuidance(11,Int.MAX_VALUE,"","",19))!!
        assertEquals(0,frame.roundaboutExit)
        assertEquals(131071,frame.distanceMeters)
    }

    @Test fun `foreign cruise reset restores only the current owned route without one second wait`() {
        populate()
        val before = packets.size
        assertTrue(session.recoverFromExternalReset(true))
        assertEquals(ChanganHudPacket.State(8),packets[before])
        assertEquals(159,(packets.last() as ChanganHudPacket.Guidance).frame.distanceMeters)
    }

    @Test fun `foreign packet storms are rate limited`() {
        populate()
        assertTrue(session.recoverFromExternalReset(true))
        val count = packets.size
        repeat(100) { assertFalse(session.recoverFromExternalReset(true)) }
        assertEquals(count,packets.size)
        now += 250_000_000L
        assertTrue(session.recoverFromExternalReset(true))
    }

    @Test fun `foreign resets cannot resurrect stopped disabled or arrived routes`() {
        session.start()
        assertFalse(session.recoverFromExternalReset(true))
        populate()
        assertFalse(session.recoverFromExternalReset(false))
        session.onFrame(0x5201,tlv(1,0),true)
        assertFalse(session.recoverFromExternalReset(true))
        session.stop()
        assertFalse(session.recoverFromExternalReset(true))
    }

    @Test fun `foreign reset repair respects instrument handshake quiet time`() {
        populate()
        projection = ChanganHudProjection(1,2_000_000_000L)
        assertFalse(session.recoverFromExternalReset(true))
        now = 2_000_000_000L
        assertTrue(session.recoverFromExternalReset(true))
    }

    private fun populate(type: Int = 2, distance: Int = 159, enabled: Boolean = true) {
        session.start()
        session.onFrame(0x5202,tlvs(tlv(1,0,7),tlv(3,type),tlv(4,*utf8("HUD测试路A")),tlv(8,0)),enabled)
        session.onFrame(0x5201,tlvs(tlv(1,1),tlv(3,*utf8("当前路")),tlv(0x0a,0,0,distance ushr 8,distance and 255),tlv(0x0d,0,7)),enabled)
    }

    private fun utf8(text: String): IntArray = (text.toByteArray(Charsets.UTF_8)+byteArrayOf(0)).map { it.toInt() and 255 }.toIntArray()
    private fun tlvs(vararg values: ByteArray): ByteArray = values.fold(byteArrayOf()) { result,value -> result+value }
    private fun tlv(type: Int,vararg values: Int): ByteArray {
        val size = values.size+4
        return byteArrayOf((size ushr 8).toByte(),size.toByte(),(type ushr 8).toByte(),type.toByte())+values.map(Int::toByte)
    }
}
