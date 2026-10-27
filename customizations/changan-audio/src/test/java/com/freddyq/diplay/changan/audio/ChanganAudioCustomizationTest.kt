package com.freddyq.diplay.changan.audio

import com.freddyq.diplay.customization.MediaKeyRoute
import com.freddyq.diplay.customization.VehicleCustomizationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ChanganAudioCustomizationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val module = ChanganAudioCustomization()
    @Before fun reset() { app.getSharedPreferences(ChanganAudioCustomization.PREFS, 0).edit().clear().commit() }
    @Test fun defaultsToCarPlayEvenWithOldBluetoothPreference() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().putBoolean("media_audio_via_bluetooth", true).commit()
        assertEquals(MediaKeyRoute.CARPLAY, module.mediaKeyRoute(app))
    }
    @Test fun explicitCompatibilityChoiceIsSeparateAndPersistent() {
        val toggle = module.settings(app).single().toggles.single()
        assertTrue(toggle.enabled)
        toggle.onChanged(false)
        assertEquals(MediaKeyRoute.EXTERNAL, ChanganAudioCustomization().mediaKeyRoute(app))
        assertTrue(app.getSharedPreferences("xcertplay_airplay", 0).getBoolean("media_audio_via_bluetooth", true))
    }
    @Test fun registryDispatchesGenericRouteWithoutProtocolDetails() {
        val registry = VehicleCustomizationRegistry()
        assertNull(registry.mediaKeyRoute(app))
        registry.register(module)
        assertEquals(MediaKeyRoute.CARPLAY, registry.mediaKeyRoute(app))
    }
    @Test fun controlsDoNotRewriteAudioChannels() {
        val original = app.getSharedPreferences("xcertplay_airplay", 0)
        original.edit().putInt("media_audio_channel", 12).putInt("navigation_audio_channel", 10).commit()
        module.settings(app).single().toggles.single().onChanged(false)
        assertEquals(12, original.getInt("media_audio_channel", -1))
        assertEquals(10, original.getInt("navigation_audio_channel", -1))
    }
}
