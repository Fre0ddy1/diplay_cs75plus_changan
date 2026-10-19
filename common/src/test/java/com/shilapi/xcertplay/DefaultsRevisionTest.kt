package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The shipped defaults are dashboard projection on, auto-fly on, auto-project on and a 60 % duck.
 * An install that already stored the previous values must be migrated exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DefaultsRevisionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", 0)

    @Before fun clear() {
        prefs.edit().clear().apply()
    }

    @Test fun freshInstallUsesTheNewDefaults() {
        AirPlayPersistence.applyDefaultsRevision(context)
        assertEquals(60, AirPlayPersistence.loadNavDuckPercent(context))
        assertTrue(AirPlayPersistence.loadAutoFlyScreen(context))
    }

    @Test fun untouchedOldDuckMovesToSixty() {
        prefs.edit().putInt("nav_duck_percent", 20).apply()
        AirPlayPersistence.applyDefaultsRevision(context)
        assertEquals(60, AirPlayPersistence.loadNavDuckPercent(context))
    }

    @Test fun customDuckIsKept() {
        prefs.edit().putInt("nav_duck_percent", 35).apply()
        AirPlayPersistence.applyDefaultsRevision(context)
        assertEquals(35, AirPlayPersistence.loadNavDuckPercent(context))
    }

    @Test fun switchesThatWereOffAreTurnedOnOnce() {
        prefs.edit().putBoolean("auto_fly_screen", false).apply()
        AirPlayPersistence.applyDefaultsRevision(context)
        assertTrue(AirPlayPersistence.loadAutoFlyScreen(context))
    }

    @Test fun migrationRunsOnlyOnce() {
        AirPlayPersistence.applyDefaultsRevision(context)
        prefs.edit()
            .putBoolean("auto_fly_screen", false)
            .putInt("nav_duck_percent", 25)
            .apply()
        // A later launch must not override what the driver chose after the migration.
        AirPlayPersistence.applyDefaultsRevision(context)
        assertEquals(false, AirPlayPersistence.loadAutoFlyScreen(context))
        assertEquals(25, AirPlayPersistence.loadNavDuckPercent(context))
    }

    @Test fun theTwoAutoProjectSwitchesAreMerged() {
        // Only the merged switch stays off when either of the two used to be off.
        prefs.edit()
            .putBoolean("auto_fly_screen", true)
            .putBoolean("auto_project_on_connect", false)
            .apply()
        AirPlayPersistence.applyDefaultsRevision(context)
        assertEquals(false, AirPlayPersistence.loadAutoFlyScreen(context))

        prefs.edit().clear().apply()
        prefs.edit()
            .putBoolean("auto_fly_screen", false)
            .putBoolean("auto_project_on_connect", true)
            .apply()
        AirPlayPersistence.applyDefaultsRevision(context)
        assertEquals(false, AirPlayPersistence.loadAutoFlyScreen(context))

        prefs.edit().clear().apply()
        prefs.edit()
            .putBoolean("auto_fly_screen", true)
            .putBoolean("auto_project_on_connect", true)
            .apply()
        AirPlayPersistence.applyDefaultsRevision(context)
        assertEquals(true, AirPlayPersistence.loadAutoFlyScreen(context))
    }
}
