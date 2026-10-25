package com.freddyq.diplay.changan.projection

import com.freddyq.diplay.customization.ProjectionLayout

/** One correction after a real small-screen request, even if activation happened long before. */
internal class StartupFullscreenPolicy {
    private var generation = 0L
    private var armed = false
    private var pending: Long? = null
    val hasPending get() = pending != null

    fun start(automatic: Boolean, enabled: Boolean) {
        cancel()
        armed = automatic && enabled
    }

    fun layoutRequested(layout: ProjectionLayout): Long? {
        if (!armed) return null
        if (layout == ProjectionLayout.FULLSCREEN) {
            cancel()
            return null
        }
        if (pending != null) return null
        return (++generation).also { pending = it }
    }

    fun claim(ticket: Long, enabled: Boolean): Boolean {
        if (!enabled) { cancel(); return false }
        if (!armed || pending != ticket) return false
        // Consume before dispatch, including a failed dispatch: never cycle through driver modes.
        cancel()
        return true
    }

    fun cancel() {
        generation++
        armed = false
        pending = null
    }

    companion object { const val SETTLE_MS = 1_500L }
}
