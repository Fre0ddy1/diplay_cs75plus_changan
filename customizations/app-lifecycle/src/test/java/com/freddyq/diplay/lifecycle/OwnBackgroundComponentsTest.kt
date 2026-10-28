package com.freddyq.diplay.lifecycle

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class OwnBackgroundComponentsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val pm get() = app.packageManager
    private fun component(name: String) = ComponentName(app.packageName, "${app.packageName}.$name")
    private fun service(name: String) = ServiceInfo().apply { this.name = component(name).className; packageName = app.packageName; enabled = true }
    private fun activity(name: String) = ActivityInfo().apply { this.name = component(name).className; packageName = app.packageName; enabled = true }
    private fun install(services: Array<ServiceInfo> = arrayOf(service("Session"), service("Disabled"), service("Explicit"))) {
        shadowOf(pm).installPackage(PackageInfo().apply {
            packageName = app.packageName
            this.services = services
            receivers = arrayOf(activity("Boot"))
            activities = arrayOf(activity("Home"), activity("Host"))
        })
    }
    private fun state(name: String) = pm.getComponentEnabledSetting(component(name))
    @Before fun prepare() {
        app.getSharedPreferences(AppLifecycleCustomization.PREFS, 0).edit().clear().commit()
        install()
        pm.setComponentEnabledSetting(component("Disabled"), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(component("Explicit"), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
    }
    @Test fun stopsOnlyOwnBackgroundComponentsAndKeepsLauncherAvailable() {
        OwnBackgroundComponents(app).suspend(listOf(component("Host"), ComponentName("foreign.app", "foreign.app.Host")))
        for (name in listOf("Session", "Boot", "Host", "Explicit", "Disabled")) assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(name))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state("Home"))
        val journal = app.getSharedPreferences(AppLifecycleCustomization.PREFS, 0).getString("component_states", "")!!
        assertFalse(journal.contains("foreign.app")); assertFalse(journal.contains("Disabled"))
    }
    @Test fun restoresExactOriginalStatesAfterFreshInstance() {
        OwnBackgroundComponents(app).suspend(listOf(component("Host")))
        OwnBackgroundComponents(app).restore()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state("Session"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state("Boot"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state("Host"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state("Explicit"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state("Disabled"))
        assertFalse(app.getSharedPreferences(AppLifecycleCustomization.PREFS, 0).contains("component_states"))
    }
    @Test fun repeatedSuspensionDoesNotOverwriteOriginalJournal() {
        val components = OwnBackgroundComponents(app)
        components.suspend(listOf(component("Host"))); components.suspend(listOf(component("Host"))); components.restore()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state("Session"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state("Explicit"))
    }
    @Test fun upgradeRemovingAComponentDoesNotPreventRemainingRestoration() {
        OwnBackgroundComponents(app).suspend(listOf(component("Host")))
        install(arrayOf(service("Session"), service("Disabled")))
        OwnBackgroundComponents(app).restore()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state("Session"))
        assertFalse(app.getSharedPreferences(AppLifecycleCustomization.PREFS, 0).contains("component_states"))
    }
}
