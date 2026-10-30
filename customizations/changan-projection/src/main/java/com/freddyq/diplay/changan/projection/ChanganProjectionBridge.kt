package com.freddyq.diplay.changan.projection

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.freddyq.diplay.customization.ProjectionControl
import com.freddyq.diplay.customization.ProjectionLayout
import com.freddyq.diplay.customization.VehicleCustomizations

/** Policy only. The existing projection bridge owns the sole vendor Binder connection. */
internal object ChanganProjectionBridge {
    private const val TAG = "DiPlay-Changan-Projection"
    private val handler = Handler(Looper.getMainLooper())
    private val policy = StartupFullscreenPolicy()
    private var pending: Runnable? = null
    private var app: Context? = null
    private var control: ProjectionControl? = null
    @Volatile private var startupSettled = false
    @Volatile private var ownsRecovery = false
    private var automaticStartup = false
    private var reconfirming = false
    fun shouldRetryAutomaticProjection(): Boolean = !startupSettled && !ownsRecovery
    // Confirmed layout stops mode retries, but must not veto a later closed channel forever.
    // The 0.2.22 host calls this only while its channel watcher reports not projecting.
    fun shouldActivateProjectionChannel(): Boolean = !ownsRecovery || startupSettled

    fun start(context: Context, automatic: Boolean, endpoint: ProjectionControl) = onMain {
        clearPending()
        app = context.applicationContext
        control = endpoint
        val enabled = ChanganProjectionSettings.enabled(context)
        startupSettled = false
        ownsRecovery = false
        automaticStartup = automatic && enabled
        reconfirming = false
        policy.start(automatic, enabled)
        diagnostic("startup automatic=$automatic enabled=$enabled; waiting for layout request")
    }

    fun resume(context: Context, endpoint: ProjectionControl) = onMain {
        start(context, true, endpoint)
        val ticket = policy.requestConfirmation() ?: return@onMain
        reconfirming = true
        ownsRecovery = true
        diagnostic("retained channel on new window; request fresh fullscreen confirmation")
        pending = Runnable { onMain { dispatch(ticket) } }
            .also { handler.postDelayed(it, StartupFullscreenPolicy.SETTLE_MS) }
    }

    fun layoutConfirmed(layout: ProjectionLayout) = onMain {
        reconfirming = false // The current source request can confirm before the delayed retry.
        layoutRequested(layout)
    }

    fun layoutRequested(layout: ProjectionLayout) = onMain {
        // A retained value delivered before our first request cannot acknowledge that request.
        if (reconfirming && policy.attempts == 0 && layout == ProjectionLayout.FULLSCREEN) return@onMain
        if (automaticStartup && layout == ProjectionLayout.FULLSCREEN) {
            startupSettled = true
            diagnostic("fullscreen layout confirmed; stop recovery after ${policy.attempts} attempts")
        }
        val ticket = policy.layoutRequested(layout)
        if (!policy.hasPending) clearPending()
        if (ticket != null) {
            diagnostic("small-screen request; bounded fullscreen recovery after ${StartupFullscreenPolicy.SETTLE_MS}ms")
            pending = Runnable { onMain { dispatch(ticket) } }
                .also { handler.postDelayed(it, StartupFullscreenPolicy.SETTLE_MS) }
        }
    }

    private fun dispatch(ticket: Long) {
        pending = null
        val context = app ?: return
        if (!policy.claim(ticket, ChanganProjectionSettings.enabled(context))) return
        ownsRecovery = true
        val dispatched = runCatching { control?.requestLayout(ProjectionLayout.FULLSCREEN) == true }
            .getOrElse { diagnostic("fullscreen endpoint failed: ${it.javaClass.simpleName}"); false }
        diagnostic("fullscreen attempt=${policy.attempts}/${StartupFullscreenPolicy.MAX_ATTEMPTS} dispatched=$dispatched; waiting for layout confirmation")
        // A synchronous endpoint may already have delivered confirmation.
        if (!policy.hasPending) return
        pending = Runnable { onMain {
            pending = null
            val next = policy.confirmationTimedOut(ChanganProjectionSettings.enabled(context))
            if (next != null) dispatch(next)
            else if (!startupSettled) {
                ownsRecovery = false
                if (reconfirming) {
                    runCatching { control?.invalidateConfirmation() }
                        .onFailure { diagnostic("confirmation invalidation failed: ${it.javaClass.simpleName}") }
                }
                diagnostic("fullscreen recovery exhausted; no confirmed success, bounded activation fallback remains available")
            }
        } }.also { handler.postDelayed(it, StartupFullscreenPolicy.CONFIRMATION_MS) }
    }

    fun clear(reason: String = "explicit") = onMain {
        if (app != null || pending != null) diagnostic("cancel startup recovery reason=$reason confirmed=$startupSettled attempts=${policy.attempts}")
        clearPending()
        policy.cancel()
        app = null
        control = null
        startupSettled = false
        ownsRecovery = false
        automaticStartup = false
        reconfirming = false
    }

    private fun clearPending() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    private fun onMain(action: () -> Unit) {
        val guarded = Runnable {
            try { action() } catch (error: Exception) {
                clearPending()
                policy.cancel()
                Log.w(TAG, "Projection customization failed", error)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) guarded.run() else handler.post(guarded)
    }

    private fun diagnostic(message: String) {
        Log.i(TAG, message)
        VehicleCustomizations.diagnostic("ChanganProjection", message)
    }
}
