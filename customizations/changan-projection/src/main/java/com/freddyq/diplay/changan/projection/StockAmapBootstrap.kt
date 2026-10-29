package com.freddyq.diplay.changan.projection

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.freddyq.diplay.customization.ProjectionActivationControl
import com.freddyq.diplay.customization.ProjectionEnvironment
import com.freddyq.diplay.customization.VehicleCustomizations

/** A live background service is not proof that the stock map initialized its external screen. */
internal class StockAmapBootstrap(private val onReturned: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var preparedService: Long? = null
    private var attempted = false
    @Volatile private var pending: Runnable? = null
    private var ticket = 0L
    private var endpoint: ProjectionActivationControl? = null
    private var preparingService: Long? = null
    private var observedService: Long? = null

    val waiting: Boolean get() = pending != null

    /** False holds activation/content handoff while the prerequisite owns the foreground. */
    @Synchronized fun ready(context: Context, state: ProjectionEnvironment, control: ProjectionActivationControl): Boolean {
        val eligible = state.phoneConnected && state.displayOn && state.sizeReady && state.serviceConnected &&
            !state.userClosed && (state.automaticEnabled || state.manualRequest != 0L)
        if (!eligible) { cancel(); return false }
        if (observedService != state.serviceEpoch) {
            cancel()
            attempted = false
            preparedService = null
            observedService = state.serviceEpoch
        }
        if (waiting) {
            if (preparingService != state.serviceEpoch) cancel() else {
                endpoint = control
                return false
            }
        }
        if (state.channelOpen) { preparedService = state.serviceEpoch; return true }
        if (preparedService == state.serviceEpoch || attempted) return true
        val intent = runCatching { context.packageManager.getLaunchIntentForPackage(PACKAGE) }.getOrNull()
        attempted = true
        if (intent == null) { log("stock map launcher unavailable; bounded direct recovery remains"); return true }
        // Cancel any already queued source handshake before yielding the foreground.
        if (!control.invalidateChannel()) return false
        // invalidateChannel notifies module release hooks, which cancel stale endpoints.
        endpoint = control
        if (!control.launchPrerequisite(intent)) {
            endpoint = null
            log("stock map launch rejected; bounded direct recovery remains")
            return true
        }
        preparingService = state.serviceEpoch
        val generation = ++ticket
        log("stock map initialization requested; holding projection activation")
        pending = Runnable {
            synchronized(this) {
                if (generation != ticket) return@Runnable
                pending = null
                val current = endpoint ?: return@Runnable
                val service = preparingService
                endpoint = null
                preparingService = null
                if (current.returnToProjection()) {
                    preparedService = service
                    log("stock map foreground returned; rearm current-session recovery")
                    onReturned()
                } else log("stock map return cancelled; projection owner no longer current")
            }
        }.also { main.postDelayed(it, RETURN_DELAY_MS) }
        return false
    }

    /** One fallback after a formerly prepared service stops confirming, not an endless launch loop. */
    @Synchronized fun retryAfterExhaustion() { if (!attempted) preparedService = null }
    @Synchronized fun cancel() {
        ticket++
        pending?.let(main::removeCallbacks)
        pending = null
        endpoint = null
        preparingService = null
    }
    @Synchronized fun sessionEnded() { cancel(); attempted = false }
    private fun log(message: String) = VehicleCustomizations.diagnostic("ChanganProjection", message)
    companion object {
        const val PACKAGE = "com.autonavi.amapauto"
        const val RETURN_DELAY_MS = 2_000L
    }
}
