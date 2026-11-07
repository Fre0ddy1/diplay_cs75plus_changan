package com.shilapi.xcertplay

import android.os.Handler
import com.freddyq.diplay.customization.VehicleCustomization
import com.freddyq.diplay.customization.VehicleCustomizations
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class HostProjectionManualRecoveryTest {
    private lateinit var host: CarPlayHostActivity
    private var opens=0
    private var manual=0
    private var oldExtensions=emptyList<VehicleCustomization>()
    private val saved=mutableMapOf<String,Any?>()
    private var oldCallbacks=emptyMap<Int,Int>()
    private val extensions get()=ReflectionHelpers.getField<MutableList<VehicleCustomization>>(VehicleCustomizations,"extensions")
    private val callbacks get()=ReflectionHelpers.getStaticField<MutableMap<Int,Int>>(InCallClusterBridge::class.java,"lastCallbackValue")
    private fun field(name:String,value:Any?)=ReflectionHelpers.setStaticField(InCallClusterBridge::class.java,name,value)
    private fun swipe(direction:String)=ReflectionHelpers.callInstanceMethod<Unit>(host,"swipeClusterMode",
        ReflectionHelpers.ClassParameter.from(String::class.java,direction))

    @Before fun prepare() {
        for(name in listOf("observationOwner","contentHandoffWindow","resumeGeneration","automaticProjectionOwner")) {
            saved[name]=ReflectionHelpers.getStaticField<Any?>(InCallClusterBridge::class.java,name)
        }
        oldCallbacks=callbacks.toMap(); callbacks.clear()
        oldExtensions=extensions.toList(); extensions.clear()
        VehicleCustomizations.register(object: VehicleCustomization {
            override val id="manual-recovery-observer"
            override fun onProjectionManualControl() { manual++ }
            override fun onProjectionManualOpen() { opens++ }
        })
        RuntimeEnvironment.getApplication().getSharedPreferences("InCallClusterBridge",0).edit().clear().commit()
        field("resumeGeneration",null)
        field("automaticProjectionOwner",null)
        host=Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
    }
    @After fun restore() {
        ReflectionHelpers.getField<Handler>(host,"mainHandler").removeCallbacksAndMessages(null)
        for(name in listOf("airPlayCommandExecutor","teardownExecutor")) {
            ReflectionHelpers.getField<ExecutorService>(host,name).shutdownNow()
        }
        extensions.clear(); extensions.addAll(oldExtensions)
        for((name,value) in saved) field(name,value)
        callbacks.clear(); callbacks.putAll(oldCallbacks)
    }
    @Test fun manualOpenOfAnUnavailableChannelRequestsContentRecoveryButClosingDoesNot() {
        swipe("left")
        assertEquals(1,opens)
        assertTrue(ReflectionHelpers.getField(host,"manualClusterOverride"))
        swipe("right")
        assertEquals(2,manual)
        assertEquals(1,opens)
        assertTrue(ReflectionHelpers.getField(host,"userClosedProjection"))
    }
    private fun retainedWindow(): ClusterMapPresentation {
        val window=mock(ClusterMapPresentation::class.java)
        ReflectionHelpers.setField(host,"clusterPresentation",window)
        field("observationOwner",WeakReference(window))
        field("contentHandoffWindow",WeakReference(window))
        callbacks[1]=3; callbacks[2]=3
        return window
    }
    @Test fun confirmedHotSwitchDoesNotReopenTheSource() {
        retainedWindow()
        swipe("left")
        assertEquals(1,manual)
        assertEquals(0,opens)
    }
    @Test fun cachedFullscreenWithPendingConfirmationIsNotATrustedHotSwitch() {
        retainedWindow()
        val generation=ReflectionHelpers.getStaticField<Int>(InCallClusterBridge::class.java,"generation")
        field("resumeGeneration",generation)
        swipe("left")
        assertEquals(1,manual)
        assertEquals(1,opens)
    }
}
