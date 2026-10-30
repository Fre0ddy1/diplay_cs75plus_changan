package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BackgroundSessionAdoptionTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var sink: AndroidMediaSink
    private lateinit var controller: CarPlayController
    private lateinit var session: AirPlaySession
    private lateinit var listener: AirPlaySessionListener

    @Before fun prepare() {
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        sink = mock(AndroidMediaSink::class.java)
        session = mock(AirPlaySession::class.java)
        controller = mock(CarPlayController::class.java) { invocation ->
            when (invocation.method.name) {
                "activeAirPlaySession" -> session
                "attachUi" -> { listener = invocation.getArgument(0); null }
                else -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        CarPlayBackgroundSession.store(controller, sink, 1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, android.view.Surface.ROTATION_0, true, true, 1920, 720)) {}
        assertEquals(true, ReflectionHelpers.callInstanceMethod<Boolean>(host, "adoptBackgroundSession"))
    }
    @After fun restore() {
        CarPlayMediaKeys.detach(controller)
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
    }
    @Test fun adoptedLiveSessionReceivesDayNightWithoutAnotherActivation() {
        assertSame(session, ReflectionHelpers.getField<Any?>(host, "activeAirPlaySession"))
        ReflectionHelpers.setField(host, "darkMode", true)
        ReflectionHelpers.callInstanceMethod<Unit>(host, "syncAirPlayDarkMode",
            ReflectionHelpers.ClassParameter.from(ThemeModeDiagnostics.Source::class.java, ThemeModeDiagnostics.Source.CARPLAY_MODE))
        verify(session, timeout(2000)).setNightMode(true)
        verify(controller, never()).reconnectIphone()
    }
    @Test fun adoptedListenerAcceptsCurrentAudioResourceButRejectsAnotherPhoneSession() {
        val resources = mapOf("resources" to listOf(mapOf("resourceID" to 2, "entity" to 2)))
        clearInvocations(sink)
        listener.onCommand(mock(AirPlaySession::class.java), "modesChanged", resources)
        verify(sink, never()).setMediaResourceAllowed(false)
        listener.onCommand(session, "modesChanged", resources)
        verify(sink).setMediaResourceAllowed(false)
        assertFalse(listener.wantsTrace)
    }
}
