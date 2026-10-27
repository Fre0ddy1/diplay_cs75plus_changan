package com.freddyq.diplay.changan.audio

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class TargetBluetoothHandoffTest {
    private class Link : TargetBluetoothLink {
        val state = mutableMapOf(11 to LinkState.CONNECTED, 16 to LinkState.CONNECTED)
        val requests = mutableListOf<Int>()
        var changed: () -> Unit = {}
        var closed = false
        var call = false
        var accepts = true
        override fun start(changed: () -> Unit) { this.changed = changed }
        override fun states() = state.toMap()
        override fun canDisconnect() = !call
        override fun supportsDisconnect(profile: Int) = profile != 12
        override fun disconnect(profile: Int): Boolean { requests += profile; return accepts }
        override fun close() { closed = true }
        fun disconnected() { state.keys.toList().forEach { state[it] = LinkState.DISCONNECTED }; changed() }
    }
    private val links = mutableMapOf<String, Link>()
    private val report = mutableListOf<String>()
    private val bridge = TargetBluetoothHandoff({ address -> links.getOrPut(address) { Link() } }, { report += it })
    private val owner = Any()
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    private fun start(address: String = "selected"): Link {
        bridge.begin(owner, address)
        idle()
        return links.getValue(address)
    }
    @Test fun acceptedDisconnectIsNotProofOfDisconnection() {
        val link = start()
        val results = mutableListOf<Boolean>()
        assertTrue(bridge.prepare { results += it })
        assertTrue(results.isEmpty())
        link.disconnected(); idle(200)
        assertEquals(listOf(true), results)
    }
    @Test fun onlyTheSelectedPeerIsOpened() {
        links["other-phone"] = Link()
        start()
        assertTrue(links.getValue("other-phone").requests.isEmpty())
        assertEquals(setOf(11,16), links.getValue("selected").requests.toSet())
    }
    @Test fun activeCallPreventsProfileDisconnects() {
        links["selected"] = Link().apply { call = true }
        val link = start(); idle(2200)
        assertTrue(link.requests.isEmpty())
    }
    @Test fun failedOrUnknownInterfaceTimesOutWithoutClaimingSuccess() {
        links["selected"] = Link().apply { state[11] = LinkState.UNKNOWN; state[16] = LinkState.UNKNOWN }
        start()
        val results = mutableListOf<Boolean>()
        bridge.prepare { results += it }; idle(2200)
        assertEquals(listOf(false), results)
        assertTrue(links.getValue("selected").requests.isEmpty())
    }
    @Test fun retriesHavePerCycleAndSessionBounds() {
        val link = start(); idle(2200)
        assertEquals(3, link.requests.count { it == 11 })
        repeat(20) { link.changed(); idle(2200) }
        assertTrue(link.requests.size <= 20)
    }
    @Test fun reconnectionIsCheckedWhileLeaseIsAlive() {
        val link = start(); link.disconnected(); idle(200)
        val before = link.requests.size
        link.state[11] = LinkState.CONNECTED; link.changed(); idle()
        assertEquals(before+1, link.requests.size)
    }
    @Test fun oldEndDoesNotStopNewPeer() {
        val old = start()
        bridge.begin(Any(), "second"); idle()
        bridge.end(owner); idle()
        assertTrue(old.closed)
        assertFalse(links.getValue("second").closed)
    }
    @Test fun lateCallbackFromRetiredLinkCannotDisconnectNewPeer() {
        val old = start()
        bridge.begin(Any(), "second"); idle()
        val before = links.getValue("second").requests.size
        old.changed(); idle()
        assertEquals(before, links.getValue("second").requests.size)
    }
    @Test fun endCancelsPendingWorkAndCallbacks() {
        val link = start()
        val results = mutableListOf<Boolean>()
        bridge.prepare { results += it }
        val before = link.requests.size
        bridge.end(owner); idle(10_000)
        assertTrue(link.closed)
        assertEquals(before, link.requests.size)
        assertEquals(listOf(false), results)
        assertFalse(bridge.prepare {})
    }
    @Test fun coalescedUserRequestsCompleteInOrder() {
        val link = start()
        val order = mutableListOf<Int>()
        bridge.prepare { order += 1 }; bridge.prepare { order += 2 }
        link.disconnected(); idle(200)
        assertEquals(listOf(1,2), order)
    }
    @Test fun passiveAvrcpIsVerifiedWithoutInventingADisconnectApi() {
        links["selected"] = Link().apply { state[12] = LinkState.CONNECTED }
        val link = start()
        assertFalse(link.requests.contains(12))
        link.disconnected(); idle(200)
        val results = mutableListOf<Boolean>()
        bridge.prepare { results += it }
        assertEquals(listOf(true), results)
    }
    @Test fun unavailablePlatformDoesNotLeaveADeadLeaseOrCrashInitialization() {
        val unavailable = TargetBluetoothHandoff({ throw SecurityException("denied") }, {})
        unavailable.begin(Any(), "selected"); idle()
        assertFalse(unavailable.prepare {})
    }
}
