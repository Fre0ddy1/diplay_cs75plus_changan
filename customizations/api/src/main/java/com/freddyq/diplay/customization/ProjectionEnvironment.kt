package com.freddyq.diplay.customization

import android.content.Intent

/** Observed platform state only; no vehicle properties or protocol values. */
data class ProjectionEnvironment(
    val displayId: Int?, val displayOn: Boolean, val sizeReady: Boolean,
    val serviceEpoch: Long, val serviceConnected: Boolean, val channelOpen: Boolean,
    val phoneConnected: Boolean, val automaticEnabled: Boolean, val userClosed: Boolean,
    val manualRequest: Long,
    val resumeEpoch: Long = 0L,
)
interface ProjectionActivationControl {
    /** Discards local acknowledgements, never sends a vehicle command. */
    fun invalidateChannel(): Boolean
    /** True means activation was dispatched, never that pixels were observed. */
    fun activate(): Boolean
    /** Launch a module-selected prerequisite without changing the current phone session. */
    fun launchPrerequisite(intent: Intent): Boolean = false
    /** Return to the same host and refresh its video window; never replace the phone session. */
    fun returnToProjection(): Boolean = false
}
