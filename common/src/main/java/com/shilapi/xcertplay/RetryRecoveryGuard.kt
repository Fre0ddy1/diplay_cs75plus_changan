package com.shilapi.xcertplay

/** Upstream #876: recovery invalidates an older retry only until another real loss occurs. UI thread only. */
internal class RetryRecoveryGuard {
    private var runs = 0L
    private var lossSinceRunning = false
    fun running() { runs++; lossSinceRunning = false }
    fun lost() { lossSinceRunning = true }
    fun checkpoint(): Long = runs
    fun recoveredSince(checkpoint: Long): Boolean = runs != checkpoint && !lossSinceRunning
}
