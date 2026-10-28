package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DashboardCardDefaultsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit() }
    @Test fun unconfiguredDashboardIsMapWithoutNativeInstructionCard() {
        assertEquals(Content.MAP, AirPlayPersistence.loadClusterContent(app))
        assertFalse(AirPlayPersistence.loadClusterTurnCardVisible(app))
    }
    @Test fun allUnconfiguredInformationAndMusicCardsStartHidden() {
        for (card in listOf("eta", "song", "apple", "exit", "lane")) assertFalse(card, AirPlayPersistence.loadClusterCardVisible(app, card))
        assertFalse(AirPlayPersistence.loadClusterSongShowArtwork(app))
    }
    @Test fun explicitSavedChoicesRemainEnabledAfterUpgrade() {
        AirPlayPersistence.saveClusterContent(app, Content.FLY_CARD)
        AirPlayPersistence.saveClusterTurnCardVisible(app, true)
        AirPlayPersistence.saveClusterSongShowArtwork(app, true)
        AirPlayPersistence.saveClusterCardVisible(app, "song", true)
        assertEquals(Content.FLY_CARD, AirPlayPersistence.loadClusterContent(app))
        assertTrue(AirPlayPersistence.loadClusterTurnCardVisible(app))
        assertTrue(AirPlayPersistence.loadClusterSongShowArtwork(app))
        assertTrue(AirPlayPersistence.loadClusterCardVisible(app, "song"))
    }
    @Test fun customModeStillRequiresExplicitIndividualOptIn() {
        AirPlayPersistence.saveClusterContent(app, Content.MAP_WITH_CUSTOM_CARD)
        assertFalse(AirPlayPersistence.loadClusterTurnCardVisible(app))
        assertFalse(AirPlayPersistence.loadClusterCardVisible(app, "eta"))
        AirPlayPersistence.saveClusterCardVisible(app, "eta", true)
        assertTrue(AirPlayPersistence.loadClusterCardVisible(app, "eta"))
    }
}
