package com.shilapi.xcertplay.iap2.message

import org.junit.Assert.*
import org.junit.Test

class LaneGuidanceUpdatesTest {
    @Test fun offIgnoresFramesAndOnOnlyReportsChangedLanes() {
        var count = 0
        val off = LaneGuidanceUpdates(false) { count++ }
        off.accept(0x5204, byteArrayOf()); assertEquals(0, count)
        val on = LaneGuidanceUpdates(true) { assertTrue(it.isEmpty()); count++ }
        on.accept(0x5201, byteArrayOf()); assertEquals(0, count)
        on.accept(0x5204, byteArrayOf()); on.accept(0x5204, byteArrayOf()); assertEquals(1, count)
        on.clear(); on.accept(0x5204, byteArrayOf()); assertEquals(2, count)
    }
}
