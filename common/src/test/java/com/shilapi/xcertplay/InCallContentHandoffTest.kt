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
    private var onSourceWrite: (() -> Unit)? = null
    private var onModeCommit: ((Boolean, Int?) -> Unit)? = null
    private var pendingSlide = false
    private var pendingAck: Int? = null
    private var rejectEntries = 0
    private var rejectAcks = 0
    private fun entries() = calls.count { it.first == 21 && it.second == listOf(1,0) }
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
            if (code == 21) pendingSlide = false
            if (code == 21 && rejectEntries > 0) { rejectEntries--; return false }
            if (code == 8 && rejectAcks > 0) { rejectAcks--; return false }
            if (code == 21) pendingSlide = true
            if (code == 8) pendingAck = values.first()
            if (code == 1) {
                val slide = pendingSlide; val ack = pendingAck
                pendingSlide = false; pendingAck = null
                onModeCommit?.invoke(slide, ack)
            }
            if (code == 2 && values == listOf(1)) onSourceWrite?.invoke()
            reply?.writeNoException()
            reply?.writeInt(1)
            return true
        }
    }
    @Before fun prepare() {
        for(name in listOf("binder","remoteServiceEpoch","openState","contextRef","bound","observeCallbackRequested",
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
    private fun reply(code:Int,value:Int,dispatch:Boolean=true) {
        val callback=ReflectionHelpers.getStaticField<IBinder>(InCallClusterBridge::class.java,"navigateCallback")
        val data=Parcel.obtain()
        val result=Parcel.obtain()
        try {
            data.writeInterfaceToken("com.incall.serversdk.interactive.callback.INavigateCallback")
            data.writeInt(value)
            assertTrue(callback.transact(code,data,result,0))
            result.readException()
        } finally { data.recycle(); result.recycle() }
        if (dispatch) idle(0)
    }

    private fun frameReady(window:Any) {
        module.onProjectionContentReady(app, window,
            com.freddyq.diplay.customization.ProjectionContentControl { result ->
                InCallClusterBridge.requestContentHandoff(app,window,result)
            })
    }
    @Test fun experimentalTransportSuppressesEveryHostLayoutWriteButKeepsSourceHandshake() {
        val sourceWindows = mutableListOf<Any>()
        VehicleCustomizations.register(object : VehicleCustomization {
            override val id = "probe-transport-test"
            override fun dispatchProjectionLayout(context: Context, layout: ProjectionLayout, service: IBinder) = false
            override fun onProjectionSourceReady(context: Context, window: Any, service: IBinder) {
                assertSame(fake, service)
                assertEquals(1, sourceAcknowledgements())
                sourceWindows += window
            }
        })
        val window = Any()
        InCallClusterBridge.observeCallback(app, window)
        frameReady(window); idle(5000)
        assertTrue(calls.none { it.first in setOf(1, 8, 21) })
        assertEquals(1, sourceAcknowledgements())
        assertEquals(listOf(window), sourceWindows)
    }
    @Test fun experimentalTransportFailureCannotFallBackToOrdinarySwitchWrites() {
        VehicleCustomizations.register(object : VehicleCustomization {
            override val id = "broken-probe-transport-test"
            override fun dispatchProjectionLayout(context: Context, layout: ProjectionLayout, service: IBinder): Boolean? {
                throw IllegalStateException("temporary agent unavailable")
            }
        })
        val window = Any()
        InCallClusterBridge.observeCallback(app, window)
        frameReady(window); idle(5000)
        assertTrue(calls.none { it.first in setOf(1, 8, 21) })
        assertEquals(1, sourceAcknowledgements())
    }
    @Test fun stockFullscreenStillRequiresSourceHandoffAfterVideoReady() {
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        reply(1,3); reply(2,3)
        InCallClusterBridge.adoptObservedProjection(app)
        InCallClusterBridge.resumeAutomaticProjection(app,Any())
        idle(10000)
        assertEquals(0,sourceAcknowledgements())
        frameReady(window); idle(2200)
        assertEquals(1,sourceAcknowledgements())
        assertTrue(calls.none { it.first==8 && it.second==listOf(1,0) })
        assertCompleteLvdsHandshakes(4)
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
        // Cached replies belong before the actual source request. Fresh replies after it
        // are precisely the valid early pair seen on the car, not stale fullscreen values.
        frameReady(window); idle(1499)
        reply(1,3); reply(2,3)
        assertEquals(0,confirmed)
        idle(101)
        reply(2,3)
        assertEquals(0,confirmed)
        reply(1,3)
        assertEquals(1,confirmed)
        val attempts=fullscreenAttempts()
        idle(30000)
        assertEquals(attempts,fullscreenAttempts())
        assertEquals(1,sourceAcknowledgements())
    }

    @Test fun sourceRepliesBeforeDelayedFullscreenRequestRevealCurrentWindowOnce() {
        var confirmed=0
        field("projectingListener",{ value:Boolean -> if(value) confirmed++ })
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        frameReady(window); idle(1600)
        assertEquals(1,sourceAcknowledgements())
        assertEquals(0,fullscreenAttempts())
        reply(1,3); reply(2,3)
        assertEquals("the source request's own pair must reveal its current window",1,confirmed)
        idle(30000) // An idempotent later setMode(3) need not produce another callback.
        assertEquals(0,fullscreenAttempts())
        assertEquals(1,confirmed)
        assertEquals(1,sourceAcknowledgements())
    }

    @Test fun layoutRetryPreservesFreshPartialSourceConfirmation() {
        var confirmed=0
        field("projectingListener",{ value:Boolean -> if(value) confirmed++ })
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        frameReady(window); idle(1600)
        reply(1,3)
        assertEquals(0,confirmed)
        idle(1500)
        assertEquals(1,fullscreenAttempts())
        reply(2,3)
        assertEquals("retrying the same request must not erase a fresh layout reply",1,confirmed)
        idle(30000)
        assertEquals(1,fullscreenAttempts())
    }

    @Test fun oneSourceReplyCannotRevealWindowAndRecoveryRemainsBounded() {
        callbacks[2]=3 // A cached open value must not complete the fresh layout reply.
        var confirmed=0
        field("projectingListener",{ value:Boolean -> if(value) confirmed++ })
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        frameReady(window); idle(1600)
        reply(1,3)
        idle(60000)
        assertEquals(0,confirmed)
        assertEquals(3,fullscreenAttempts())
        assertFalse(InCallClusterBridge.reportsProjecting())
    }

    @Test fun dismissedWindowRejectsQueuedSourceConfirmation() {
        var confirmed=0
        field("projectingListener",{ value:Boolean -> if(value) confirmed++ })
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        frameReady(window); idle(1600)
        reply(1,3,dispatch=false); reply(2,3,dispatch=false)
        InCallClusterBridge.forgetContentWindow(window)
        InCallClusterBridge.observeCallback(app,Any())
        idle(30000)
        assertEquals(0,confirmed)
    }

    @Test fun contradictorySourceRepliesCannotCompleteAnOldPartialPair() {
        var confirmed=0
        field("projectingListener",{ value:Boolean -> if(value) confirmed++ })
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        frameReady(window); idle(1600)
        reply(1,3); reply(2,0); reply(1,1); reply(2,3)
        assertEquals(0,confirmed)
        reply(1,3)
        assertEquals(1,confirmed)
    }

    @Test fun synchronousSourceRepliesAreAcceptedBeforeDelayedLayoutRetry() {
        var confirmed=0
        field("projectingListener",{ value:Boolean -> if(value) confirmed++ })
        onSourceWrite = { reply(1,3,dispatch=false); reply(2,3,dispatch=false) }
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        frameReady(window); idle(1600)
        assertEquals(1,sourceAcknowledgements())
        assertEquals(1,confirmed)
        idle(30000)
        assertEquals(0,fullscreenAttempts())
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

    @Test fun directFullscreenCoversColdActivationAndCurrentSourceHandoff() {
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        InCallClusterBridge.openProjection(app,automatic=true)
        idle(1600)
        frameReady(window); idle(2200)
        assertEquals(2,sourceAcknowledgements())
        val modes=calls.filter { it.first==8 }.map { it.second.first() }
        assertTrue(modes.isNotEmpty())
        assertTrue("activation, holds and content takeover must all skip small ACK",modes.all { it==3 })
        assertCompleteLvdsHandshakes(8)
    }

    @Test fun directFullscreenAlsoCoversRetainedSilentChannelAndReassertion() {
        callbacks[1]=1; callbacks[2]=3
        InCallClusterBridge.openProjection(app,automatic=true)
        idle(1000)
        InCallClusterBridge.reassertProjection(app)
        idle(1000)
        assertEquals(0,sourceAcknowledgements())
        val modes=calls.filter { it.first==8 }.map { it.second.first() }
        assertTrue(modes.isNotEmpty())
        assertTrue(modes.all { it==3 })
        assertCompleteLvdsHandshakes(5)
    }

    @Test fun disablingAutomaticFullscreenRetainsTheOriginalInitialSequence() {
        app.getSharedPreferences("diplay_changan_projection",0).edit().putBoolean("automatic_fullscreen",false).commit()
        InCallClusterBridge.openProjection(app,automatic=true)
        idle(100)
        assertTrue(calls.any { it.first==8 && it.second==listOf(1,0) })
        assertTrue(calls.any { it.first==8 && it.second==listOf(3,0) })
        assertEquals("legacy mode keeps entry before each layout ACK",calls.count { it.first==8 },entries())
    }

    @Test fun noCustomizationRetainsTheUpstreamInitialSequence() {
        extensions.clear()
        InCallClusterBridge.openProjection(app,automatic=true)
        idle(100)
        assertTrue(calls.any { it.first==8 && it.second==listOf(1,0) })
        assertTrue(calls.any { it.first==8 && it.second==listOf(3,0) })
        assertEquals("legacy mode keeps entry before each layout ACK",calls.count { it.first==8 },entries())
    }
    /** Contract captured on the car: switching event, ACK, then finish; an ACK alone is not a request. */
    private fun assertCompleteLvdsHandshakes(count: Int) {
        val wire=calls.filter { it.first in setOf(21,8,1) }
        val expected=listOf(21 to listOf(1,0),8 to listOf(3,0),1 to listOf(0))
        assertEquals(List(count) { expected }.flatten(),wire)
        assertEquals(count,entries())
    }

    private fun rawVendorWrite(code: Int, values: List<Int>) {
        val data=Parcel.obtain(); val result=Parcel.obtain()
        try {
            data.writeInterfaceToken("com.incall.serversdk.interactive.IDouInteractiveManager")
            values.forEach(data::writeInt)
            assertTrue(fake.transact(code,data,result,0))
            result.readException()
        } finally { data.recycle(); result.recycle() }
    }

    @Test fun layoutRetryPreservesCompleteLvdsHandshake() {
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        InCallClusterBridge.requestContentHandoff(app,window) {}
        idle(1000)
        assertCompleteLvdsHandshakes(4)
        val control=controls.last()
        calls.clear()
        assertTrue(control.requestLayout(ProjectionLayout.FULLSCREEN))
        assertCompleteLvdsHandshakes(1)
    }

    @Test fun replacementVideoWindowKeepsSourceAndLvdsHandshake() {
        val first=Any()
        InCallClusterBridge.observeCallback(app,first)
        InCallClusterBridge.requestContentHandoff(app,first) {}
        idle(1000)
        val replacement=Any()
        InCallClusterBridge.observeCallback(app,replacement)
        InCallClusterBridge.requestContentHandoff(app,replacement) {}
        idle(1000)
        assertEquals(2,sourceAcknowledgements())
        assertCompleteLvdsHandshakes(8)
    }

    @Test fun smallScreenCallbacksKeepCompleteHandshakeDuringBoundedRetries() {
        InCallClusterBridge.openProjection(app,automatic=true)
        idle(1000)
        reply(1,1); reply(2,2)
        idle(11000)
        assertCompleteLvdsHandshakes(7)
        assertEquals(3,fullscreenAttempts())
        assertFalse(InCallClusterBridge.reportsProjecting())
    }

    @Test fun closedChannelReopensWithCompleteLvdsHandshake() {
        InCallClusterBridge.openProjection(app,automatic=true)
        idle(1000)
        assertCompleteLvdsHandshakes(4)
        reply(2,0)
        InCallClusterBridge.openProjection(app,automatic=true)
        idle(1000)
        assertCompleteLvdsHandshakes(8)
    }

    @Test fun explicitHandbackReopensWithCompleteLvdsHandshake() {
        InCallClusterBridge.openProjection(app,automatic=true)
        idle(1000)
        InCallClusterBridge.releaseExternalScreen(app)
        InCallClusterBridge.openProjection(app,automatic=true)
        idle(1000)
        assertCompleteLvdsHandshakes(8)
    }

    @Test fun powerInvalidationReopensWithCompleteLvdsHandshake() {
        InCallClusterBridge.openProjection(app,automatic=true)
        idle(1000)
        InCallClusterBridge.invalidateProjectionChannel()
        InCallClusterBridge.openProjection(app,automatic=true)
        idle(1000)
        assertCompleteLvdsHandshakes(8)
    }

    @Test fun reconnectedServiceReactivatesWithCompleteLvdsHandshake() {
        InCallClusterBridge.openProjection(app,automatic=true)
        idle(1000)
        val connection=ReflectionHelpers.getStaticField<android.content.ServiceConnection>(InCallClusterBridge::class.java,"connection")
        connection.onServiceDisconnected(null)
        connection.onServiceConnected(null,fake)
        InCallClusterBridge.openProjection(app,automatic=true)
        idle(1000)
        assertCompleteLvdsHandshakes(8)
    }

    @Test fun rejectedSlideIsReportedAndLaterCompleteHandshakeCanRecover() {
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        InCallClusterBridge.requestContentHandoff(app,window) {}
        idle(1000)
        val control=controls.last(); calls.clear()
        rejectEntries=1
        assertFalse(control.requestLayout(ProjectionLayout.FULLSCREEN))
        assertTrue(control.requestLayout(ProjectionLayout.FULLSCREEN))
        assertCompleteLvdsHandshakes(2)
    }

    @Test fun rejectedAckIsNotSuccessAndRetryKeepsRequiredSlide() {
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        InCallClusterBridge.requestContentHandoff(app,window) {}
        idle(1000)
        val control=controls.last(); calls.clear()
        rejectAcks=1
        assertFalse(control.requestLayout(ProjectionLayout.FULLSCREEN))
        assertTrue(control.requestLayout(ProjectionLayout.FULLSCREEN))
        assertCompleteLvdsHandshakes(2)
    }

    @Test fun confirmationFallbackKeepsCompleteHandshakeAndRejectsExpiredControl() {
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        InCallClusterBridge.requestContentHandoff(app,window) {}
        idle(1000)
        val expired=controls.last()
        expired.invalidateConfirmation()
        InCallClusterBridge.openProjection(app,automatic=true)
        idle(1000)
        assertCompleteLvdsHandshakes(8)
        val generation=ReflectionHelpers.getStaticField<Int>(InCallClusterBridge::class.java,"generation")
        expired.invalidateConfirmation()
        assertFalse(expired.requestLayout(ProjectionLayout.FULLSCREEN))
        assertEquals(generation,ReflectionHelpers.getStaticField<Int>(InCallClusterBridge::class.java,"generation"))
        assertCompleteLvdsHandshakes(8)
    }

    @Test fun observedAckOnlyRecoveryCannotConfirmFullUntilSwitchEventArrives() {
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        InCallClusterBridge.requestContentHandoff(app,window) {}
        idle(1000)
        val control=controls.last()
        var fullReplies=0
        // Negative replay of 65: the vendor ignores bare ACKs as switching requests.
        onModeCommit={slide,ack -> if (slide && ack==3) {
            fullReplies++
            reply(1,3,dispatch=false); reply(2,3,dispatch=false)
        } }
        calls.clear()
        repeat(3) { rawVendorWrite(8,listOf(3,0)); rawVendorWrite(1,listOf(0)) }
        assertEquals(0,fullReplies)
        assertFalse(InCallClusterBridge.reportsProjecting())
        calls.clear()
        assertTrue(control.requestLayout(ProjectionLayout.FULLSCREEN))
        idle(0)
        assertEquals(1,fullReplies)
        assertTrue(InCallClusterBridge.reportsProjecting())
        assertCompleteLvdsHandshakes(1)
        idle(30000)
        assertCompleteLvdsHandshakes(1)
    }

    @Test fun observedOpeningStateRequiresSwitchingEventBeforeFreshFullPair() {
        var vendorReady=false
        onModeCommit={slide,ack ->
            if (slide && ack==3 && vendorReady) {
                reply(1,3,dispatch=false); reply(2,3,dispatch=false)
            } else reply(1,1,dispatch=false)
        }
        val window=Any()
        InCallClusterBridge.observeCallback(app,window)
        InCallClusterBridge.requestContentHandoff(app,window) {}
        idle(1000)
        assertFalse(InCallClusterBridge.reportsProjecting())
        assertEquals(1,sourceAcknowledgements())
        vendorReady=true
        reply(2,2) // External readiness, not invented from an outgoing ACK.
        idle(600)
        assertTrue(InCallClusterBridge.reportsProjecting())
        assertEquals(1,fullscreenAttempts())
        assertCompleteLvdsHandshakes(5)
        idle(30000)
        assertEquals(1,fullscreenAttempts())
        assertCompleteLvdsHandshakes(5)
    }
}
