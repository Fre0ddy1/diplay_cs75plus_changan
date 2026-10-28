package com.freddyq.diplay.customization

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ForegroundExtensionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val registry = VehicleCustomizationRegistry()
    private val control = ApplicationForegroundControl { ApplicationForeground(true, true, false) }
    @Test fun noExtensionPreservesUpstreamDefaultAndStartup() {
        assertNull(registry.automaticHotspotDefault(app)); assertFalse(registry.onApplicationForeground(app, control))
    }
    @Test fun exitPolicyPreventsAutomaticForegroundWorkAndFallback() {
        var calls = 0
        registry.register(object : VehicleCustomization {
            override val id = "stop"
            override fun allowAutomaticStartup(context: Context) = false
            override fun onApplicationForeground(context: Context, control: ApplicationForegroundControl): Boolean { calls++; return true }
        })
        assertTrue(registry.onApplicationForeground(app, control)); assertEquals(0, calls)
    }
    @Test fun firstOwnerClaimsHotspotWorkExactlyOnce() {
        var calls = 0
        for (idValue in listOf("owner", "other")) registry.register(object : VehicleCustomization {
            override val id = idValue
            override fun onApplicationForeground(context: Context, control: ApplicationForegroundControl): Boolean { calls++; return true }
        })
        assertTrue(registry.onApplicationForeground(app, control)); assertEquals(1, calls)
    }
    @Test fun settingsAreaAndActionSurviveRegistryAndFailuresAreIsolated() {
        registry.register(object : VehicleCustomization {
            override val id = "actions"
            override fun settings(context: Context) = listOf(CustomizationSettingsGroup(1, emptyList(),
                listOf(CustomizationSettingsAction(2, 3) { error("unavailable system page") }), CustomizationSettingsArea.CONNECTION))
        })
        val group = registry.settings(app).single()
        assertEquals(CustomizationSettingsArea.CONNECTION, group.area)
        group.actions.single().onClick()
    }
}
