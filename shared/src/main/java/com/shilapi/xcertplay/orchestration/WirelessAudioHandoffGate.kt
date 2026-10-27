package com.shilapi.xcertplay.orchestration

import com.freddyq.diplay.customization.WirelessAudioConnection

/** A new phone session must provide its own command and authenticated tunnel, even on reconnect. */
internal class WirelessAudioHandoffGate(
    private val ready: (WirelessAudioConnection) -> Unit,
    private val ended: (Any) -> Unit,
) {
    private var current: WirelessAudioConnection? = null
    private var requested = false
    private var tunnelReady = false
    private var bootstrapReleased = false
    private var notified = false
    @Synchronized fun session(owner: Any, address: String, bootstrapAlreadyReleased: Boolean) {
        if (current?.owner === owner) return
        close()
        current = WirelessAudioConnection(owner, address)
        bootstrapReleased = bootstrapAlreadyReleased
    }
    @Synchronized fun requested(owner: Any) {
        if (current?.owner !== owner) return
        requested = true
        dispatchIfReady()
    }
    @Synchronized fun tunnelReady(owner: Any) {
        if (current?.owner !== owner) return
        tunnelReady = true
        dispatchIfReady()
    }
    @Synchronized fun bootstrapReleased(owner: Any) {
        if (current?.owner !== owner) return
        bootstrapReleased = true
        dispatchIfReady()
    }
    @Synchronized fun close(owner: Any? = null) {
        val previous = current ?: return
        if (owner != null && previous.owner !== owner) return
        current = null
        val wasNotified = notified
        requested = false; tunnelReady = false; bootstrapReleased = false; notified = false
        if (wasNotified) ended(previous.owner)
    }
    private fun dispatchIfReady() {
        val connection = current ?: return
        if (!requested || !tunnelReady || !bootstrapReleased || notified) return
        notified = true
        ready(connection)
    }
}
