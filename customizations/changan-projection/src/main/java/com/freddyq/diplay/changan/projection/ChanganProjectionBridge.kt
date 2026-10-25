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
    private var automaticStartup = false
    fun shouldRetryAutomaticProjection(): Boolean = !startupSettled

    fun start(context: Context, automatic: Boolean, endpoint: ProjectionControl) = onMain {
        clearPending()
        app = context.applicationContext
        control = endpoint
        val enabled = ChanganProjectionSettings.enabled(context)
        startupSettled = false
        automaticStartup = automatic && enabled
        policy.start(automatic, enabled)
        diagnostic("startup automatic=$automatic enabled=$enabled; waiting for layout request")
    }

    fun layoutRequested(layout: ProjectionLayout) = onMain {
        if (automaticStartup && layout == ProjectionLayout.FULLSCREEN) startupSettled = true
        val ticket = policy.layoutRequested(layout)
        if (!policy.hasPending) clearPending()
        if (ticket != null) {
            diagnostic("small-screen request; one fullscreen correction after ${StartupFullscreenPolicy.SETTLE_MS}ms")
            pending = Runnable { onMain {
                pending = null
                val context = app ?: return@onMain
                if (policy.claim(ticket, ChanganProjectionSettings.enabled(context))) {
                    val dispatched = control?.requestLayout(ProjectionLayout.FULLSCREEN) == true
                    if (dispatched) startupSettled = true
                    diagnostic("fullscreen correction dispatched=$dispatched; physical confirmation still required")
                }
            } }.also { handler.postDelayed(it, StartupFullscreenPolicy.SETTLE_MS) }
        }
    }

    fun clear() = onMain {
        clearPending()
        policy.cancel()
        app = null
        control = null
        startupSettled = false
        automaticStartup = false
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
