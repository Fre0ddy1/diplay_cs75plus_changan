package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class ScreenStreamGeometryTest {
    @Test fun cropCanRemoveBothPaddingAndAnOffset() {
        assertTrue(ScreenStreamGeometry(512,208,16,4,480,200).valid)
    }
    @Test fun fullBufferIsAValidCrop() { assertTrue(ScreenStreamGeometry(1920,720,0,0,1920,720).valid) }
    @Test fun invalidOffsetsAndEmptyPicturesAreRejected() {
        assertFalse(ScreenStreamGeometry(512,208,-1,0,480,200).valid)
        assertFalse(ScreenStreamGeometry(512,208,0,0,0,200).valid)
        assertFalse(ScreenStreamGeometry(512,208,0,0,480,209).valid)
    }
    @Test fun overflowDoesNotTurnAnOutOfBoundsCropIntoAValidOne() {
        assertFalse(ScreenStreamGeometry(512,208,Int.MAX_VALUE,0,Int.MAX_VALUE,200).valid)
    }
}
