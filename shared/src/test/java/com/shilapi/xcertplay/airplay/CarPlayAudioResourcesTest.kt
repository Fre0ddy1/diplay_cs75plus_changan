package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayAudioResourcesTest {
    private val state = CarPlayAudioResources()
    private fun payload(id: Int = 2, entity: Int = 1) = mapOf("resources" to listOf(mapOf("resourceID" to id, "entity" to entity, "permanentEntity" to entity)))
    @Test fun phoneOwnershipAllowsMedia() {
        assertTrue(state.update(payload()))
        assertEquals(CarPlayAudioOwner.PHONE, state.owner)
        assertTrue(state.mediaOutputAllowed)
    }
    @Test fun accessoryOwnershipDefersMedia() {
        state.update(payload(entity = 2))
        assertEquals(CarPlayAudioOwner.ACCESSORY, state.owner)
        assertFalse(state.mediaOutputAllowed)
    }
    @Test fun screenOnlyUpdateDoesNotChangeAudio() {
        state.update(payload(entity = 2))
        assertFalse(state.update(payload(id = 1)))
        assertFalse(state.mediaOutputAllowed)
    }
    @Test fun missingAndMalformedRowsDoNotInventOwnership() {
        state.update(payload(entity = 2))
        assertFalse(state.update(emptyMap()))
        assertFalse(state.update(mapOf("resources" to "not a list")))
        assertFalse(state.update(mapOf("resources" to listOf(mapOf("resourceID" to 2)))))
        assertEquals(CarPlayAudioOwner.ACCESSORY, state.owner)
    }
    @Test fun unknownWireValueDoesNotGrantAudio() {
        state.update(payload(entity = 2))
        assertFalse(state.update(payload(entity = 99)))
        assertFalse(state.mediaOutputAllowed)
    }
    @Test fun endedSessionDoesNotCarryOwnershipIntoNewSession() {
        state.update(payload(entity = 2))
        state.reset()
        assertEquals(CarPlayAudioOwner.UNKNOWN, state.owner)
        assertNull(state.entity)
        assertNull(state.permanentEntity)
    }

    @Test fun unassignedAudioDoesNotPermitMedia() {
        assertTrue(state.update(payload(entity = 0)))
        assertEquals(CarPlayAudioOwner.UNASSIGNED, state.owner)
        assertFalse(state.mediaOutputAllowed)
    }

    @Test fun duplicateAudioRowsDoNotChooseArbitraryOwnership() {
        state.update(payload(entity = 2))
        val rows = listOf(mapOf("resourceID" to 2, "entity" to 1), mapOf("resourceID" to 2, "entity" to 2))
        assertFalse(state.update(mapOf("resources" to rows)))
        assertEquals(CarPlayAudioOwner.ACCESSORY, state.owner)
    }

    @Test fun fractionalAndOverflowNumbersDoNotBecomeValidOwners() {
        state.update(payload(entity = 2))
        for (invalid in listOf<Any>(1.5, 1L shl 40, "1")) {
            assertFalse(state.update(mapOf("resources" to listOf(mapOf("resourceID" to 2, "entity" to invalid)))))
        }
        assertEquals(CarPlayAudioOwner.ACCESSORY, state.owner)
    }

    @Test fun longWireIntegersAreAcceptedWithoutLosingPermanentOwner() {
        assertTrue(state.update(mapOf("resources" to listOf(mapOf("resourceID" to 2L, "entity" to 1L, "permanentEntity" to 2L)))))
        assertEquals(CarPlayAudioOwner.PHONE, state.owner)
        assertEquals(2, state.permanentEntity)
    }
}
