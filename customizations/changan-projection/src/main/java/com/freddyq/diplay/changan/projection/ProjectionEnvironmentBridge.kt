package com.freddyq.diplay.changan.projection

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.freddyq.diplay.customization.ProjectionActivationControl
import com.freddyq.diplay.customization.ProjectionEnvironment
import com.freddyq.diplay.customization.VehicleCustomizations

internal class ProjectionEnvironmentBridge(private val onPrerequisiteReturned: () -> Unit = {}) {
    private val main = Handler(Looper.getMainLooper())
    private val policy = ProjectionPowerRecovery()
    private val bootstrap = StockAmapBootstrap {
        policy.clear()
        onPrerequisiteReturned()
    }
    val prerequisiteWaiting: Boolean get() = bootstrap.waiting
    fun cancelPrerequisite() = bootstrap.cancel()
    private var generation = 0L
    private data class Observation(val state: ProjectionEnvironment, val control: ProjectionActivationControl, val context: Context)
    private var pending: Observation? = null
    private var queued = false
    @Synchronized fun observe(state: ProjectionEnvironment, control: ProjectionActivationControl, context: Context) {
        pending = Observation(state, control, context.applicationContext)
        if (queued) return
        queued = true
        val ticket = generation
        main.post { synchronized(this) {
            if (ticket != generation) return@synchronized
            queued = false
            val (latest, endpoint, app) = pending ?: return@synchronized
            pending = null
            val now = SystemClock.elapsedRealtime()
            val action = policy.observe(latest, now)
            // Power loss must still invalidate local state while a prerequisite is pending.
            if (action != ProjectionPowerRecovery.Action.INVALIDATE) {
                if (policy.exhausted() && !latest.channelOpen) bootstrap.retryAfterExhaustion()
                if (!bootstrap.ready(app, latest, endpoint)) return@synchronized
            }
            when (action) {
                ProjectionPowerRecovery.Action.INVALIDATE -> {
                    bootstrap.cancel()
                    if (endpoint.invalidateChannel()) {
                        policy.invalidated()
                        VehicleCustomizations.diagnostic("ChanganProjection", "power/service changed display=${latest.displayId} on=${latest.displayOn} sizeReady=${latest.sizeReady} serviceEpoch=${latest.serviceEpoch} resumeEpoch=${latest.resumeEpoch}; stale channel discarded locally")
                    }
                }
                ProjectionPowerRecovery.Action.ACTIVATE -> if (endpoint.activate()) {
                    policy.activated(now)
                    VehicleCustomizations.diagnostic("ChanganProjection", "bounded channel activation after power/service readiness")
                }
                ProjectionPowerRecovery.Action.WAIT -> Unit
            }
        } }
    }
    @Synchronized fun clear() {
        generation++; pending = null; queued = false; policy.clear(); bootstrap.sessionEnded()
    }
}
