package com.shilapi.xcertplay.iap2.message

/** Optional side channel; it never changes the existing maneuver or HUD state. */
internal class LaneGuidanceUpdates(private val enabled: Boolean, private val changed: (List<LaneGuidanceLane>) -> Unit) {
    private var last: List<LaneGuidanceLane>? = null
    fun accept(messageId: Int, payload: ByteArray) {
        if (!enabled || messageId != 0x5204) return
        val next = Iap2LaneGuidance.parse(payload).take(8)
        synchronized(this) {
            if (next == last) return
            last = next
        }
        changed(next)
    }
    @Synchronized fun clear() { last = null }
}
