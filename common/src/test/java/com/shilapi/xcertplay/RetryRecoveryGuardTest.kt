package com.shilapi.xcertplay
import org.junit.Assert.*
import org.junit.Test

class RetryRecoveryGuardTest {
    @Test fun aFailureWithoutRecoveryStillRetries() {
        val state = RetryRecoveryGuard(); state.lost()
        assertFalse(state.recoveredSince(state.checkpoint()))
    }
    @Test fun recoverySuppressesTheOlderRetry() {
        val state = RetryRecoveryGuard(); state.lost(); val token = state.checkpoint()
        state.running()
        assertTrue(state.recoveredSince(token))
    }
    @Test fun aNewLossAfterRecoveryMustNotBeSuppressed() {
        val state = RetryRecoveryGuard(); state.lost(); val token = state.checkpoint()
        state.running(); state.lost()
        assertFalse(state.recoveredSince(token))
    }
    @Test fun anotherRecoveryAfterTheSecondLossSuppressesAgain() {
        val state = RetryRecoveryGuard(); val token = state.checkpoint()
        state.running(); state.lost(); state.running()
        assertTrue(state.recoveredSince(token))
        assertFalse(state.recoveredSince(state.checkpoint()))
    }
}
