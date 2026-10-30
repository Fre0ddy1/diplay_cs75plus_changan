package com.shilapi.xcertplay

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.freddyq.diplay.changan.projection.ChanganProjectionCustomization
import com.freddyq.diplay.customization.ProjectionControl
import com.freddyq.diplay.customization.ProjectionLayout
import com.freddyq.diplay.customization.VehicleCustomization
import com.freddyq.diplay.customization.VehicleCustomizations
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

/** Regression: a global fullscreen state never skips current-window source dispatch. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class InCallContentHandoffTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val module = ChanganProjectionCustomization()
    private val calls = mutableListOf<Pair<Int,List<Int>>>()
    private val saved = mutableMapOf<String,Any?>()
    private var oldCallbacks = emptyMap<Int,Int>()
    private var oldExtensions = emptyList<VehicleCustomization>()
    private val controls = mutableListOf<ProjectionControl>()
    private val callbacks get() = ReflectionHelpers.getStaticField<MutableMap<Int,Int>>(InCallClusterBridge::class.java,"lastCallbackValue")
    private val extensions get() = ReflectionHelpers.getField<MutableList<VehicleCustomization>>(VehicleCustomizations,"extensions")
    private fun field(name:String,value:Any?) = ReflectionHelpers.setStaticField(InCallClusterBridge::class.java,name,value)
    private fun idle(ms:Long) = shadowOf(Looper.getMainLooper()).idleFor(ms,TimeUnit.MILLISECONDS)
    private fun sourceAcknowledgements() = calls.count { it.first==2 && it.second==listOf(1) }
    private val fake = object: Binder() {
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            data.enforceInterface("com.incall.serversdk.interactive.IDouInteractiveManager")
            val values=mutableListOf<Int>()
            if(code in setOf(1,2,8,21)) repeat(if(code==1 || code==2) 1 else 2) { values+=data.readInt() }
            calls+=code to values
            reply?.writeNoException()
            reply?.writeInt(1)
            return true
        }
    }
    @Before fun prepare() {
        for(name in listOf("binder","openState","contextRef","bound","observeCallbackRequested",
            "callbackRegistered","callbackHeard","generation","observationOwner",
            "projectionNeedsReconfirmation","automaticProjectionOwner","callbackSerial","resumeGeneration",
            "resumeRequestSerial","resumeFullSeen","resumeOpenSeen","projectingListener",
            "silentOpenOnly","broadcastHalfSent","sourceEnterSent","silentHoldScheduled","contentHandoffWindow","contentReadyWindow","projectionDisplayAvailable")) {
            saved[name]=ReflectionHelpers.getStaticField<Any?>(InCallClusterBridge::class.java,name)
        }
        oldCallbacks=callbacks.toMap()
        oldExtensions=extensions.toList()
        extensions.clear()
        app.getSharedPreferences("InCallClusterBridge",0).edit().clear().commit()
        app.getSharedPreferences("diplay_changan_projection",0).edit().clear().commit()
        module.onSessionEnded()
        VehicleCustomizations.register(module)
        VehicleCustomizations.register(object : VehicleCustomization {
            override val id = "content-confirmation-observer"
            override fun onProjectionResumed(context: Context, control: ProjectionControl): Boolean {
                controls += control
                return false
            }
        })
        field("binder",fake)
        field("contextRef",app)
        field("openState",false)
        field("bound",true)
        field("observeCallbackRequested",true)
        field("callbackRegistered",true)
        field("callbackHeard",false)
        field("observationOwner",null)
        field("projectionNeedsReconfirmation",false)
        field("automaticProjectionOwner",null)
        field("resumeGeneration",null)
        field("resumeRequestSerial",Long.MAX_VALUE)
        field("contentHandoffWindow",null)
        field("contentReadyWindow",null)
        field("projectionDisplayAvailable",true)
        callbacks.clear()
    }
    @After fun restore() {
        module.onSessionEnded()
        extensions.clear()
        extensions.addAll(oldExtensions)
        for((name,value) in saved) field(name,value)
        callbacks.clear()
        callbacks.putAll(oldCallbacks)
    }
    private fun reply(code:Int,value:Int) {
        val callback=ReflectionHelpers.getStaticField<IBinder>(InCallClusterBridge::class.java,"navigateCallback")
        val data=Parcel.obtain()
        val result=Parcel.obtain()
        try {
            data.writeInterfaceToken("com.incall.serversdk.interactive.callback.INavigateCallback")
            data.writeInt(value)
            assertTrue(callback.transact(code,data,result,0))
            result.readException()
        } finally { data.recycle(); result.recycle() }
        idle(0)
    }

    private fun frameReady(window:Any) {
        module.onProjectionContentReady(app, window,
            com.freddyq.diplay.customization.ProjectionContentControl { result ->
                InCallClusterBridge.requestContentHandoff(app,window,result)
            })
    }
    @Test fun stockFullscreenStillRequiresSourceHandoffAfterVideoReady() {
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        reply(1,3); reply(2,3)
        InCallClusterBridge.adoptObservedProjection(app)
        InCallClusterBridge.resumeAutomaticProjection(app,Any())
        idle(10000)
        assertEquals(0,sourceAcknowledgements())
        frameReady(window); idle(1600)
        assertEquals(1,sourceAcknowledgements())
        assertTrue(calls.none { it.first==8 && it.second==listOf(1,0) })
    }
    @Test fun freshGlobalRepliesDoNotCancelIndependentContentHandoff() {
        field("openState",true)
        callbacks[1]=3; callbacks[2]=3
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        InCallClusterBridge.resumeAutomaticProjection(app,Any())
        idle(1500); reply(1,3); reply(2,3)
        frameReady(window); idle(1600)
        assertEquals(1,sourceAcknowledgements())
        frameReady(window); idle(60000)
        assertEquals(1,sourceAcknowledgements())
    }
    @Test fun expiredWindowCannotChangeCurrentSource() {
        val old=Any()
        InCallClusterBridge.observeCallback(app,old)
        frameReady(old)
        InCallClusterBridge.observeCallback(app,Any())
        idle(60000)
        assertEquals(0,sourceAcknowledgements())
    }
    @Test fun dismissedWindowCancelsQueuedProtocolWorkBeforeSourceAck() {
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        frameReady(window); idle(1500)
        InCallClusterBridge.forgetContentWindow(window)
        idle(10000)
        assertEquals(0,sourceAcknowledgements())
    }
    @Test fun sameWindowWithExecutedSourceHandshakeIsReusedWithoutBroadcastLadder() {
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        reply(1,3); reply(2,3)
        frameReady(window); idle(1600)
        assertEquals(1,sourceAcknowledgements())
        calls.clear()
        var reused=false
        InCallClusterBridge.requestContentHandoff(app,window) { reused=it }
        idle(10000)
        assertTrue(reused)
        assertEquals(0,sourceAcknowledgements())
    }
    @Test fun morningPreVideoHandshakeDoesNotSuppressCurrentContentTakeover() {
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        InCallClusterBridge.openProjection(app,automatic=true)
        idle(100); reply(1,3); reply(2,3); idle(10000)
        assertEquals(1,sourceAcknowledgements())
        frameReady(window); idle(1600)
        assertEquals("pre-video source handshake is not current video ownership",2,sourceAcknowledgements())
    }
    @Test fun expiredOwnerCannotClearCurrentWindowReadiness() {
        val currentOwner=Any()
        val window=Any()
        field("automaticProjectionOwner",currentOwner)
        field("contentReadyWindow",java.lang.ref.WeakReference(window))
        InCallClusterBridge.detachAutomaticProjection(Any())
        val readiness=ReflectionHelpers.getStaticField<java.lang.ref.WeakReference<Any>>(InCallClusterBridge::class.java,"contentReadyWindow")
        assertSame(window,readiness.get())
    }

    private fun fullscreenAttempts(): Int {
        val bridge = Class.forName("com.freddyq.diplay.changan.projection.ChanganProjectionBridge")
        val policy = ReflectionHelpers.getStaticField<Any>(bridge, "policy")
        return ReflectionHelpers.getField(policy, "attempts")
    }

    @Test fun contentHandoffRebindsAndContinuesAnInterruptedWarmRecovery() {
        field("openState",true); callbacks[1]=3; callbacks[2]=3
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        InCallClusterBridge.resumeAutomaticProjection(app,Any())
        idle(1500); reply(1,1)
        assertEquals(1,fullscreenAttempts())
        val expired=controls.last()
        frameReady(window); idle(1600)
        assertEquals(1,sourceAcknowledgements())
        assertNotSame(expired,controls.last())
        assertFalse(expired.requestLayout(ProjectionLayout.FULLSCREEN))
        idle(1500)
        assertEquals(1,fullscreenAttempts())
        idle(2500)
        assertEquals("source takeover must not strand the fullscreen retry",2,fullscreenAttempts())
        idle(2500)
        assertEquals(3,fullscreenAttempts())
        idle(20000)
        assertEquals(3,fullscreenAttempts())
        assertFalse(InCallClusterBridge.reportsProjecting())
        assertTrue(module.shouldActivateProjectionChannel())
    }

    @Test fun sourceDispatchAndOldFullValuesCannotConfirmCurrentWindow() {
        field("openState",true); callbacks[1]=3; callbacks[2]=3
        var confirmed=0
        field("projectingListener",{ value:Boolean -> if(value) confirmed++ })
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        frameReady(window); idle(1600)
        reply(1,3); reply(2,3)
        assertEquals(0,confirmed)
        idle(1500)
        reply(2,3)
        assertEquals(0,confirmed)
        reply(1,3)
        assertEquals(1,confirmed)
        val attempts=fullscreenAttempts()
        idle(30000)
        assertEquals(attempts,fullscreenAttempts())
        assertEquals(1,sourceAcknowledgements())
    }

    @Test fun expiredContentControlCannotInvalidateReplacementWindow() {
        val old=Any()
        InCallClusterBridge.observeCallback(app,old)
        frameReady(old); idle(1600)
        val expired=controls.last()
        val fresh=Any()
        InCallClusterBridge.observeCallback(app,fresh)
        frameReady(fresh); idle(1600)
        val generation=ReflectionHelpers.getStaticField<Int>(InCallClusterBridge::class.java,"generation")
        expired.invalidateConfirmation()
        assertEquals(generation,ReflectionHelpers.getStaticField<Int>(InCallClusterBridge::class.java,"generation"))
        assertFalse(expired.requestLayout(ProjectionLayout.FULLSCREEN))
        assertTrue(controls.last().requestLayout(ProjectionLayout.FULLSCREEN))
    }

    @Test fun disabledFullscreenDoesNotArmContentConfirmation() {
        app.getSharedPreferences("diplay_changan_projection",0).edit().putBoolean("automatic_fullscreen",false).commit()
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        // A direct/manual source request must not turn the automatic fullscreen option on.
        InCallClusterBridge.requestContentHandoff(app,window) {}
        idle(100)
        assertEquals(1,sourceAcknowledgements())
        assertNull(ReflectionHelpers.getStaticField<Any?>(InCallClusterBridge::class.java,"resumeGeneration"))
    }
}
