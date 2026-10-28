package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import com.freddyq.diplay.customization.VehicleCustomization
import com.freddyq.diplay.customization.VehicleCustomizations
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DiPlayAppExitEntryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val extensions get() = ReflectionHelpers.getField<MutableList<VehicleCustomization>>(VehicleCustomizations, "extensions")
    private var saved = emptyList<VehicleCustomization>()
    private var stopped = true
    private val policy = object : VehicleCustomization {
        override val id = "exit-entry-test"
        override fun allowAutomaticStartup(context: Context) = !stopped
        override fun onManualApplicationLaunch(context: Context) { stopped = false }
    }
    @Before fun prepare() { saved = extensions.toList(); extensions.clear(); VehicleCustomizations.register(policy) }
    @After fun restore() { extensions.clear(); extensions.addAll(saved) }
    @Test fun bootCannotReenableStoppedAppOrLaunchActivity() {
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedActivity); assertTrue(stopped)
    }
    @Test fun usbNotificationAndExplicitHostCannotClearStop() {
        for (intent in listOf(Intent("android.hardware.usb.action.USB_DEVICE_ATTACHED"), Intent(), Intent(Intent.ACTION_MAIN))) {
            assertFalse(DiPlayAppExit.allowEntry(app, intent, launcher = true)); assertTrue(stopped)
        }
        assertFalse(DiPlayAppExit.allowEntry(app, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
    }
    @Test fun explicitLauncherRestoresStartup() {
        assertTrue(DiPlayAppExit.allowEntry(app, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), launcher = true))
        assertFalse(stopped)
    }
    @Test fun stoppedMapEmbedServiceDoesNotExposeBinder() {
        val service = org.robolectric.Robolectric.buildService(MapEmbedService::class.java).get()
        assertNull(service.onBind(Intent()))
    }
}
