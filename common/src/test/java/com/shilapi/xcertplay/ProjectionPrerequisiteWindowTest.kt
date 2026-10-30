package com.shilapi.xcertplay

import android.os.Looper
import android.view.TextureView
import com.freddyq.diplay.customization.VehicleCustomization
import com.freddyq.diplay.customization.VehicleCustomizations
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ProjectionPrerequisiteWindowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val extensions get() = ReflectionHelpers.getField<MutableList<VehicleCustomization>>(VehicleCustomizations,"extensions")
    private var saved = emptyList<VehicleCustomization>()
    private lateinit var host: CarPlayHostActivity
    private fun window(): ClusterMapPresentation? = ReflectionHelpers.getField(host,"clusterPresentation")
    private fun invoke(name: String) = ReflectionHelpers.callInstanceMethod<Unit>(host,name)
    @Before fun prepare() {
        saved=extensions.toList(); extensions.clear()
        ShadowDisplayManager.addDisplay("w1920dp-h720dp")
        host=Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
    }
    @After fun restore() {
        invoke("dismissClusterPresentation")
        extensions.clear(); extensions.addAll(saved)
    }
    @Test fun returningFromPrerequisiteReplacesExistingWindowWithoutStartingPhoneSession() {
        invoke("ensureClusterWindowQuiet")
        val previous=window()!!
        assertTrue(previous.isShowing)
        val controller=org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        val session=org.mockito.Mockito.mock(com.shilapi.xcertplay.airplay.AirPlaySession::class.java)
        ReflectionHelpers.setField(host,"controller",controller)
        ReflectionHelpers.setField(host,"activeAirPlaySession",session)
        invoke("refreshProjectionWindowAfterPrerequisite")
        val current=window()!!
        assertNotSame(previous,current)
        assertFalse(previous.isShowing)
        assertTrue(current.isShowing)
        assertSame(controller,ReflectionHelpers.getField<Any?>(host,"controller"))
        assertSame(session,ReflectionHelpers.getField<Any?>(host,"activeAirPlaySession"))
    }
    @Test fun dismissedWindowSurfaceCallbackCannotClearReplacementSurface() {
        invoke("ensureClusterWindowQuiet")
        val previous=window()!!
        val oldNav=ReflectionHelpers.getField<TextureView>(previous,"navTextureView")
        val oldCallback=oldNav.surfaceTextureListener!!
        invoke("refreshProjectionWindowAfterPrerequisite")
        val current=window()!!
        val sentinel=org.mockito.Mockito.mock(android.view.Surface::class.java)
        ReflectionHelpers.setField(host,"navClusterSurface",sentinel)
        val texture=org.mockito.Mockito.mock(android.graphics.SurfaceTexture::class.java)
        oldCallback.onSurfaceTextureDestroyed(texture)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(current,window())
        assertSame(sentinel,ReflectionHelpers.getField<android.view.Surface>(host,"navClusterSurface"))
    }
}
