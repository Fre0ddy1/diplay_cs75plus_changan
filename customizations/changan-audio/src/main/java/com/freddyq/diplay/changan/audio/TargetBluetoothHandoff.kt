package com.freddyq.diplay.changan.audio

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable

internal enum class LinkState { UNKNOWN, DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }
internal interface TargetBluetoothLink : Closeable {
    fun start(changed: () -> Unit)
    fun states(): Map<Int, LinkState>
    fun canDisconnect(): Boolean
    fun supportsDisconnect(profile: Int): Boolean = true
    fun disconnect(profile: Int): Boolean
}

/** All state lives on the main looper; the port is bound to exactly one peer. */
internal class TargetBluetoothHandoff(
    private val open: (String) -> TargetBluetoothLink,
    private val report: (String) -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private class Lease(val owner: Any, val link: TargetBluetoothLink) {
        var checking = false
        var deadline = 0L
        var budget = 20
        val attempts = mutableMapOf<Int, Int>()
        val lastRequest = mutableMapOf<Int, Long>()
        val waiters = mutableListOf<(Boolean) -> Unit>()
        var lastResult: String? = null
    }
    private var lease: Lease? = null
    private fun onMain(work: () -> Unit) {
        if (Looper.myLooper() == handler.looper) work() else handler.post(work)
    }
    fun begin(owner: Any, address: String) = onMain {
        if (lease?.owner === owner) return@onMain
        lease?.let { lease = null; retire(it) }
        val link = try { open(address) } catch (error: RuntimeException) {
            report("target profile initialization unavailable=${error.javaClass.simpleName}")
            return@onMain
        }
        val next = Lease(owner, link)
        lease = next
        next.link.start { onMain {
            if (lease === next && !next.checking && next.budget > 0) beginCheck(next)
        } }
        report("handoff started; selected peer only")
        beginCheck(next)
    }
    fun end(owner: Any) = onMain {
        lease?.takeIf { it.owner === owner }?.let {
            lease = null
            retire(it)
            report("handoff ended; native connection policy resumed")
        }
    }
    /** Called on the main looper by the customization endpoint. No automatic PLAY is generated. */
    fun prepare(completed: (Boolean) -> Unit): Boolean {
        check(Looper.myLooper() == handler.looper)
        val current = lease ?: return false
        current.waiters += completed
        if (!current.checking) {
            // A new user action may retry a previously exhausted automatic budget.
            current.budget = 20
            beginCheck(current)
        }
        return true
    }
    private fun retire(old: Lease) {
        handler.removeCallbacksAndMessages(old)
        old.link.close()
        val waiters = old.waiters.toList()
        old.waiters.clear()
        waiters.forEach { it(false) }
    }
    private fun beginCheck(current: Lease) {
        if (current.checking) return
        current.checking = true
        current.deadline = SystemClock.uptimeMillis() + 2_000L
        current.attempts.clear()
        current.lastRequest.clear()
        checkLinks(current)
    }
    private fun checkLinks(current: Lease) {
        if (lease !== current) return
        val now = SystemClock.uptimeMillis()
        val states = current.link.states()
        if (states.isNotEmpty() && states.values.all { it == LinkState.DISCONNECTED }) {
            finish(current, true, "profiles disconnected; physical phone output still unverified")
            return
        }
        if (now >= current.deadline || current.budget <= 0) {
            finish(current, false, "handoff incomplete states=$states; no global Bluetooth fallback")
            return
        }
        if (current.link.canDisconnect()) {
            states.forEach { (profile, state) ->
                if (state !in setOf(LinkState.CONNECTED, LinkState.CONNECTING) ||
                    !current.link.supportsDisconnect(profile)) return@forEach
                val count = current.attempts[profile] ?: 0
                val last = current.lastRequest[profile] ?: -1_000L
                if (count >= 3 || now - last < 500L || current.budget <= 0) return@forEach
                current.attempts[profile] = count + 1
                current.lastRequest[profile] = now
                current.budget--
                val accepted = current.link.disconnect(profile)
                report("target profile=$profile disconnect accepted=$accepted attempt=${count + 1}")
            }
        }
        handler.postAtTime({ checkLinks(current) }, current, now + 200L)
    }
    private fun finish(current: Lease, success: Boolean, message: String) {
        current.checking = false
        if (current.lastResult != message) report(message)
        current.lastResult = message
        val waiters = current.waiters.toList()
        current.waiters.clear()
        waiters.forEach { it(success) }
    }
}
