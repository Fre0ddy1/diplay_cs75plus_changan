package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class InCallReconnectLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val module = ChanganProjectionCustomization()
    private val calls = mutableListOf<Pair<Int, List<Int>>>()
    private val saved = mutableMapOf<String, Any?>()
    private var oldCallbacks = emptyMap<Int, Int>()
    private var oldExtensions = emptyList<VehicleCustomization>()
    private var capturedControl: ProjectionControl? = null
    private var confirmations = 0
    private val callbacks get() = ReflectionHelpers.getStaticField<MutableMap<Int, Int>>(InCallClusterBridge::class.java, "lastCallbackValue")
    private val extensions get() = ReflectionHelpers.getField<MutableList<VehicleCustomization>>(VehicleCustomizations, "extensions")
    private fun field(name: String, value: Any?) = ReflectionHelpers.setStaticField(InCallClusterBridge::class.java, name, value)
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    private fun fullRequests() = calls.count { it.first == 8 && it.second == listOf(3, 0) }

    private val fake = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface("com.incall.serversdk.interactive.IDouInteractiveManager")
            val values = mutableListOf<Int>()
            if (code in setOf(1, 8, 21)) repeat(if (code == 1) 1 else 2) { values += data.readInt() }
            calls += code to values
            reply?.writeNoException()
            reply?.writeInt(1)
            return true
        }
    }

    @Before fun prepare() {
        for (name in listOf("binder", "fullscreenEntryBinder", "openState", "contextRef", "bound", "observeCallbackRequested",
            "callbackRegistered", "callbackHeard", "generation", "observationOwner",
            "projectionNeedsReconfirmation", "automaticProjectionOwner", "callbackSerial", "resumeGeneration",
            "resumeRequestSerial", "resumeFullSeen", "resumeOpenSeen", "projectingListener",
            "projectionDisplayAvailable", "remoteServiceEpoch")) {
            saved[name] = ReflectionHelpers.getStaticField<Any?>(InCallClusterBridge::class.java, name)
        }
        oldCallbacks = callbacks.toMap()
        oldExtensions = extensions.toList()
        extensions.clear()
        app.getSharedPreferences("InCallClusterBridge", 0).edit().clear().commit()
        app.getSharedPreferences("diplay_changan_projection", 0).edit().clear().commit()
        module.onSessionEnded()
        VehicleCustomizations.register(module)
        VehicleCustomizations.register(object : VehicleCustomization {
            override val id = "reconnect-control-observer"
            override fun onProjectionResumed(context: Context, control: ProjectionControl): Boolean {
                capturedControl = control
                return false // The production module is the owner, this observer only records it.
            }
        })
        field("binder", fake)
        field("fullscreenEntryBinder", null)
        field("contextRef", app)
        field("openState", true)
        field("bound", true)
        field("observeCallbackRequested", true)
        field("callbackRegistered", true)
        field("observationOwner", null)
        field("projectionNeedsReconfirmation", false)
        field("automaticProjectionOwner", null)
        field("resumeGeneration", null)
        field("resumeRequestSerial", Long.MAX_VALUE)
        field("projectionDisplayAvailable", true)
        callbacks.clear()
        callbacks[1] = 3
        callbacks[2] = 3
        InCallClusterBridge.projectingListener = { if (it) confirmations++ }
    }

    @After fun restore() {
        module.onSessionEnded()
        extensions.clear()
        extensions.addAll(oldExtensions)
        for ((name, value) in saved) field(name, value)
        callbacks.clear()
        callbacks.putAll(oldCallbacks)
    }

    private fun reply(code: Int, value: Int, dispatch: Boolean = true) {
        val callback = ReflectionHelpers.getStaticField<IBinder>(InCallClusterBridge::class.java, "navigateCallback")
        val data = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.incall.serversdk.interactive.callback.INavigateCallback")
            data.writeInt(value)
            assertTrue(callback.transact(code, data, response, 0))
            response.readException()
        } finally {
            data.recycle()
            response.recycle()
        }
        if (dispatch) idle(0)
    }

    private fun newWindow(owner: Any = Any()): Any {
        InCallClusterBridge.observeCallback(app, owner)
        val session = Any()
        InCallClusterBridge.resumeAutomaticProjection(app, session)
        return session
    }

    @Test fun newWindowRearmsAfterEndedSessionAndRequiresTwoFreshReplies() {
        module.onSessionEnded()
        VehicleCustomizations.onSessionStarted(app)
        newWindow()
        reply(1, 3) // Old cached/full feedback before our request is insufficient.
        reply(2, 3)
        assertEquals(0, confirmations)
        idle(1500)
        assertEquals(1, fullRequests())
        reply(2, 3)
        assertFalse(module.shouldRetryAutomaticProjection()) // Recovery still owns the request.
        assertFalse(module.shouldActivateProjectionChannel())
        reply(1, 3)
        assertEquals(1, confirmations)
        assertTrue(module.shouldActivateProjectionChannel())
        idle(60000)
        assertEquals(1, fullRequests())
        assertTrue(calls.none { it.first == 8 && it.second == listOf(1, 0) })
    }

    @Test fun freshRepliesInOppositeOrderAlsoConfirmEvenWhenValuesDidNotChange() {
        newWindow()
        idle(1500)
        reply(1, 3)
        assertEquals(0, confirmations)
        reply(2, 3)
        assertEquals(1, confirmations)
        assertTrue(InCallClusterBridge.reportsProjecting())
    }

    @Test fun duplicateOwnerDoesNotRestartBudget() {
        val owner = newWindow()
        idle(1500)
        InCallClusterBridge.resumeAutomaticProjection(app, owner)
        idle(2500)
        assertEquals(2, fullRequests())
        idle(60000)
        assertEquals(3, fullRequests())
        assertFalse(InCallClusterBridge.reportsProjecting())
        assertTrue(module.shouldActivateProjectionChannel())
    }

    @Test fun expiredOwnerCannotInvalidateNewOwner() {
        newWindow()
        val old = capturedControl!!
        newWindow()
        old.invalidateConfirmation()
        assertTrue(InCallClusterBridge.reportsProjecting())
        assertFalse(old.requestLayout(ProjectionLayout.FULLSCREEN))
        idle(1500)
        assertEquals(1, fullRequests())
    }

    @Test fun endedSessionCancelsQueuedWarmRequests() {
        val owner = newWindow()
        InCallClusterBridge.detachAutomaticProjection(owner)
        VehicleCustomizations.onSessionEnded()
        idle(60000)
        assertEquals(0, fullRequests())
    }

    @Test fun queuedFreshConfirmationCannotReshowAnEndedOwner() {
        val owner = newWindow()
        idle(1500)
        reply(1,3,dispatch=false)
        reply(2,3,dispatch=false)
        InCallClusterBridge.detachAutomaticProjection(owner)
        VehicleCustomizations.onSessionEnded()
        idle(0)
        assertEquals(0, confirmations)
    }

    @Test fun sameWindowReattachesPolicyWithoutExtraModeSwitch() {
        val window = Any()
        newWindow(window)
        idle(1500)
        reply(1, 3)
        reply(2, 3)
        VehicleCustomizations.onSessionEnded()
        calls.clear()
        InCallClusterBridge.observeCallback(app, window)
        InCallClusterBridge.resumeAutomaticProjection(app, Any())
        idle(60000)
        assertTrue(calls.none { it.first in setOf(1, 8, 21) })
        assertFalse(module.shouldRetryAutomaticProjection())
    }

    @Test fun disabledFullscreenDoesNotClaimWarmRecoveryOrExpireChannel() {
        app.getSharedPreferences("diplay_changan_projection", 0).edit().putBoolean("automatic_fullscreen", false).commit()
        newWindow()
        idle(60000)
        assertEquals(0, fullRequests())
        assertTrue(InCallClusterBridge.reportsProjecting())
    }

    @Test fun sameWindowRestoreDoesNotCancelInFlightActivationWork() {
        val handler = ReflectionHelpers.getStaticField<android.os.Handler>(InCallClusterBridge::class.java, "mainHandler")
        val token = ReflectionHelpers.getStaticField<Any>(InCallClusterBridge::class.java, "token")
        var sourceHandshakeFinished = false
        handler.postAtTime({ sourceHandshakeFinished = true }, token, android.os.SystemClock.uptimeMillis() + 100)
        InCallClusterBridge.resumeAutomaticProjection(app, Any())
        idle(100)
        assertTrue(sourceHandshakeFinished)
    }

    private inner class AppScope : ContextWrapper(app) {
        var binds = 0
        var unbinds = 0
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean { binds++; return true }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }

    @Test fun observationUsesApplicationScopeAndDoesNotDuplicatePendingBinding() {
        val scope = AppScope()
        val page = object : ContextWrapper(scope) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean = error("Activity must not own binding")
        }
        field("binder", null)
        field("bound", false)
        field("observeCallbackRequested", false)
        InCallClusterBridge.observeCallback(page)
        InCallClusterBridge.observeCallback(page)
        assertEquals(1, scope.binds)
    }

    @Test fun nullBindingReleasesRegistrationAndLaterObservationCanRetry() {
        val scope = AppScope()
        field("contextRef", scope)
        val connection = ReflectionHelpers.getStaticField<ServiceConnection>(InCallClusterBridge::class.java, "connection")
        connection.onNullBinding(null)
        assertEquals(1, scope.unbinds)
        assertFalse(InCallClusterBridge.reportsProjecting())
        InCallClusterBridge.observeCallback(scope)
        assertEquals(1, scope.binds)
    }

    @Test fun bindingDeathRebindsOnceWithApplicationScopeAndDropsCachedState() {
        val scope = AppScope()
        field("contextRef", scope)
        val connection = ReflectionHelpers.getStaticField<ServiceConnection>(InCallClusterBridge::class.java, "connection")
        connection.onBindingDied(null)
        assertEquals(1, scope.unbinds)
        assertEquals(1, scope.binds)
        assertFalse(InCallClusterBridge.reportsProjecting())
        InCallClusterBridge.observeCallback(scope)
        assertEquals(1, scope.binds)
    }

    @Test fun offDisplayRejectsActivationAndLayoutCommands() {
        InCallClusterBridge.observeDisplayAvailable(false)
        InCallClusterBridge.openProjection(app, automatic = true)
        val control = ReflectionHelpers.getStaticField<ProjectionControl>(InCallClusterBridge::class.java, "projectionControl")
        assertFalse(control.requestLayout(ProjectionLayout.FULLSCREEN))
        idle(60000)
        assertTrue(calls.isEmpty())
    }

    @Test fun localInvalidationDropsStaleCallbacksWithoutVehicleCommandOrUnbinding() {
        val beforeBinder = ReflectionHelpers.getStaticField<IBinder>(InCallClusterBridge::class.java, "binder")
        InCallClusterBridge.invalidateProjectionChannel()
        assertFalse(InCallClusterBridge.reportsProjecting())
        assertTrue(callbacks.isEmpty())
        assertSame(beforeBinder, ReflectionHelpers.getStaticField<IBinder>(InCallClusterBridge::class.java, "binder"))
        assertTrue(ReflectionHelpers.getStaticField<Boolean>(InCallClusterBridge::class.java, "bound"))
        idle(60000)
        assertTrue(calls.isEmpty())
    }

    @Test fun serviceDeathAdvancesEpochAndInvalidatesOldConfirmation() {
        val before = InCallClusterBridge.serviceEpoch()
        val connection = ReflectionHelpers.getStaticField<ServiceConnection>(InCallClusterBridge::class.java, "connection")
        connection.onServiceDisconnected(null)
        assertTrue(InCallClusterBridge.serviceEpoch() > before)
        assertFalse(InCallClusterBridge.serviceConnected())
        assertFalse(InCallClusterBridge.reportsProjecting())
        assertTrue(callbacks.isEmpty())
        connection.onServiceConnected(null, fake)
        idle(60000)
        assertTrue(InCallClusterBridge.serviceConnected())
        assertTrue(calls.none { it.first in setOf(1, 8, 21) })
    }
    @Test fun explicitAppExitBlocksLateActivationLayoutAndContentCallbacks() {
        VehicleCustomizations.register(object : VehicleCustomization {
            override val id = "stopped-app"
            override fun allowAutomaticStartup(context: Context) = false
        })
        InCallClusterBridge.openProjection(app, automatic = true)
        val control = ReflectionHelpers.getStaticField<ProjectionControl>(InCallClusterBridge::class.java, "projectionControl")
        assertFalse(control.requestLayout(ProjectionLayout.FULLSCREEN))
        var result: Boolean? = null
        InCallClusterBridge.requestContentHandoff(app, Any()) { result = it }
        assertEquals(false, result)
        idle(60000)
        assertTrue(calls.isEmpty())
    }
}
