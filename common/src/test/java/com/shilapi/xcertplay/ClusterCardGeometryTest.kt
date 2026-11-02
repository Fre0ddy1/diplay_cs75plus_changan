package com.shilapi.xcertplay

import android.hardware.display.DisplayManager
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.media.ScreenStreamGeometry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class ClusterCardGeometryTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private var displayId=-1
    private lateinit var presentation:ClusterMapPresentation
    private val crop=ScreenStreamGeometry(512,208,16,4,480,200)
    @Before fun start() {
        context.getSharedPreferences("xcertplay_airplay",0).edit().clear().commit()
        displayId=ShadowDisplayManager.addDisplay("w1920dp-h720dp",5)
        val display=context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        presentation=ClusterMapPresentation(context,display,onMirrorSurface={},onNavSurface={},flyCard=true)
        presentation.show(); presentation.setMode(false)
        presentation.setCardStreamActive(true)
        presentation.setTurnCardGuidance(ClusterTurnGuidance(3,0,159,"测试路"))
    }
    @After fun finish() { presentation.dismiss(); ShadowDisplayManager.removeDisplay(displayId); AirPlayPersistence.overlaySettingsListener=null }
    private fun container():FrameLayout=ReflectionHelpers.getField(presentation,"cardContainer")
    @Test fun paddedBufferIsClippedAtItsOffsetWithoutScalingTheCard() {
        presentation.setCardVideoGeometry(crop)
        val outer=container(); val inner=outer.getChildAt(0) as TextureView
        assertEquals(480,outer.layoutParams.width); assertEquals(200,outer.layoutParams.height)
        val params=inner.layoutParams as FrameLayout.LayoutParams
        assertEquals(512,params.width); assertEquals(208,params.height)
        assertEquals(-16,params.leftMargin); assertEquals(-4,params.topMargin)
        assertTrue(outer.clipChildren); assertFalse(outer.clipToOutline)
    }
    @Test fun invalidCropCannotOverwriteTheLastValidLayout() {
        presentation.setCardVideoGeometry(crop)
        presentation.setCardVideoGeometry(crop.copy(cropLeft=100))
        assertEquals(480,container().layoutParams.width)
        assertEquals(-16,(container().getChildAt(0).layoutParams as FrameLayout.LayoutParams).leftMargin)
    }
    @Test fun mirrorModeHidesTheContainerWithoutDestroyingItsTexture() {
        presentation.setCardVideoGeometry(crop)
        val old=container()
        presentation.setMode(true); assertEquals(View.GONE,old.visibility)
        presentation.setMode(false); assertEquals(View.VISIBLE,old.visibility); assertSame(old,container())
    }
    @Test fun stoppingTheCardStreamDropsTheWholeCropWindow() {
        presentation.setCardVideoGeometry(crop)
        presentation.setCardStreamActive(false)
        assertNull(ReflectionHelpers.getField<Any?>(presentation,"cardContainer"))
        assertNull(ReflectionHelpers.getField<Any?>(presentation,"cardTextureView"))
    }
}
