package com.freddyq.diplay.changan.projection

import android.os.Handler
import android.os.Looper
import com.freddyq.diplay.customization.ProjectionContentControl
import java.lang.ref.WeakReference

/** Independent of LVDS layout: global full-screen requests cannot complete content handoff. */
internal class ContentHandoffRecovery(
    private val diagnostic: (String) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private var owner: WeakReference<Any>? = null
    private var dispatchedWindow: WeakReference<Any>? = null
    private var budgetWindow: WeakReference<Any>? = null
    private var pending: Runnable? = null
    private var generation = 0L
    private var attempts = 0
    private var suspended = false

    fun frameReady(window: Any, endpoint: ProjectionContentControl) {
        if (suspended || dispatchedWindow?.get() === window || owner?.get() === window) return
        if (budgetWindow?.get() !== window) cancel() else cancelPending()
        budgetWindow = WeakReference(window)
        owner = WeakReference(window)
        if (attempts >= MAX_ATTEMPTS) return
        val ticket = generation
        fun dispatch() {
            if (ticket != generation || owner?.get() !== window) return
            pending = null
            attempts++
            val attempt = attempts
            diagnostic("content handoff attempt=$attempts/$MAX_ATTEMPTS; local video frame ready")
            fun result(sent: Boolean) {
                handler.post {
                    if (ticket != generation || owner?.get() !== window || attempt != attempts || dispatchedWindow?.get() === window) return@post
                    pending?.let(handler::removeCallbacks)
                    pending = null
                    if (sent) {
                        dispatchedWindow = WeakReference(window)
                        diagnostic("content source handshake dispatched; physical picture requires vehicle verification")
                    } else if (attempts < MAX_ATTEMPTS) {
                        pending = Runnable { dispatch() }.also { handler.postDelayed(it, RETRY_MS) }
                    } else {
                        diagnostic("content handoff budget exhausted")
                    }
                }
            }
            pending = Runnable { result(false) }.also { handler.postDelayed(it, RETRY_MS) }
            try { endpoint.requestHandoff(::result) } catch (_: Exception) { result(false) }
        }
        pending = Runnable { dispatch() }.also { handler.postDelayed(it, SETTLE_MS) }
    }

    fun windowLost(window: Any) {
        if (budgetWindow?.get() === window) cancel()
        if (dispatchedWindow?.get() === window) dispatchedWindow = null
    }

    /** A successful IPC write is only a deduplication marker until the source is confirmed. */
    fun confirmationLost(window: Any) {
        if (suspended || owner?.get() !== window) return
        cancelPending()
        if (dispatchedWindow?.get() === window) dispatchedWindow = null
        diagnostic("content source unconfirmed; remaining attempts=${MAX_ATTEMPTS - attempts}")
    }

    private fun cancelPending() {
        generation++
        pending?.let(handler::removeCallbacks)
        pending = null
        owner = null
    }

    fun cancel() {
        cancelPending()
        attempts = 0
        budgetWindow = null
    }

    fun suspend() { cancel(); suspended = true }
    fun resume() { suspended = false }
    fun invalidate() { cancel(); dispatchedWindow = null }

    companion object {
        const val SETTLE_MS = 1_500L
        const val RETRY_MS = 5_000L
        const val MAX_ATTEMPTS = 2
    }
}
