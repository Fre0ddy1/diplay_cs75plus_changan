package com.freddyq.diplay.lifecycle

import android.content.ComponentName
import android.os.Looper
import com.freddyq.diplay.customization.ApplicationExitControl
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AppLifecycleCustomizationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val module = AppLifecycleCustomization()
    private val events = mutableListOf<String>()
    private var finished: (() -> Unit)? = null
    private var taskError = false
    private val components = object : BackgroundComponents {
        override fun suspend(automaticActivities: List<ComponentName>) { events += "suspend" }
        override fun restore() { events += "restore" }
    }
    private val control = object : ApplicationExitControl {
        override val automaticActivities = emptyList<ComponentName>()
        override fun shutdown(completed: () -> Unit) {
            assertFalse(module.allowAutomaticStartup(app))
            events += "shutdown"; finished = completed
        }
        override fun removeTasks() { events += "tasks"; if (taskError) error("task gone") }
        override fun terminate() { events += "terminate" }
    }
    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    @Before fun prepare() { app.getSharedPreferences(AppLifecycleCustomization.PREFS, 0).edit().clear().commit(); module.components = { components } }
    @Test fun cleanupFollowsShutdownAndTerminatesOwnProcessOnce() {
        assertTrue(module.requestApplicationExit(app, control))
        assertEquals(listOf("shutdown"), events)
        finished!!(); finished!!(); idle(200)
        assertEquals(listOf("shutdown", "suspend", "tasks", "terminate"), events)
        idle(15000); assertEquals(1, events.count { it == "terminate" })
    }
    @Test fun repeatedExitDoesNotDuplicateShutdown() {
        repeat(3) { module.requestApplicationExit(app, control) }
        assertEquals(listOf("shutdown"), events)
    }
    @Test fun stuckShutdownHasBoundedCleanup() {
        module.requestApplicationExit(app, control); idle(12200)
        assertEquals(listOf("shutdown", "suspend", "tasks", "terminate"), events)
        finished!!(); idle(500); assertEquals(1, events.count { it == "terminate" })
    }
    @Test fun taskRemovalFailureStillTerminates() {
        taskError = true; module.requestApplicationExit(app, control); finished!!(); idle(200)
        assertEquals("terminate", events.last())
    }
    @Test fun persistentStopRequiresExplicitManualLaunchInNewProcess() {
        module.requestApplicationExit(app, control)
        val fresh = AppLifecycleCustomization().also { it.components = { components } }
        assertFalse(fresh.allowAutomaticStartup(app))
        fresh.onManualApplicationLaunch(app)
        assertEquals("restore", events.last()); assertTrue(fresh.allowAutomaticStartup(app))
        fresh.onManualApplicationLaunch(app)
        assertEquals(1, events.count { it == "restore" })
    }
    @Test fun cannotCancelInProgressExitFromOldActivity() {
        module.requestApplicationExit(app, control); module.onManualApplicationLaunch(app)
        assertFalse(module.allowAutomaticStartup(app)); assertFalse("restore" in events)
    }
    @Test fun failedRestorationKeepsPersistentStop() {
        module.requestApplicationExit(app, control)
        val fresh = AppLifecycleCustomization().also { it.components = { object : BackgroundComponents {
            override fun suspend(automaticActivities: List<ComponentName>) {}
            override fun restore() { error("restore failed") }
        } } }
        assertThrows(IllegalStateException::class.java) { fresh.onManualApplicationLaunch(app) }
        assertFalse(fresh.allowAutomaticStartup(app))
    }
}
