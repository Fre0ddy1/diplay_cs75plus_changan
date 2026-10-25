package com.freddyq.diplay.changan.projection

import android.os.Looper
import com.freddyq.diplay.customization.ProjectionControl
import com.freddyq.diplay.customization.ProjectionLayout
import com.freddyq.diplay.customization.VehicleCustomizations
import org.junit.Assert.*
import org.junit.After
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
@Config(manifest=Config.NONE,sdk=[28,33])
@LooperMode(LooperMode.Mode.PAUSED)
class ChanganProjectionAndroidTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val module = ChanganProjectionCustomization()
    private val requested = mutableListOf<ProjectionLayout>()
    private val endpoint = object : ProjectionControl {
        override fun requestLayout(layout: ProjectionLayout): Boolean { requested += layout; return true }
    }
    @Before fun reset() {
        ChanganProjectionBridge.clear()
        app.getSharedPreferences("diplay_changan_projection",0).edit().clear().commit()
    }
    @After fun end() { module.onSessionEnded() }
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms,TimeUnit.MILLISECONDS)
    private fun start() = module.onProjectionStarted(app,true,endpoint)

    @Test fun `setting is always visible defaults on and preserves saved off`() {
        val toggle = module.settings(app).single().toggles.single()
        assertTrue(toggle.enabled)
        toggle.onChanged(false)
        assertFalse(module.settings(app).single().toggles.single().enabled)
    }
    @Test fun `private initializer registers once without making projection requests`() {
        val initializer = ChanganProjectionInitializer()
        assertTrue(initializer.onCreate())
        assertTrue(initializer.onCreate())
        assertEquals(1,VehicleCustomizations.settings(app).count { it.title == R.string.settings_changan_projection_title })
        idle(2000)
        assertTrue(requested.isEmpty())
    }
    @Test fun `late small request waits for settle and dispatches full only once`() {
        start()
        idle(600000)
        assertTrue(requested.isEmpty())
        module.onProjectionLayoutRequested(ProjectionLayout.CARD)
        idle(1499)
        assertTrue(requested.isEmpty())
        idle(1)
        assertEquals(listOf(ProjectionLayout.FULLSCREEN),requested)
        module.onProjectionLayoutRequested(ProjectionLayout.FULLSCREEN)
        module.onProjectionLayoutRequested(ProjectionLayout.CARD)
        idle(60000)
        assertEquals(1,requested.size)
    }
    @Test fun `full layout arriving before timer prevents redundant correction`() {
        start()
        module.onProjectionLayoutRequested(ProjectionLayout.CARD)
        idle(500)
        module.onProjectionLayoutRequested(ProjectionLayout.FULLSCREEN)
        idle(5000)
        assertTrue(requested.isEmpty())
    }
    @Test fun `manual control cancels pending automatic correction`() {
        start()
        module.onProjectionLayoutRequested(ProjectionLayout.CARD)
        module.onProjectionManualControl()
        idle(5000)
        assertTrue(requested.isEmpty())
    }
    @Test fun `release cancels pending correction`() {
        start()
        module.onProjectionLayoutRequested(ProjectionLayout.CARD)
        module.onProjectionReleased()
        idle(5000)
        assertTrue(requested.isEmpty())
    }
    @Test fun `disabling then enabling does not override current manual layout`() {
        start()
        module.onProjectionLayoutRequested(ProjectionLayout.CARD)
        module.settings(app).single().toggles.single().onChanged(false)
        module.settings(app).single().toggles.single().onChanged(true)
        idle(5000)
        assertTrue(requested.isEmpty())
    }
    @Test fun `session end rejects timer and reconnect needs new layout feedback`() {
        start()
        module.onProjectionLayoutRequested(ProjectionLayout.CARD)
        module.onSessionEnded()
        idle(5000)
        start()
        idle(5000)
        assertTrue(requested.isEmpty())
        module.onProjectionLayoutRequested(ProjectionLayout.CARD)
        idle(1500)
        assertEquals(listOf(ProjectionLayout.FULLSCREEN),requested)
    }
    @Test fun `endpoint exceptions cannot crash host or create an unbounded retry loop`() {
        var attempts = 0
        module.onProjectionStarted(app,true,object : ProjectionControl {
            override fun requestLayout(layout: ProjectionLayout): Boolean { attempts++; error("endpoint unavailable") }
        })
        module.onProjectionLayoutRequested(ProjectionLayout.CARD)
        idle(1500)
        module.onProjectionLayoutRequested(ProjectionLayout.CARD)
        idle(60000)
        assertEquals(StartupFullscreenPolicy.MAX_ATTEMPTS,attempts)
    }
    @Test fun `dispatch success alone does not stop bounded recovery`() {
        start()
        module.onProjectionLayoutRequested(ProjectionLayout.CARD)
        idle(60000)
        assertEquals(StartupFullscreenPolicy.MAX_ATTEMPTS,requested.size)
        assertTrue(requested.all { it == ProjectionLayout.FULLSCREEN })
        assertTrue(module.shouldRetryAutomaticProjection()) // no acknowledgement: retain bounded activation fallback
        idle(60000)
        assertEquals(StartupFullscreenPolicy.MAX_ATTEMPTS,requested.size)
    }
    @Test fun `manual control cancels retries after a request was already dispatched`() {
        start()
        module.onProjectionLayoutRequested(ProjectionLayout.CARD)
        idle(1500)
        assertEquals(1,requested.size)
        module.onProjectionManualControl()
        idle(60000)
        assertEquals(1,requested.size)
    }
    @Test fun `duplicate phone session start cannot reset pending projection recovery`() {
        start()
        module.onProjectionLayoutRequested(ProjectionLayout.CARD)
        idle(1500)
        module.onSessionStarted(app)
        idle(2500)
        assertEquals(2,requested.size)
        assertFalse(module.shouldRetryAutomaticProjection())
        module.onProjectionLayoutRequested(ProjectionLayout.FULLSCREEN)
        idle(60000)
        assertEquals(2,requested.size)
    }
    @Test fun `settled full startup suppresses legacy mode retries until next activation`() {
        start()
        assertTrue(module.shouldRetryAutomaticProjection())
        module.onProjectionLayoutRequested(ProjectionLayout.CARD)
        idle(1500)
        assertFalse(module.shouldRetryAutomaticProjection())
        start()
        assertTrue(module.shouldRetryAutomaticProjection())
        module.onProjectionLayoutRequested(ProjectionLayout.FULLSCREEN)
        assertFalse(module.shouldRetryAutomaticProjection())
    }
}
