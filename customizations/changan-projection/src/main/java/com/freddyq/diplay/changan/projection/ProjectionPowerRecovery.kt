package com.freddyq.diplay.changan.projection

import com.freddyq.diplay.customization.ProjectionEnvironment

/** Fresh power/service generations get a fresh bounded budget; an unchanged failure does not. */
internal class ProjectionPowerRecovery {
    enum class Action { WAIT, INVALIDATE, ACTIVATE }
    private var previous: ProjectionEnvironment? = null
    private var invalidate = false
    private var nextAt = 0L
    private var attempts = 0
    fun observe(state: ProjectionEnvironment, now: Long): Action {
        val old = previous
        previous = state
        val changed = old != null && (old.displayId != state.displayId || old.displayOn != state.displayOn ||
            old.serviceEpoch != state.serviceEpoch || old.resumeEpoch != state.resumeEpoch)
        val manual = old != null && old.manualRequest != state.manualRequest
        if (old == null || changed || manual || (old.phoneConnected != state.phoneConnected)) {
            attempts = 0
            nextAt = now + if (manual) 0L else SETTLE_MS
            if (changed) invalidate = true
        }
        if (invalidate) return Action.INVALIDATE
        if (!state.phoneConnected || !state.displayOn || !state.sizeReady || !state.serviceConnected || state.userClosed) return Action.WAIT
        if (!state.automaticEnabled && state.manualRequest == 0L) return Action.WAIT
        if (state.channelOpen) { attempts = 0; return Action.WAIT }
        return if (attempts < DELAYS.size && now >= nextAt) Action.ACTIVATE else Action.WAIT
    }
    fun invalidated() { invalidate = false }
    fun exhausted(): Boolean = attempts >= DELAYS.size
    fun activated(now: Long) {
        nextAt = now + DELAYS[attempts.coerceAtMost(DELAYS.lastIndex)]
        attempts++
    }
    fun clear() { previous = null; invalidate = false; attempts = 0; nextAt = 0L }
    companion object {
        const val SETTLE_MS = 1_000L
        private val DELAYS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L)
    }
}
