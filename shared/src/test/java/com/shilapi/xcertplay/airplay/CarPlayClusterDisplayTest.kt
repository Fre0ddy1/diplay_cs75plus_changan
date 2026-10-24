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
        // The centre area clear in both cluster navi modes: left 35 %, right 36 %, top 16 %, bottom 25 %.
        assertEquals(672, safe["originXPixels"])
        assertEquals(115, safe["originYPixels"])
        assertEquals(557, safe["widthPixels"])
        assertEquals(425, safe["heightPixels"])
        assertEquals(true, safe["drawUIOutsideSafeArea"])

        // The main screen keeps its touch and knob features.
        assertEquals(0x0A, (displays[0] as Map<*, *>)["features"])
    }

    @Test
    fun theDashboardOffersOnlyTwoSources() {
        val instruments = CarPlayClusterDisplay.config(1920, 720, content = CarPlayClusterDisplay.Content.INSTRUMENTS)
        assertEquals("maps:/car/instrumentcluster", instruments.initialUrl)
        val custom = CarPlayClusterDisplay.config(1920, 720, content = CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD)
        assertEquals("maps:/car/instrumentcluster/map", custom.initialUrl)
        // MAP stays as an internal value for stored settings from older builds.
        // FLY_CARD (dual-stream) uses the MAP URL too: the map stream must stay card-free, the
        // turn card comes from the separate instruction-card display.
        assertEquals(
            listOf(
                "maps:/car/instrumentcluster",
                "maps:/car/instrumentcluster/map",
                "maps:/car/instrumentcluster/map",
                "maps:/car/instrumentcluster/map",
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
    fun markerStepsMoveTheWholeSafeArea() {
        val centre = CarPlayClusterDisplay.config(1920, 720, scalePercent = 100).safeArea!!
        val farLeftHigher = CarPlayClusterDisplay.config(1920, 720, scalePercent = 100, horizontalStep = -2, verticalStep = -1).safeArea!!

        // Two 10 % steps left (384 px) and one 10 % step up (72 px), same size as the centred area.
        assertEquals(centre.left - 384, farLeftHigher.left)
        assertEquals(centre.right + 384, farLeftHigher.right)
        assertEquals(centre.top - 72, farLeftHigher.top)
        assertEquals(centre.bottom + 72, farLeftHigher.bottom)
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

        // Marker at 15.5 % from the top: the area reaches the top edge and stays centred on it.
        assertEquals(0, top.top)
        assertEquals(310, 1000 - top.top - top.bottom)
        assertEquals(15.5, CarPlayClusterDisplay.markerPercent(0, -3).second, 0.001)
    }

    @Test
    fun theCarSitsNearThePanelCentre() {
        val safe = CarPlayClusterDisplay.SAFE_AREA_PERCENT
        val centreX = (safe.left + 100 - safe.right) / 2.0

        assertTrue("centre x $centreX", centreX in 48.0..52.0)
    }
}
