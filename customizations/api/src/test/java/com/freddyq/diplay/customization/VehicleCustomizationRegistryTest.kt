package com.freddyq.diplay.customization

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VehicleCustomizationRegistryTest {
    private val registry = VehicleCustomizationRegistry()
    private val app get() = RuntimeEnvironment.getApplication()
    @Test fun `warm recovery requires an explicit owner`() {
        val endpoint = object : ProjectionControl { override fun requestLayout(layout: ProjectionLayout) = true }
        assertFalse(registry.onProjectionResumed(app,endpoint))
        registry.register(object : VehicleCustomization {
            override val id = "warm-owner"
            override fun onProjectionResumed(context: Context, control: ProjectionControl): Boolean {
                assertSame(app,context)
                assertSame(endpoint,control)
                return true
            }
        })
        assertTrue(registry.onProjectionResumed(app,endpoint))
    }
    @Test fun `broken warm listener cannot stop later recovery owner`() {
        val endpoint = object : ProjectionControl { override fun requestLayout(layout: ProjectionLayout) = true }
        registry.register(object : VehicleCustomization {
            override val id = "broken-warm"
            override fun onProjectionResumed(context: Context, control: ProjectionControl): Boolean = error("failure")
        })
        registry.register(object : VehicleCustomization {
            override val id = "healthy-warm"
            override fun onProjectionResumed(context: Context, control: ProjectionControl) = true
        })
        assertTrue(registry.onProjectionResumed(app,endpoint))
    }
    @Test fun `empty registry retains upstream projection retries`() {
        assertTrue(registry.shouldRetryAutomaticProjection())
        assertTrue(registry.shouldActivateProjectionChannel())
    }
    @Test fun `channel recovery can remain available after mode retries settle`() {
        registry.register(object : VehicleCustomization {
            override val id = "channel-policy"
            override fun shouldRetryAutomaticProjection() = false
            override fun shouldActivateProjectionChannel() = true
        })
        assertFalse(registry.shouldRetryAutomaticProjection())
        assertTrue(registry.shouldActivateProjectionChannel())
    }
    @Test fun `broken channel policy cannot disable upstream recovery`() {
        registry.register(object : VehicleCustomization {
            override val id = "broken-channel"
            override fun shouldActivateProjectionChannel(): Boolean = error("failure")
        })
        assertTrue(registry.shouldActivateProjectionChannel())
    }
    @Test fun `an opted in customization may stop mode reassertions`() {
        registry.register(object : VehicleCustomization {
            override val id = "settled"
            override fun shouldRetryAutomaticProjection() = false
        })
        assertFalse(registry.shouldRetryAutomaticProjection())
    }
    @Test fun `broken retry policy does not disable upstream retries`() {
        registry.register(object : VehicleCustomization {
            override val id = "broken-retry"
            override fun shouldRetryAutomaticProjection(): Boolean = error("failure")
        })
        assertTrue(registry.shouldRetryAutomaticProjection())
    }

    @Test fun `projection lifecycle preserves order and passes the same control endpoint`() {
        val events = mutableListOf<String>()
        val endpoint = object : ProjectionControl {
            override fun requestLayout(layout: ProjectionLayout): Boolean { events += "request=$layout"; return true }
        }
        registry.register(object : VehicleCustomization {
            override val id = "projection"
            override fun onProjectionStarted(context: Context, automatic: Boolean, control: ProjectionControl) {
                assertSame(endpoint,control)
                assertSame(app,context)
                events += "start=$automatic"
            }
            override fun onProjectionLayoutRequested(layout: ProjectionLayout) { events += "layout=$layout" }
            override fun onProjectionManualControl() { events += "manual" }
            override fun onProjectionReleased() { events += "released" }
        })
        registry.onProjectionStarted(app,true,endpoint)
        registry.onProjectionLayoutRequested(ProjectionLayout.CARD)
        registry.onProjectionManualControl()
        registry.onProjectionReleased()
        assertEquals(listOf("start=true","layout=CARD","manual","released"),events)
    }

    @Test fun `one projection extension failure does not suppress the following extension`() {
        var seen = false
        registry.register(object : VehicleCustomization {
            override val id = "broken-projection"
            override fun onProjectionLayoutRequested(layout: ProjectionLayout) { error("failure") }
        })
        registry.register(object : VehicleCustomization {
            override val id = "other-projection"
            override fun onProjectionLayoutRequested(layout: ProjectionLayout) { seen = true }
        })
        registry.onProjectionLayoutRequested(ProjectionLayout.FULLSCREEN)
        assertTrue(seen)
    }

    @Test fun `session and projection events preserve order`() {
        val events = mutableListOf<String>()
        registry.register(object : VehicleCustomization {
            override val id = "test"
            override fun onSessionStarted(context: Context) { events += "start" }
            override fun onNavigationFrame(messageId: Int, payload: ByteArray) { events += "frame" }
            override fun onProjectionNavigationState(state: Int) { events += "projection=$state" }
            override fun onSessionEnded() { events += "end" }
        })
        registry.onSessionStarted(app)
        registry.onNavigationFrame(0x5201,byteArrayOf(1))
        registry.onProjectionNavigationState(9)
        registry.onSessionEnded()
        assertEquals(listOf("start","frame","projection=9","end"),events)
    }

    @Test fun `extensions cannot change another extension or transport payload`() {
        registry.register(object : VehicleCustomization {
            override val id = "mutator"
            override fun onNavigationFrame(messageId: Int, payload: ByteArray) { payload[0] = 9 }
        })
        var seen: ByteArray? = null
        registry.register(object : VehicleCustomization {
            override val id = "reader"
            override fun onNavigationFrame(messageId: Int, payload: ByteArray) { seen = payload }
        })
        val original = byteArrayOf(1,2)
        registry.onNavigationFrame(0x5202,original)
        assertArrayEquals(byteArrayOf(1,2),original)
        assertArrayEquals(original,seen)
        assertNotSame(original,seen)
    }

    @Test fun `non navigation traffic is not dispatched`() {
        var calls = 0
        registry.register(object : VehicleCustomization {
            override val id = "counter"
            override fun onNavigationFrame(messageId: Int, payload: ByteArray) { calls++ }
        })
        listOf(0x5000,0x5201,0x5202,0x5204).forEach { registry.onNavigationFrame(it,byteArrayOf()) }
        assertEquals(3,calls)
    }

    @Test fun `repeated provider registration does not duplicate outputs`() {
        var calls = 0
        val module = object : VehicleCustomization {
            override val id = "one"
            override fun onSessionEnded() { calls++ }
        }
        registry.register(module)
        registry.register(module)
        registry.onSessionEnded()
        assertEquals(1,calls)
    }

    @Test fun `one failing extension cannot stop the next extension or core callback`() {
        var delivered = false
        registry.register(object : VehicleCustomization {
            override val id = "broken"
            override fun onSessionEnded() { error("failure") }
        })
        registry.register(object : VehicleCustomization {
            override val id = "working"
            override fun onSessionEnded() { delivered = true }
        })
        registry.onSessionEnded()
        assertTrue(delivered)
    }

    @Test fun `broken settings do not hide other modules`() {
        registry.register(object : VehicleCustomization {
            override val id = "broken"
            override fun settings(context: Context): List<CustomizationSettingsGroup> = error("failure")
        })
        registry.register(object : VehicleCustomization {
            override val id = "working"
            override fun settings(context: Context) = listOf(CustomizationSettingsGroup(123,emptyList()))
        })
        assertEquals(123,registry.settings(app).single().title)
    }

    @Test fun `diagnostic consumer failure does not escape into output`() {
        registry.diagnosticListener = { error("closed activity") }
        registry.diagnostic("module","message")
    }

    @Test fun `setting callback failure cannot crash the host settings UI`() {
        registry.register(object : VehicleCustomization {
            override val id = "broken-toggle"
            override fun settings(context: Context) = listOf(CustomizationSettingsGroup(1,listOf(
                CustomizationToggle(2,3,true) { error("failure") },
            )))
        })
        registry.settings(app).single().toggles.single().onChanged(false)
    }
}
