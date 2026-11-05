package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Iap2RouteGuidanceSubscriptionTest {
    @Test fun laneCapabilityIsExplicitlyOptIn() {
        val config = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)
        fun fields(c: Iap2IdentificationConfig) = Iap2ParameterList.parse(
            Iap2ParameterList.parse(Iap2IdentificationClient.identificationInformation(c).payload)
                .asList().single { it.id == 30 }.payload).asList()
        assertEquals(0, fields(config).count { it.id == 8 })
        assertArrayEquals(byteArrayOf(0, 8), fields(config.copy(laneGuidanceEnabled = true)).single { it.id == 8 }.payload)
    }
    @Test
    fun identificationDeclaresRouteGuidanceDisplayComponent() {
        val config = Iap2IdentificationConfig(
            name = "xcertplay",
            modelIdentifier = "xcertplay",
            manufacturer = "xcertplay",
            serialNumber = "xcertplay",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
        )

        val parameters = Iap2ParameterList.parse(
            Iap2IdentificationClient.identificationInformation(config).payload,
        ).asList()
        val component = Iap2ParameterList.parse(parameters.single { it.id == 30 }.payload).asList()
        assertArrayEquals(byteArrayOf(0, 42), component.single { it.id == 0 }.payload)
        assertArrayEquals("RouteGuidance\u0000".encodeToByteArray(), component.single { it.id == 1 }.payload)
        assertArrayEquals(byteArrayOf(0, 8), component.single { it.id == 6 }.payload)
    }

    @Test
    fun startRouteGuidanceUpdatesRequestsDeclaredComponent() {
        val frame = Iap2ControlMessages.subscriptions().single { it.messageId == 0x5200 }

        assertEquals(0x5200, frame.messageId)
        assertArrayEquals(
            byteArrayOf(0, 6, 0, 0, 0, 42, 0, 4, 0, 1, 0, 4, 0, 2),
            frame.payload,
        )
    }
}
