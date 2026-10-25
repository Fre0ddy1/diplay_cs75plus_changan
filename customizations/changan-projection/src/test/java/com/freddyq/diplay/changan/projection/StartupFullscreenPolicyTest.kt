package com.freddyq.diplay.changan.projection

import com.freddyq.diplay.customization.ProjectionLayout
import org.junit.Assert.*
import org.junit.Test

class StartupFullscreenPolicyTest {
    private val policy = StartupFullscreenPolicy()
    @Test fun `idle callbacks cannot switch the cluster`() {
        assertNull(policy.layoutRequested(ProjectionLayout.CARD))
    }
    @Test fun `manual projection does not arm automatic promotion`() {
        policy.start(false,true)
        assertNull(policy.layoutRequested(ProjectionLayout.CARD))
    }
    @Test fun `disabled automatic projection does not arm promotion`() {
        policy.start(true,false)
        assertNull(policy.layoutRequested(ProjectionLayout.CARD))
    }
    @Test fun `first small request yields only one claim`() {
        policy.start(true,true)
        val ticket = policy.layoutRequested(ProjectionLayout.CARD)!!
        assertTrue(policy.claim(ticket,true))
        assertFalse(policy.claim(ticket,true))
    }
    @Test fun `duplicate small requests do not create more timers`() {
        policy.start(true,true)
        val ticket = policy.layoutRequested(ProjectionLayout.CARD)!!
        assertNull(policy.layoutRequested(ProjectionLayout.CARD))
        assertTrue(policy.claim(ticket,true))
    }
    @Test fun `already full projection leaves later manual small selection alone`() {
        policy.start(true,true)
        assertNull(policy.layoutRequested(ProjectionLayout.FULLSCREEN))
        assertNull(policy.layoutRequested(ProjectionLayout.CARD))
    }
    @Test fun `manual full request before correction invalidates timer`() {
        policy.start(true,true)
        val ticket = policy.layoutRequested(ProjectionLayout.CARD)!!
        policy.layoutRequested(ProjectionLayout.FULLSCREEN)
        assertFalse(policy.claim(ticket,true))
    }
    @Test fun `cancelled session cannot dispatch delayed request`() {
        policy.start(true,true)
        val ticket = policy.layoutRequested(ProjectionLayout.CARD)!!
        policy.cancel()
        assertFalse(policy.claim(ticket,true))
    }
    @Test fun `old session timer cannot consume new session request`() {
        policy.start(true,true)
        val old = policy.layoutRequested(ProjectionLayout.CARD)!!
        policy.start(true,true)
        val current = policy.layoutRequested(ProjectionLayout.CARD)!!
        assertFalse(policy.claim(old,true))
        assertTrue(policy.claim(current,true))
    }
    @Test fun `setting disabled before dispatch cancels pending correction`() {
        policy.start(true,true)
        val ticket = policy.layoutRequested(ProjectionLayout.CARD)!!
        assertFalse(policy.claim(ticket,false))
        assertFalse(policy.claim(ticket,true))
    }
    @Test fun `attempt is consumed even if endpoint fails`() {
        policy.start(true,true)
        val ticket = policy.layoutRequested(ProjectionLayout.CARD)!!
        assertTrue(policy.claim(ticket,true))
        assertNull(policy.layoutRequested(ProjectionLayout.CARD))
    }
    @Test fun `manual activation invalidates previously automatic pending timer`() {
        policy.start(true,true)
        val ticket = policy.layoutRequested(ProjectionLayout.CARD)!!
        policy.start(false,true)
        assertFalse(policy.claim(ticket,true))
    }
    @Test fun `successful dispatch without full response has a bounded retry budget`() {
        policy.start(true,true)
        var ticket = policy.layoutRequested(ProjectionLayout.CARD)!!
        repeat(StartupFullscreenPolicy.MAX_ATTEMPTS) { attempt ->
            assertTrue(policy.claim(ticket,true))
            assertEquals(attempt + 1,policy.attempts)
            val next = policy.confirmationTimedOut(true)
            if (attempt < StartupFullscreenPolicy.MAX_ATTEMPTS - 1) ticket = next!! else assertNull(next)
        }
        assertNull(policy.layoutRequested(ProjectionLayout.CARD))
    }
    @Test fun `full response cancels confirmation recovery`() {
        policy.start(true,true)
        val ticket = policy.layoutRequested(ProjectionLayout.CARD)!!
        assertTrue(policy.claim(ticket,true))
        policy.layoutRequested(ProjectionLayout.FULLSCREEN)
        assertNull(policy.confirmationTimedOut(true))
    }
}
