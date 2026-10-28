package com.freddyq.diplay.changan.projection

import com.freddyq.diplay.customization.ProjectionEnvironment
import org.junit.Assert.*
import org.junit.Test

class ProjectionPowerRecoveryTest {
    private val ready = ProjectionEnvironment(2, true, true, 1, true, false, true, true, false, 0)
    private val policy = ProjectionPowerRecovery()
    private fun action(s: ProjectionEnvironment = ready, time: Long) = policy.observe(s, time)
    private fun exhaust() {
        assertEquals(ProjectionPowerRecovery.Action.WAIT, action(time = 0))
        for (time in listOf(1000L, 6000L, 21000L, 51000L)) {
            assertEquals(ProjectionPowerRecovery.Action.ACTIVATE, action(time = time))
            policy.activated(time)
        }
        assertEquals(ProjectionPowerRecovery.Action.WAIT, action(time = 200000))
    }
    @Test fun unchangedFailureHasFiniteBudget() { exhaust(); assertEquals(ProjectionPowerRecovery.Action.WAIT, action(time = 999999)) }
    @Test fun dispatchFailureDoesNotSpendBudget() {
        action(time = 0)
        repeat(10) { assertEquals(ProjectionPowerRecovery.Action.ACTIVATE, action(time = 1000)) }
    }
    @Test fun offDisplayNeverActivatesDespiteSaneSize() {
        val off = ready.copy(displayOn = false)
        action(off, 0)
        assertEquals(ProjectionPowerRecovery.Action.WAIT, action(off, 99999))
    }
    @Test fun powerCycleRearmsAnExhaustedBudgetAndInvalidatesLocally() {
        exhaust()
        assertEquals(ProjectionPowerRecovery.Action.INVALIDATE, action(ready.copy(displayOn = false), 210000))
        policy.invalidated()
        assertEquals(ProjectionPowerRecovery.Action.WAIT, action(ready.copy(displayOn = false), 211000))
        assertEquals(ProjectionPowerRecovery.Action.INVALIDATE, action(time = 220000))
        policy.invalidated()
        assertEquals(ProjectionPowerRecovery.Action.WAIT, action(time = 220500))
        assertEquals(ProjectionPowerRecovery.Action.ACTIVATE, action(time = 221000))
    }
    @Test fun serviceRestartRearmsAndRequiresReadiness() {
        exhaust()
        val dead = ready.copy(serviceEpoch = 2, serviceConnected = false)
        assertEquals(ProjectionPowerRecovery.Action.INVALIDATE, action(dead, 210000))
        policy.invalidated()
        assertEquals(ProjectionPowerRecovery.Action.WAIT, action(dead, 220000))
        assertEquals(ProjectionPowerRecovery.Action.ACTIVATE, action(dead.copy(serviceConnected = true), 220001))
    }
    @Test fun replacementDisplayDiscardsOldConfirmation() {
        action(time = 0)
        assertEquals(ProjectionPowerRecovery.Action.INVALIDATE, action(ready.copy(displayId = 9), 2000))
    }
    @Test fun noPhoneOrPlaceholderCannotConsumeActivation() {
        for (state in listOf(ready.copy(phoneConnected = false), ready.copy(sizeReady = false), ready.copy(serviceConnected = false))) {
            policy.clear(); action(state, 0)
            assertEquals(ProjectionPowerRecovery.Action.WAIT, action(state, 100000))
        }
    }
    @Test fun manualCloseSurvivesPowerEpochUntilExplicitRequest() {
        val closed = ready.copy(userClosed = true)
        action(closed, 0)
        assertEquals(ProjectionPowerRecovery.Action.WAIT, action(closed, 100000))
        assertEquals(ProjectionPowerRecovery.Action.INVALIDATE, action(closed.copy(serviceEpoch = 2), 100001))
        policy.invalidated()
        assertEquals(ProjectionPowerRecovery.Action.WAIT, action(closed.copy(serviceEpoch = 2), 110000))
        assertEquals(ProjectionPowerRecovery.Action.ACTIVATE, action(ready.copy(serviceEpoch = 2, manualRequest = 1), 110001))
    }
    @Test fun disabledAutomaticProjectionRequiresManualRequest() {
        val disabled = ready.copy(automaticEnabled = false)
        action(disabled, 0)
        assertEquals(ProjectionPowerRecovery.Action.WAIT, action(disabled, 99999))
        assertEquals(ProjectionPowerRecovery.Action.ACTIVATE, action(disabled.copy(manualRequest = 1), 100000))
    }
    @Test fun newPhoneConnectionGetsNewBudget() {
        exhaust()
        action(ready.copy(phoneConnected = false), 210000)
        assertEquals(ProjectionPowerRecovery.Action.WAIT, action(time = 220000))
        assertEquals(ProjectionPowerRecovery.Action.ACTIVATE, action(time = 221000))
    }
    @Test fun systemResumeRearmsEvenIfOffTransitionWasMissedDuringSleep() {
        exhaust()
        val resumed = ready.copy(resumeEpoch = 1)
        assertEquals(ProjectionPowerRecovery.Action.INVALIDATE, action(resumed, 210000))
        policy.invalidated()
        assertEquals(ProjectionPowerRecovery.Action.ACTIVATE, action(resumed, 211000))
    }
}
