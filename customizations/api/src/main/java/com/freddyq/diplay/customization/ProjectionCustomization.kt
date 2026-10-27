package com.freddyq.diplay.customization

/** Logical layout only: vendor LVDS/broadcast values stay in the existing bridge. */
enum class ProjectionLayout { CARD, FULLSCREEN }

/** Current window has received a local video frame; this is separate from global layout. */
fun interface ProjectionContentControl {
    /** Result reports protocol dispatch, never proof of pixels on the physical panel. */
    fun requestHandoff(onDispatched: (Boolean) -> Unit)
}

interface ProjectionControl {
    /** True means the request was dispatched, not that the physical display acknowledged it. */
    fun requestLayout(layout: ProjectionLayout): Boolean
    /** Drop an unconfirmed cached channel after bounded recovery; never sends a device command. */
    fun invalidateConfirmation() {}
}
