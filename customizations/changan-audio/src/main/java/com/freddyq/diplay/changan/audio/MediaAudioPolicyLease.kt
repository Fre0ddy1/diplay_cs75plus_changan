package com.freddyq.diplay.changan.audio

import android.os.Handler
import android.os.Looper
import java.io.Closeable

/** Only A2DP policy is owned. HFP, pairing, phonebooks and other peers are untouched. */
internal interface MediaAudioPolicyPort : Closeable {
    fun start(ready: () -> Unit): Boolean
    fun priority(address: String): Int?
    fun setPriority(address: String, value: Int): Boolean
    fun disconnectMedia(address: String): Boolean
}

internal data class SavedMediaPolicy(val address: String, val priority: Int)
internal interface MediaPolicyJournal {
    fun read(): SavedMediaPolicy?
    fun save(value: SavedMediaPolicy): Boolean
    fun clear(): Boolean
}

/** Main-looper lease. Persist before changing a policy so a later process can restore it. */
internal class MediaAudioPolicyLease(
    private val open: () -> MediaAudioPolicyPort,
    private val journal: MediaPolicyJournal,
    private val report: (String) -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private data class Request(val owner: Any, val address: String)
    private var wanted: Request? = null
    private var appliedOwner: Any? = null
    private var enabled = true
    private var port: MediaAudioPolicyPort? = null
    private var ready = false
    private val exitWaiters = mutableListOf<() -> Unit>()
    private var exiting = false

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post(action)
    }
    fun recover() = onMain { if (journal.read() != null) ensurePort() }
    fun begin(owner: Any, address: String) = onMain {
        if (exiting || (wanted?.owner === owner && wanted?.address == address)) return@onMain
        wanted = Request(owner, address)
        if (enabled || journal.read() != null) ensurePort()
    }
    fun end(owner: Any) = onMain {
        if (wanted?.owner !== owner) return@onMain
        wanted = null
        appliedOwner = null
        reconcileOrBind()
    }
    fun configure(value: Boolean) = onMain {
        enabled = value
        if (!value) appliedOwner = null
        reconcileOrBind()
    }
    fun prepareExit(completed: () -> Unit) = onMain {
        exiting = true
        wanted = null
        appliedOwner = null
        exitWaiters += completed
        // Existing app shutdown has its own outer deadline. Never hold it indefinitely.
        handler.postDelayed(::finishExitWaiters, 1_500L)
        reconcileOrBind()
    }
    private fun reconcileOrBind() {
        if (journal.read() == null && (wanted == null || !enabled || exiting)) {
            port?.close(); port = null; ready = false
            finishExitWaiters()
            return
        }
        if (port != null && ready) reconcile()
        else if (journal.read() != null || (enabled && wanted != null)) ensurePort()
        else finishExitWaiters()
    }
    private fun ensurePort() {
        if (port != null) { if (ready) reconcile(); return }
        val candidate = open()
        port = candidate
        val accepted = runCatching { candidate.start {
            onMain { if (port === candidate) { ready = true; reconcile() } }
        } }.getOrDefault(false)
        if (!accepted) {
            report("media audio policy service unavailable; original policy kept")
            if (port === candidate) { port = null; ready = false; candidate.close() }
            finishExitWaiters()
        } else handler.postDelayed({
            if (port === candidate && !ready) {
                report("media audio policy bind timed out; restoration journal retained")
                port = null; candidate.close()
                finishExitWaiters()
            }
        }, 1_500L)
    }
    private fun reconcile() {
        val connection = port ?: return
        val target = wanted.takeIf { enabled && !exiting }
        val saved = journal.read()
        if (saved != null && (target == null || appliedOwner !== target.owner)) {
            val current = connection.priority(saved.address)
            when {
                current == null -> report("media audio restore unconfirmed; journal retained")
                current != 0 -> {
                    // A user/native settings change after our write wins. Do not overwrite it.
                    if (journal.clear()) report("media audio policy changed externally; preserved")
                }
                connection.setPriority(saved.address, saved.priority) &&
                    connection.priority(saved.address) == saved.priority -> {
                    if (journal.clear()) report("media audio original policy restored=${saved.priority}")
                }
                else -> report("media audio restore failed; journal retained")
            }
            if (journal.read() != null) { finishExitWaiters(); return }
        }
        if (target != null && appliedOwner !== target.owner) {
            val previous = connection.priority(target.address)
            if (previous !in setOf(0, 100, 1000)) {
                report("media audio original policy unavailable; no write")
                return
            }
            appliedOwner = target.owner
            if (previous == 0) report("media audio reports disabled; no restore write owned")
            else if (!journal.save(SavedMediaPolicy(target.address, previous!!))) {
                report("media audio journal unavailable; no write")
            } else {
                val accepted = connection.setPriority(target.address, 0)
                val confirmed = connection.priority(target.address) == 0
                if (accepted && confirmed) {
                    // Setting PRIORITY_OFF blocks reconnects. Only release this existing media link once.
                    val disconnected = connection.disconnectMedia(target.address)
                    report("media audio disabled; accepted=$accepted readback=true disconnectRequested=$disconnected")
                } else report("media audio disable unconfirmed; original policy journal retained")
            }
        }
        if (target == null) {
            port = null; ready = false
            connection.close()
        }
        finishExitWaiters()
    }
    private fun finishExitWaiters() {
        val callbacks = exitWaiters.toList()
        exitWaiters.clear()
        callbacks.forEach { it() }
    }
}
