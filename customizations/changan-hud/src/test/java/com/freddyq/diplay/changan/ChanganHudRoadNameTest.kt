package com.freddyq.diplay.changan

import org.junit.Assert.*
import org.junit.Test

class ChanganHudRoadNameTest {
    @Test fun observedEightCharacterRoadIsPreservedBeforeBroadcast() {
        val name = "北横通道 长宁路"
        assertEquals(22, name.toByteArray(Charsets.UTF_8).size)
        assertEquals(name, ChanganHudFrame.roadName(name))
    }
    @Test fun protocolLimitPreservesUtf8CharacterBoundaries() {
        val result = ChanganHudFrame.roadName("路".repeat(22))
        assertEquals("路".repeat(21), result)
        assertEquals(63, result.toByteArray(Charsets.UTF_8).size)
    }
}
