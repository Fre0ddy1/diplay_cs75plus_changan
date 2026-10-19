package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Custom turn card: absolute pixel position on the 1920x720 panel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ClusterTurnCardPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().apply()
        AirPlayPersistence.overlaySettingsListener = null
    }

    @Test fun defaultsAreInsideThePanel() {
        val x = AirPlayPersistence.loadClusterTurnCardX(context)
        val y = AirPlayPersistence.loadClusterTurnCardY(context)
        assert(x in 0..1920) { "x=$x" }
        assert(y in 0..720) { "y=$y" }
    }

    @Test fun offsetsRoundTrip() {
        AirPlayPersistence.saveClusterTurnCardX(context, 640)
        AirPlayPersistence.saveClusterTurnCardY(context, 210)
        assertEquals(640, AirPlayPersistence.loadClusterTurnCardX(context))
        assertEquals(210, AirPlayPersistence.loadClusterTurnCardY(context))
    }

    @Test fun offsetsAreClampedToThePanel() {
        AirPlayPersistence.saveClusterTurnCardX(context, 5000)
        AirPlayPersistence.saveClusterTurnCardY(context, -40)
        assertEquals(1920, AirPlayPersistence.loadClusterTurnCardX(context))
        assertEquals(0, AirPlayPersistence.loadClusterTurnCardY(context))
    }

    @Test fun saveNotifiesTheHostOncePerCall() {
        var noticed = 0
        AirPlayPersistence.overlaySettingsListener = { noticed++ }
        AirPlayPersistence.saveClusterTurnCardX(context, 300)
        AirPlayPersistence.saveClusterTurnCardY(context, 120)
        assertEquals(2, noticed)
    }

    @Test fun silentSaveDoesNotNotifyTheHost() {
        var noticed = 0
        AirPlayPersistence.overlaySettingsListener = { noticed++ }
        AirPlayPersistence.saveClusterTurnCardXSilent(context, 300)
        AirPlayPersistence.saveClusterTurnCardYSilent(context, 120)
        assertEquals(0, noticed)
        assertEquals(300, AirPlayPersistence.loadClusterTurnCardX(context))
        assertEquals(120, AirPlayPersistence.loadClusterTurnCardY(context))
    }
}
