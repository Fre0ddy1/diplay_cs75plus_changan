package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.ScreenStreamGeometry
import org.junit.Assert.*
import org.junit.Test

class ClusterStreamBindingTest {
    private val map=ScreenStreamGeometry(1920,736,0,0,1920,720)
    private val card=ScreenStreamGeometry(512,208,16,4,480,200)
    @Test fun aMapOnTheSecondStreamDoesNotNeedTheFirstStreamToReport() {
        val state=ClusterStreamBinding()
        assertTrue(state.observe(112,map,1920)); assertEquals(112,state.mapType); assertEquals(111,state.cardType)
    }
    @Test fun cardFirstIsCorrectedAndLaterReportsDoNotSwapItBack() {
        val state=ClusterStreamBinding()
        state.observe(111,card,1920); state.observe(112,map,1920)
        assertEquals(112,state.mapType); assertSame(card,state.cardGeometry())
        assertFalse(state.observe(111,card,1920)); assertEquals(112,state.mapType)
    }
    @Test fun bothFullPanelPicturesDoNotBecomeAnEnormousCard() {
        val state=ClusterStreamBinding()
        state.observe(111,map,1920); state.observe(112,map,1920)
        assertNull(state.cardGeometry())
    }
    @Test fun invalidAndNonClusterReportsCannotChangeBindings() {
        val state=ClusterStreamBinding()
        assertFalse(state.observe(110,map,1920))
        assertFalse(state.observe(112,map.copy(cropLeft=-1),1920))
        assertEquals(111,state.mapType); assertNull(state.cardGeometry())
    }
    @Test fun anUnsampledPanelDoesNotCauseSpeculativeRebinding() {
        val state=ClusterStreamBinding()
        assertFalse(state.observe(111,card,0)); assertEquals(111,state.mapType)
        assertTrue(state.observe(111,card,1920)); assertEquals(112,state.mapType)
        state.observe(112,map,1920); assertEquals(112,state.mapType)
    }
    @Test fun teardownForgetsTheOldPictureAndResetCannotLeakIntoANewSession() {
        val state=ClusterStreamBinding()
        state.observe(111,card,1920); state.observe(112,map,1920)
        state.forget(111); assertNull(state.cardGeometry())
        state.reset(); assertEquals(111,state.mapType); assertNull(state.cardGeometry())
        state.observe(112,card,1920); state.observe(111,map,1920)
        assertEquals(111,state.mapType); assertSame(card,state.cardGeometry())
    }
}
