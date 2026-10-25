package com.shilapi.xcertplay

import android.view.View
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content
import com.shilapi.xcertplay.hud.BydLaneGuidance
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ClusterCardContentSelectionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var displayId = -1
    private lateinit var presentation: ClusterMapPresentation
    private val fields = listOf("turnCardView", "etaCardView", "laneCardView", "roadNameCardView", "exitBadgeView")
    private val guidance = ClusterTurnGuidance(3, 0, 159, "测试路", remainingSeconds = 120, remainingMeters = 800, currentRoad = "当前路", exitLabel = "A1")

    @Before fun setUp() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        displayId = ShadowDisplayManager.addDisplay("w1920dp-h720dp", 5)
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId)
        presentation = ClusterMapPresentation(context, display, onMirrorSurface = {}, onNavSurface = {}, navOnlyCards = true)
        presentation.show()
        presentation.setMode(false)
    }

    @After fun tearDown() {
        presentation.dismiss()
        ShadowDisplayManager.removeDisplay(displayId)
        AirPlayPersistence.overlaySettingsListener = null
    }

    private fun visibility(name: String): Int {
        val field = ClusterMapPresentation::class.java.getDeclaredField(name).apply { isAccessible = true }
        return (field.get(presentation) as View).visibility
    }

    private fun feed() {
        presentation.setTurnCardGuidance(guidance)
        presentation.setEtaGuidance(guidance)
        presentation.setLaneGuidance(BydLaneGuidance(listOf(1, 2)))
        presentation.setRoadNameGuidance(guidance)
        presentation.setExitGuidance(guidance)
    }

    private fun choose(content: Content) {
        AirPlayPersistence.saveClusterContent(context, content)
        presentation.refreshCardOverlays()
    }

    @Test fun defaultMapDoesNotOverlayAnyNavigationCardsEvenAfterFreshGuidance() {
        feed()
        fields.forEach { assertEquals(it, View.GONE, visibility(it)) }
    }

    @Test fun everyNonCustomSelectionHidesTheWholeGroupAndUpdatesCannotResurrectIt() {
        choose(Content.MAP_WITH_CUSTOM_CARD)
        feed()
        fields.forEach { assertEquals(it, View.VISIBLE, visibility(it)) }
        listOf(Content.MAP, Content.TURN_CARD, Content.INSTRUMENTS).forEach { content ->
            choose(content)
            feed()
            fields.forEach { assertEquals("$content $it", View.GONE, visibility(it)) }
        }
    }

    @Test fun changingBackToCustomContentRestoresCachedDataWithoutAnotherPhoneUpdate() {
        feed()
        choose(Content.MAP_WITH_CUSTOM_CARD)
        fields.forEach { assertEquals(it, View.VISIBLE, visibility(it)) }
        presentation.setMode(true)
        fields.forEach { assertEquals(it, View.GONE, visibility(it)) }
        presentation.setMode(false)
        fields.forEach { assertEquals(it, View.VISIBLE, visibility(it)) }
    }

    @Test fun customSelectionStillRespectsIndividualSwitchesAndMissingData() {
        AirPlayPersistence.saveClusterTurnCardVisible(context, false)
        AirPlayPersistence.saveClusterCardVisible(context, "eta", false)
        choose(Content.MAP_WITH_CUSTOM_CARD)
        feed()
        assertEquals(View.GONE, visibility("turnCardView"))
        assertEquals(View.GONE, visibility("etaCardView"))
        presentation.setRoadNameGuidance(null)
        assertEquals(View.GONE, visibility("roadNameCardView"))
    }
}
