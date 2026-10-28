package com.freddyq.diplay.changan.projection

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.freddyq.diplay.customization.ProjectionActivationControl
import com.freddyq.diplay.customization.ProjectionEnvironment
import com.freddyq.diplay.customization.VehicleCustomizations

internal class ProjectionEnvironmentBridge {
    private val main = Handler(Looper.getMainLooper())
    private val policy = ProjectionPowerRecovery()
    private var generation = 0L
    private var pending: Pair<ProjectionEnvironment, ProjectionActivationControl>? = null
    private var queued = false
    @Synchronized fun observe(state: ProjectionEnvironment, control: ProjectionActivationControl) {
        pending = state to control
        if (queued) return
        queued = true
        val ticket = generation
        main.post { synchronized(this) {
            if (ticket != generation) return@synchronized
            queued = false
            val (latest, endpoint) = pending ?: return@synchronized
            pending = null
            val now = SystemClock.elapsedRealtime()
            when (policy.observe(latest, now)) {
                ProjectionPowerRecovery.Action.INVALIDATE -> if (endpoint.invalidateChannel()) {
                    policy.invalidated()
                    VehicleCustomizations.diagnostic("ChanganProjection", "power/service changed display=${latest.displayId} on=${latest.displayOn} sizeReady=${latest.sizeReady} serviceEpoch=${latest.serviceEpoch} resumeEpoch=${latest.resumeEpoch}; stale channel discarded locally")
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
        generation++; pending = null; queued = false; policy.clear()
    }
}
