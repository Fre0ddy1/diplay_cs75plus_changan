package com.shilapi.xcertplay

import com.freddyq.diplay.changan.projection.ChanganProjectionCustomization
import com.freddyq.diplay.customization.VehicleCustomization
import com.freddyq.diplay.customization.VehicleCustomizations
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutomaticProjectionOptInTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val extensions get() = ReflectionHelpers.getField<MutableList<VehicleCustomization>>(VehicleCustomizations, "extensions")
    private var previous = emptyList<VehicleCustomization>()
    private lateinit var module: ChanganProjectionCustomization
    @Before fun prepare() {
        previous = extensions.toList(); extensions.clear()
        app.getSharedPreferences("diplay_changan_projection", 0).edit().clear().commit()
        module = ChanganProjectionCustomization()
        VehicleCustomizations.register(module)
    }
    @After fun restore() { module.onSessionEnded(); extensions.clear(); extensions.addAll(previous) }
    @Test fun freshAndLegacyEnabledInstallRequireExplicitOptIn() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear()
            .putBoolean("auto_fly_screen", true).putInt("defaults_revision", 0).commit()
        app.getSharedPreferences("diplay_changan_projection", 0).edit().putBoolean("automatic_fullscreen", true).commit()
        AirPlayPersistence.applyDefaultsRevision(app)
        assertFalse(AirPlayPersistence.loadAutoFlyScreen(app))
        assertFalse(module.settings(app).single().toggles.first().enabled)
    }
    @Test fun eitherSwitchChangesBothDirectionsAndSurvivesReload() {
        module.settings(app).single().toggles.first().onChanged(true)
        assertTrue(AirPlayPersistence.loadAutoFlyScreen(app))
        AirPlayPersistence.saveAutoFlyScreen(app, false)
        assertFalse(module.settings(app).single().toggles.first().enabled)
        AirPlayPersistence.saveAutoFlyScreen(app, true)
        assertTrue(ChanganProjectionCustomization().automaticProjectionEnabled(app))
        module.settings(app).single().toggles.first().onChanged(false)
        assertFalse(AirPlayPersistence.loadAutoFlyScreen(app))
    }
    @Test fun optionalPublicCapabilitiesRemainOffUntilExplicitlySaved() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        assertFalse(AirPlayPersistence.loadLaneGuidance(app))
        assertFalse(AirPlayPersistence.loadCallAtCallRate(app))
        assertFalse(AirPlayPersistence.loadSpeechWithout8And16Khz(app))
        AirPlayPersistence.saveLaneGuidance(app, true)
        AirPlayPersistence.saveCallAtCallRate(app, true)
        AirPlayPersistence.saveSpeechWithout8And16Khz(app, true)
        assertTrue(AirPlayPersistence.loadLaneGuidance(app))
        assertTrue(AirPlayPersistence.loadCallAtCallRate(app))
        assertTrue(AirPlayPersistence.loadSpeechWithout8And16Khz(app))
    }
}
