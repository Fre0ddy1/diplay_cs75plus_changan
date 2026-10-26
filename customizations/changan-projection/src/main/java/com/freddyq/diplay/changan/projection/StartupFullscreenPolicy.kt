package com.freddyq.diplay.changan.projection

import com.freddyq.diplay.customization.ProjectionLayout

/** A small request starts bounded recovery; only a full-layout response completes it. */
internal class StartupFullscreenPolicy {
    private var generation = 0L
    private var armed = false
    private var pending: Long? = null
    private var awaitingConfirmation = false
    var attempts = 0
        private set
    val hasPending get() = pending != null || awaitingConfirmation

    fun start(automatic: Boolean, enabled: Boolean) {
        cancel()
        attempts = 0
        armed = automatic && enabled
    }

    fun layoutRequested(layout: ProjectionLayout): Long? {
        if (!armed) return null
        if (layout == ProjectionLayout.FULLSCREEN) {
            cancel()
            return null
        }
        return requestConfirmation()
    }

    /** Reconfirm a retained layout without inventing a small-screen callback. */
    fun requestConfirmation(): Long? {
        if (!armed || pending != null || awaitingConfirmation || attempts > 0) return null
        return (++generation).also { pending = it }
    }

    fun claim(ticket: Long, enabled: Boolean): Boolean {
        if (!enabled) { cancel(); return false }
        if (!armed || pending != ticket) return false
        pending = null
        awaitingConfirmation = true
        attempts++
        return true
    }

    fun confirmationTimedOut(enabled: Boolean): Long? {
        if (!enabled || !armed || !awaitingConfirmation || attempts >= MAX_ATTEMPTS) {
            cancel()
            return null
        }
        awaitingConfirmation = false
        return (++generation).also { pending = it }
    }

    fun cancel() {
        generation++
        armed = false
        pending = null
        awaitingConfirmation = false
    }

    companion object {
        const val SETTLE_MS = 1_500L
        const val CONFIRMATION_MS = 2_500L
        const val MAX_ATTEMPTS = 3
    }
}
