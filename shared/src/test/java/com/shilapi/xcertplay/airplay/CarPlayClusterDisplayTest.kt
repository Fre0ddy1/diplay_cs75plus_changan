package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayClusterDisplayTest {
    @Test
    fun defaultClusterIsAdvertisedAsAnInputlessMapScreen() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                cluster = CarPlayClusterDisplay.config(1920, 720),
            ),
        )

        val displays = info["displays"] as List<*>
        assertEquals(2, displays.size)
        val alt = displays[1] as Map<*, *>
        assertEquals(111, alt["type"])
        // The panel at 100 %: 1920x720, same 8:3 aspect.
        assertEquals(1920, alt["widthPixels"])
        assertEquals(720, alt["heightPixels"])
        assertEquals(0, alt["features"])
        assertEquals(0, alt["primaryInputDevice"])
        assertEquals("maps:/car/instrumentcluster/map", alt["initialURL"])
        assertEquals(292, alt["widthPhysical"])
        assertEquals(110, alt["heightPhysical"])

        val view = (alt["viewAreas"] as List<*>).single() as Map<*, *>
        assertEquals(1920, view["widthPixels"])
        assertEquals(720, view["heightPixels"])
        val safe = view["safeArea"] as Map<*, *>
        // Full width, but the UNI-V hides the bottom 110 px behind its moulding (2026-10-25):
        // the declared area is the visible 1920x610, while the stream itself stays 1920x720.
        assertEquals(0, safe["originXPixels"])
        assertEquals(0, safe["originYPixels"])
        assertEquals(1920, safe["widthPixels"])
        assertEquals(610, safe["heightPixels"])
        assertEquals(true, safe["drawUIOutsideSafeArea"])

        // The main screen keeps its touch and knob features.
        assertEquals(0x0A, (displays[0] as Map<*, *>)["features"])
    }

    @Test
    fun theDashboardOffersThreeSources() {
        val custom = CarPlayClusterDisplay.config(1920, 720, content = CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD)
        assertEquals("maps:/car/instrumentcluster/map", custom.initialUrl)
        // MAP stays as an internal value for stored settings from older builds.
        // FLY_CARD (dual-stream) uses the MAP URL too: the map stream must stay card-free, the
        // turn card comes from the separate instruction-card display.
        // APPLE_MAP_WITH_CARD asks for the combined cluster URL, where iOS itself lays out the
        // map, the glass card and the junction zoom in one full-panel stream.
        assertEquals(
            listOf(
                "maps:/car/instrumentcluster/map",
                "maps:/car/instrumentcluster/map",
                "maps:/car/instrumentcluster/map",
                "maps:/car/instrumentcluster",
            ),
            CarPlayClusterDisplay.Content.entries.map { it.url },
        )
    }

    @Test
    fun flyCardAdvertisesASecondInstructionCardDisplay() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                cluster = CarPlayClusterDisplay.config(1920, 720, content = CarPlayClusterDisplay.Content.FLY_CARD),
                clusterCard = CarPlayClusterDisplay.cardConfig(),
            ),
        )

        val displays = info["displays"] as List<*>
        assertEquals(3, displays.size)
        // The card is its own type-111 display with its own UUID, no input and no features.
        val card = displays[2] as Map<*, *>
        assertEquals(111, card["type"])
        assertEquals(AirPlayInfoPlist.CARD_UUID, card["uuid"])
        assertEquals(CarPlayClusterDisplay.CARD_WIDTH_PIXELS, card["widthPixels"])
        assertEquals(CarPlayClusterDisplay.CARD_HEIGHT_PIXELS, card["heightPixels"])
        assertEquals("maps:/car/instrumentcluster/instructioncard", card["initialURL"])
        assertEquals(0, card["features"])
        assertEquals(0, card["primaryInputDevice"])
        // The map display keeps its centred safe area and the plain map URL.
        val map = displays[1] as Map<*, *>
        assertEquals(AirPlayInfoPlist.ALT_UUID, map["uuid"])
        assertEquals("maps:/car/instrumentcluster/map", map["initialURL"])
    }

    @Test
    fun withoutACardDisplayTheInfoStaysTwoDisplays() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                cluster = CarPlayClusterDisplay.config(1920, 720),
            ),
        )

        assertEquals(2, (info["displays"] as List<*>).size)
    }

    @Test
    fun scaledStreamsKeepThePanelAspect() {
        // The scale is fixed at the panel size (no settings row any more), but the maths must keep
        // the aspect when a caller passes one explicitly.
        val sizes = listOf(100, 83, 67, 50).map {
            CarPlayClusterDisplay.config(1920, 720, scalePercent = it).let { c -> c.widthPixels to c.heightPixels }
        }

        assertEquals(listOf(1920 to 720, 1600 to 600, 1280 to 480, 960 to 360), sizes)
    }

    @Test
    fun markerStepsMoveTheMarkerInsideTheFullPanel() {
        // The steps move the marker itself, one 10 % step at a time, inside the declared area.
        val insets = CarPlayClusterDisplay.defaultSafeInsets(1920, 720)
        val centre = CarPlayClusterDisplay.markerPercentIn(insets, 1920, 720, 0, 0)
        val farLeftHigher = CarPlayClusterDisplay.markerPercentIn(insets, 1920, 720, -2, -1)

        assertEquals(50.0, centre.first, 0.01)
        assertEquals(42.36, centre.second, 0.05)
        assertEquals(30.0, farLeftHigher.first, 0.01)
        assertEquals(32.36, farLeftHigher.second, 0.05)

        // The advertised area is the full width with the hidden bottom strip excluded.
        val safe = CarPlayClusterDisplay.config(1920, 720, scalePercent = 100).safeArea!!
        assertEquals(0, safe.left)
        assertEquals(0, safe.right)
        assertEquals(0, safe.top)
        assertEquals(110, safe.bottom)
    }

    @Test
    fun markerStepsAreBounded() {
        val beyond = CarPlayClusterDisplay.config(1920, 720, scalePercent = 100, horizontalStep = 9, verticalStep = -9).safeArea
        val limit = CarPlayClusterDisplay.config(1920, 720, scalePercent = 100, horizontalStep = 4, verticalStep = -3).safeArea

        assertEquals(limit, beyond)
    }

    @Test
    fun nearAnEdgeTheSafeAreaShrinksAroundTheMarker() {
        val top = CarPlayClusterDisplay.config(1000, 1000, scalePercent = 100, verticalStep = -3).safeArea!!

        // Marker at 14.5 % from the top: the area reaches the top edge and stays centred on it.
        assertEquals(0, top.top)
        assertEquals(290, 1000 - top.top - top.bottom)
        val marker = CarPlayClusterDisplay.markerPercentIn(
            CarPlayClusterDisplay.defaultSafeInsets(1000, 1000), 1000, 1000, 0, -3,
        )
        assertEquals(14.5, marker.second, 0.001)
    }

    @Test
    fun theCarSitsNearThePanelCentre() {
        val insets = CarPlayClusterDisplay.defaultSafeInsets(1920, 720)
        val marker = CarPlayClusterDisplay.markerPercentIn(insets, 1920, 720, 0, 0)

        assertEquals(50.0, marker.first, 0.01)
        // 720 - 110 puts the marker at 42.36 %: the middle of the visible strip.
        assertEquals(42.36, marker.second, 0.05)
    }
}
