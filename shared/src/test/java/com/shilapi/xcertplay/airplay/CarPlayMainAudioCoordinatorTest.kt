package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayMainAudioCoordinatorTest {
    private val operations = mutableListOf<MainAudioTransfer>()
    private val replies = mutableListOf<(Boolean) -> Unit>()
    private val timers = mutableListOf<() -> Unit>()
    private val log = mutableListOf<String>()
    private var writeImmediately = true
    private var writeSucceeds = true
    private var snapshots = 0
    private val coordinator = CarPlayMainAudioCoordinator(
        send = { operation, completed -> operations += operation; replies += completed; if (writeImmediately) completed(writeSucceeds) },
        schedule = { ms, task ->
            assertEquals(2500L, ms)
            var active = true; timers += { if (active) task() }
            val cancel: () -> Unit = { active = false }; cancel
        }, changed = { snapshots++ }, log = log::add,
    )
    private fun update(entity: Int, permanent: Int = entity) = coordinator.modesChanged(mapOf("resources" to
        listOf(mapOf("resourceID" to 2, "entity" to entity, "permanentEntity" to permanent))))

    @Test fun unknownSessionNeverOptimisticallyEnablesMedia() {
        coordinator.start()
        assertEquals(CarPlayAudioOwner.UNKNOWN, coordinator.state.owner)
        assertFalse(coordinator.state.mediaOutputAllowed); assertEquals(1, snapshots)
    }
    @Test fun writingTakeIsNotAResourceGrant() {
        coordinator.nativeFocusLost(temporary = false)
        assertEquals(listOf(MainAudioTransfer.TAKE), operations)
        assertEquals(CarPlayAudioOwner.UNKNOWN, coordinator.state.owner)
        assertTrue(coordinator.state.awaitingConfirmation)
        update(2)
        assertFalse(coordinator.state.awaitingConfirmation)
        assertEquals(CarPlayAudioOwner.ACCESSORY, coordinator.state.owner)
        assertFalse(coordinator.state.mediaOutputAllowed)
    }
    @Test fun nativeTemporaryBorrowReturnsToPhoneOnlyOnItsConfirmation() {
        update(1); coordinator.nativeFocusLost(temporary = true)
        assertFalse(coordinator.state.mediaOutputAllowed)
        update(2, 1)
        assertTrue(coordinator.state.nativeBorrowActive)
        coordinator.nativeFocusGained()
        assertEquals(listOf(MainAudioTransfer.BORROW, MainAudioTransfer.UNBORROW), operations)
        assertFalse(coordinator.state.mediaOutputAllowed)
        assertTrue(coordinator.state.nativeBorrowActive)
        update(1)
        assertFalse(coordinator.state.nativeBorrowActive)
        assertTrue(coordinator.state.mediaOutputAllowed)
    }
    @Test fun permanentNativeSourceIsNotReturnedByAnUnrelatedFocusGain() {
        update(1); coordinator.nativeFocusLost(temporary = false); update(2)
        coordinator.nativeFocusGained()
        assertEquals(listOf(MainAudioTransfer.TAKE), operations)
        assertFalse(coordinator.state.mediaOutputAllowed)
    }
    @Test fun explicitPlaybackReleasesNativeTakeWithoutAWaitOrAutoplay() {
        update(2)
        coordinator.userPlaybackRequested()
        assertEquals(listOf(MainAudioTransfer.UNTAKE), operations)
        assertFalse(coordinator.state.mediaOutputAllowed)
        update(1)
        assertTrue(coordinator.state.mediaOutputAllowed)
    }
    @Test fun ordinaryNextInConfirmedPhoneModeDoesNotGenerateResourceRequests() {
        update(1)
        repeat(8) { coordinator.userPlaybackRequested() }
        assertTrue(operations.isEmpty())
    }
    @Test fun duplicateNativeLossDoesNotRepeatedlyTakeOrBorrow() {
        repeat(5) { coordinator.nativeFocusLost(temporary = true) }
        assertEquals(listOf(MainAudioTransfer.BORROW), operations)
    }
    @Test fun returnOfABorrowedNativePermanentSourceStaysWithTheNativeSource() {
        update(2); coordinator.nativeFocusLost(temporary = true); update(2, 2)
        coordinator.nativeFocusGained(); update(2, 2)
        assertFalse(coordinator.state.awaitingConfirmation)
        assertEquals(CarPlayAudioOwner.ACCESSORY, coordinator.state.owner)
        assertFalse(coordinator.state.mediaOutputAllowed)
    }
    @Test fun nativeTakeAfterBorrowReturnsTheBorrowBeforeTaking() {
        coordinator.nativeFocusLost(temporary = true)
        coordinator.nativeFocusLost(temporary = false)
        assertEquals(listOf(MainAudioTransfer.BORROW, MainAudioTransfer.UNBORROW, MainAudioTransfer.TAKE), operations)
    }
    @Test fun phoneCallSiriOrAlertFocusDoesNotBecomeANativeBorrow() {
        update(1); coordinator.phoneManagedAudio(true)
        coordinator.nativeFocusLost(temporary = true)
        coordinator.nativeFocusLost(temporary = false)
        assertTrue(operations.isEmpty()); assertTrue(coordinator.state.mediaOutputAllowed)
        coordinator.phoneManagedAudio(false); coordinator.nativeFocusLost(temporary = true)
        assertEquals(listOf(MainAudioTransfer.BORROW), operations)
    }
    @Test fun missingMalformedAndScreenOnlyUpdatesNeverConfirmAudio() {
        coordinator.nativeFocusLost(temporary = false)
        assertFalse(coordinator.modesChanged(emptyMap()))
        assertFalse(coordinator.modesChanged(mapOf("resources" to listOf(mapOf("resourceID" to 1, "entity" to 2)))))
        assertFalse(update(99)); assertTrue(coordinator.state.awaitingConfirmation)
        assertEquals(CarPlayAudioOwner.UNKNOWN, coordinator.state.owner)
    }
    @Test fun noConfirmationDoesNotInventAGrantOrRetryForever() {
        coordinator.nativeFocusLost(temporary = false)
        timers.toList().forEach { it() }
        assertFalse(coordinator.state.awaitingConfirmation)
        assertEquals(CarPlayAudioOwner.UNKNOWN, coordinator.state.owner)
        assertFalse(coordinator.state.mediaOutputAllowed)
        assertEquals(1, operations.size); assertTrue(log.any { "timed out" in it })
    }
    @Test fun failedWritePreservesThePreviouslyConfirmedOwner() {
        update(2); writeSucceeds = false
        coordinator.userPlaybackRequested()
        assertEquals(CarPlayAudioOwner.ACCESSORY, coordinator.state.owner)
        assertFalse(coordinator.state.mediaOutputAllowed); assertFalse(coordinator.state.awaitingConfirmation)
    }
    @Test fun anAckBeforeWriteCompletionDoesNotCreateALateTimeout() {
        writeImmediately = false
        coordinator.nativeFocusLost(temporary = false); update(2)
        replies.single()(true)
        assertTrue(timers.isEmpty()); assertFalse(coordinator.state.awaitingConfirmation)
    }
    @Test fun oldWriteCompletionDoesNotCompleteANewerRequest() {
        writeImmediately = false
        coordinator.nativeFocusLost(temporary = true); coordinator.nativeFocusGained()
        replies.first()(false)
        assertTrue(coordinator.state.awaitingConfirmation)
        replies.last()(true); update(1)
        assertFalse(coordinator.state.awaitingConfirmation); assertTrue(coordinator.state.mediaOutputAllowed)
    }
    @Test fun disconnectRejectsQueuedCompletionsAndStateUpdates() {
        writeImmediately = false
        coordinator.nativeFocusLost(temporary = false)
        coordinator.close(); val before = snapshots
        replies.single()(true); timers.forEach { it() }
        assertFalse(update(1)); coordinator.userPlaybackRequested()
        assertEquals(before, snapshots); assertEquals(1, operations.size)
        assertEquals(CarPlayAudioOwner.UNKNOWN, coordinator.state.owner)
    }
    @Test fun aPhoneInitiatedGrantIsAcceptedWithoutALocalKeyOrRequest() {
        update(2); update(1)
        assertTrue(coordinator.state.mediaOutputAllowed); assertTrue(operations.isEmpty())
    }
    @Test fun allCommandsRoundTripAsAudioOnlyModesMessages() {
        for (operation in MainAudioTransfer.entries) {
            val command = BplistCodec.decode(BplistCodec.encode(mainAudioModeCommand(operation))) as Map<*, *>
            assertEquals("changeModes", command["type"])
            val params = command["params"] as Map<*, *>
            assertFalse(params.containsKey("appStates"))
            val resources = params["resources"] as List<*>
            assertEquals(1, resources.size)
            val resource = resources.single() as Map<*, *>
            assertEquals(2, (resource["resourceID"] as Number).toInt())
            assertEquals(operation.wire, (resource["transferType"] as Number).toInt())
            assertFalse(resource.containsKey("entity"))
        }
    }
}
