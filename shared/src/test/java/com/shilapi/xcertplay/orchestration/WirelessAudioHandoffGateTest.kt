package com.shilapi.xcertplay.orchestration

import com.freddyq.diplay.customization.WirelessAudioConnection
import org.junit.Assert.*
import org.junit.Test

class WirelessAudioHandoffGateTest {
    private val ready = mutableListOf<WirelessAudioConnection>()
    private val ended = mutableListOf<Any>()
    private val gate = WirelessAudioHandoffGate({ ready += it }, { ended += it })
    private val first = Any()
    private fun establish(owner: Any = first) {
        gate.session(owner, "01:23:45:67:89:AB", false)
        gate.requested(owner); gate.tunnelReady(owner); gate.bootstrapReleased(owner)
    }
    @Test fun requiresPhoneCommandTunnelAndBootstrapRelease() {
        gate.session(first, "01:23:45:67:89:AB", false)
        gate.requested(first); gate.tunnelReady(first)
        assertTrue(ready.isEmpty())
        gate.bootstrapReleased(first)
        assertEquals("01:23:45:67:89:AB", ready.single().deviceAddress)
    }
    @Test fun repeatedReadinessDoesNotRepeatHandoff() {
        establish(); gate.requested(first); gate.tunnelReady(first); gate.bootstrapReleased(first)
        assertEquals(1, ready.size)
    }
    @Test fun lateOldTunnelCannotAuthenticateNewSession() {
        establish()
        val second = Any()
        gate.session(second, "01:23:45:67:89:AC", true)
        gate.requested(second); gate.tunnelReady(first)
        assertEquals(1, ready.size)
        gate.tunnelReady(second)
        assertEquals(2, ready.size)
        assertSame(first, ended.single())
    }
    @Test fun reconnectRequiresFreshPhoneCommandEvenWhenBootstrapWasClosed() {
        establish(); gate.close(first)
        val second = Any()
        gate.session(second, "01:23:45:67:89:AB", true)
        gate.tunnelReady(second)
        assertEquals(1, ready.size)
        gate.requested(second)
        assertEquals(2, ready.size)
    }
    @Test fun lateOldEndCannotReleaseNewOwner() {
        establish()
        val second = Any()
        establish(second); gate.close(first)
        assertEquals(listOf(first), ended)
        gate.close(second)
        assertEquals(listOf(first, second), ended)
    }
    @Test fun failedBootstrapDoesNotStartProfileIntervention() {
        gate.session(first, "01:23:45:67:89:AB", false)
        gate.requested(first); gate.close(first); gate.bootstrapReleased(first)
        assertTrue(ready.isEmpty()); assertTrue(ended.isEmpty())
    }
}
