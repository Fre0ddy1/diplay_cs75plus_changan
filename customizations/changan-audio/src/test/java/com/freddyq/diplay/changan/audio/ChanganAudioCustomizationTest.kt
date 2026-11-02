package com.freddyq.diplay.changan.audio

import com.freddyq.diplay.customization.MediaKeyRoute
import com.freddyq.diplay.customization.CustomizationSettingsArea
import com.freddyq.diplay.customization.VehicleCustomizationRegistry
import com.freddyq.diplay.customization.WirelessAudioConnection
import org.robolectric.util.ReflectionHelpers
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
        val toggle = module.settings(app).first { it.area == CustomizationSettingsArea.DASHBOARD }.toggles.single()
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
        module.settings(app).first { it.area == CustomizationSettingsArea.DASHBOARD }.toggles.single().onChanged(false)
        assertEquals(12, original.getInt("media_audio_channel", -1))
        assertEquals(10, original.getInt("navigation_audio_channel", -1))
    }
    @Test fun turningOffKeyInterceptionDoesNotCancelTheConnectionMediaPolicyLease() {
        var priority = 100; var saved: SavedMediaPolicy? = null
        var disconnects = 0
        module.policyFactory = { MediaAudioPolicyLease({ object : MediaAudioPolicyPort {
            override fun start(ready: () -> Unit): Boolean { ready(); return true }
            override fun priority(address: String) = priority
            override fun setPriority(address: String, value: Int): Boolean { priority = value; return true }
            override fun disconnectMedia(address: String): Boolean { disconnects++; return true }
            override fun close() {}
        } }, object : MediaPolicyJournal {
            override fun read() = saved
            override fun save(value: SavedMediaPolicy): Boolean { saved = value; return true }
            override fun clear(): Boolean { saved = null; return true }
        }, {}) }
        module.settings(app).first { it.area == CustomizationSettingsArea.DASHBOARD }.toggles.single().onChanged(false)
        val owner = Any()
        module.onBluetoothMediaConnectionStarting(app, WirelessAudioConnection(owner, "02:11:22:33:44:55"))
        assertEquals(0, priority); assertEquals(1, disconnects)
        assertEquals(MediaKeyRoute.EXTERNAL, module.mediaKeyRoute(app))
        module.onBluetoothMediaConnectionEnded(owner); assertEquals(100, priority)
    }
    @Test fun newMediaPolicyOptionIsInConnectionSettingsAndIndependentOfKeyRouting() {
        val option = module.settings(app).single { it.area == CustomizationSettingsArea.CONNECTION }.toggles.single()
        assertTrue(option.enabled); option.onChanged(false)
        assertEquals(MediaKeyRoute.CARPLAY, module.mediaKeyRoute(app))
        assertFalse(app.getSharedPreferences(ChanganAudioCustomization.PREFS, 0).getBoolean(ChanganAudioCustomization.MEDIA_POLICY, true))
    }
    @Test fun oldWirelessHandoffCallbackNoLongerStartsRepeatedProfileDisconnects() {
        val owner = Any()
        module.onWirelessAudioHandoff(app, WirelessAudioConnection(owner, "02:11:22:33:44:55"))
        assertNull(ReflectionHelpers.getField<Any?>(module, "policy"))
        var completed = false
        assertFalse(module.prepareMediaPlayback(app) { completed = true }); assertFalse(completed)
    }
}
