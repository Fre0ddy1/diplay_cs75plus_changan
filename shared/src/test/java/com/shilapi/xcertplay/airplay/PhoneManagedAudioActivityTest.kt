package com.shilapi.xcertplay.airplay

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PhoneManagedAudioActivityTest {
    private val events = mutableListOf<Pair<AirPlaySession, Boolean>>()
    private val sessions = mutableListOf<AirPlaySession>()
    private fun session() = AirPlaySession(Socket(), AirPlayConfig(deviceName = "test",
        deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01", sourceVersion = "1.0",
        main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)), AirPlayIdentity.generate(),
        PairingStore(), null, object : AirPlaySessionListener {
            override fun onPhoneManagedAudioState(session: AirPlaySession, active: Boolean) { events += session to active }
        }, object : AirPlayMediaHandler {}).also(sessions::add)
    @After fun close() { sessions.forEach(AirPlaySession::close) }
    @Test fun multipleCallsAndSiriStreamsUseAggregateEdgesWithinOneSession() {
        val session = session(); val call = AudioStreamId(100, "telephony"); val siri = AudioStreamId(100, "speechRecognition")
        session.phoneManagedAudioStarted(call); session.phoneManagedAudioStarted(siri)
        session.phoneManagedAudioStopped(call)
        assertTrue(session.hasPhoneManagedAudio()); assertEquals(listOf(session to true), events)
        session.phoneManagedAudioStopped(siri)
        assertFalse(session.hasPhoneManagedAudio()); assertEquals(listOf(session to true, session to false), events)
    }
    @Test fun musicAndAlternateGuidanceDoNotPretendToBePhoneCalls() {
        val session = session()
        session.phoneManagedAudioStarted(AudioStreamId(102, "media"))
        session.phoneManagedAudioStarted(AudioStreamId(100, "default"))
        assertFalse(session.hasPhoneManagedAudio()); assertTrue(events.isEmpty())
    }
    @Test fun oldSessionTeardownCannotChangeTheNewSessionsActiveCall() {
        val old = session(); val next = session(); val call = AudioStreamId(100, "telephony")
        old.phoneManagedAudioStarted(call); next.phoneManagedAudioStarted(call)
        old.phoneManagedAudioStopped(call)
        assertFalse(old.hasPhoneManagedAudio()); assertTrue(next.hasPhoneManagedAudio())
        assertSame(old, events.last().first)
    }
    @Test fun closedSessionRejectsLateStreamCallbacksAndClearsItsOwnActivity() {
        val session = session(); val call = AudioStreamId(100, "telephony")
        session.phoneManagedAudioStarted(call); session.close(); val count = events.size
        session.phoneManagedAudioStarted(call); session.phoneManagedAudioStopped(call)
        assertFalse(session.hasPhoneManagedAudio()); assertEquals(count, events.size)
    }
}
