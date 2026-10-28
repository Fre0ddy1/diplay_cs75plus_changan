package com.freddyq.diplay.hotspot

import android.content.Context
import android.provider.Settings
import com.freddyq.diplay.customization.*
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering.Result
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [HotspotStartupCustomizationTest.Permission::class])
class HotspotStartupCustomizationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val extensions get() = ReflectionHelpers.getField<MutableList<VehicleCustomization>>(VehicleCustomizations, "extensions")
    private var saved = emptyList<VehicleCustomization>()
    private val module = HotspotStartupCustomization()
    private var state = ApplicationForeground(true, true, false)
    private val jobs = mutableListOf<() -> Unit>()
    private var calls = 0
    private val control = ApplicationForegroundControl { state }
    @Before fun prepare() {
        saved = extensions.toList(); extensions.clear(); Permission.allowed = false
        app.getSharedPreferences("diplay_car_hotspot", 0).edit().clear().commit()
        module.createWorker = { HotspotStartupWorker({ jobs += it }, { true }, { calls++; Result.READY }, finished = {}) }
        VehicleCustomizations.register(module)
    }
    @After fun restore() { extensions.clear(); extensions.addAll(saved) }
    @Test fun customizationDefaultOnHonorsExplicitSavedOff() {
        assertTrue(CarHotspotSettings.enabled(app)); CarHotspotSettings.setEnabled(app, false)
        assertFalse(CarHotspotSettings.enabled(app))
        module.onApplicationForeground(app, control); assertTrue(jobs.isEmpty())
    }
    @Test fun nonCarHotspotModesNeverScheduleRecovery() {
        for (option in listOf(ApplicationForeground(false, true, false), ApplicationForeground(true, false, false))) {
            state = option; assertTrue(module.onApplicationForeground(app, control)); assertTrue(jobs.isEmpty())
        }
    }
    @Test fun retainedSessionDoesNotSuppressDetectionOfAnAutoClosedHotspot() {
        state = state.copy(connectionActive = true)
        module.onApplicationForeground(app, control)
        assertEquals(1, jobs.size); jobs.removeAt(0).invoke(); assertEquals(1, calls)
    }
    @Test fun stoppedApplicationIsBlockedBeforeModuleStarts() {
        VehicleCustomizations.register(object : VehicleCustomization {
            override val id = "stopped"
            override fun allowAutomaticStartup(context: Context) = false
        })
        assertTrue(VehicleCustomizations.onApplicationForeground(app, control)); assertTrue(jobs.isEmpty())
    }
    @Test fun permissionActionUsesSystemUiAndDoesNotGrantByItself() {
        val group = module.settings(app).single()
        assertEquals(CustomizationSettingsArea.CONNECTION, group.area)
        assertEquals(1, group.actions.size)
        group.actions.single().onClick()
        val launch = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_WRITE_SETTINGS, launch.action)
        assertEquals("package:${app.packageName}", launch.data.toString())
        assertFalse(Permission.allowed)
    }
    @Test fun grantedPermissionRemovesUnnecessaryAuthorizationAction() {
        Permission.allowed = true
        assertTrue(module.settings(app).single().actions.isEmpty())
    }
    @Test fun absenceOfCustomizationPreservesUpstreamOptOut() {
        extensions.clear(); assertFalse(CarHotspotSettings.enabled(app))
    }
    @Implements(Settings.System::class)
    class Permission {
        companion object {
            var allowed = false
            @Implementation @JvmStatic fun canWrite(context: Context): Boolean = allowed
        }
    }
}
