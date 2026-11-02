package com.shilapi.xcertplay.airplay

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AirPlayMainAudioModesTest {
    private val output = ByteArrayOutputStream()
    private val key = ByteArray(32) { 7 }
    private val sessions = mutableListOf<AirPlaySession>()
    private val eventSocket = object : Socket() { override fun getOutputStream() = output }
    private fun session() = AirPlaySession(Socket(), AirPlayConfig(deviceName = "test",
        deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01", sourceVersion = "1.0",
        main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)),
        AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {},
        object : AirPlayMediaHandler {}).also(sessions::add)
    private fun eventReady(session: AirPlaySession) {
        ReflectionHelpers.setField(session, "eventSocket", eventSocket)
        ReflectionHelpers.setField(session, "eventCipher", ControlCipher(key, key))
        ReflectionHelpers.callInstanceMethod<Unit>(session, "sendPendingAudioModesLocked")
    }
    @After fun close() { sessions.forEach(AirPlaySession::close) }
    private fun commands(): List<Map<*, *>> {
        val plain = ControlCipher(key, key).decrypt(output.toByteArray())
        assertTrue(plain.rest.isEmpty())
        val commands = mutableListOf<Map<*, *>>()
        var at = 0
        while (at < plain.data.size) {
            var headEnd = at
            while (headEnd + 3 < plain.data.size && !plain.data.copyOfRange(headEnd, headEnd + 4)
                .contentEquals(byteArrayOf(13, 10, 13, 10))) headEnd++
            assertTrue(headEnd + 4 <= plain.data.size)
            val headers = plain.data.copyOfRange(at, headEnd).toString(Charsets.US_ASCII)
            assertTrue(headers.startsWith("POST /command RTSP/1.0"))
            val length = Regex("Content-Length: (\\d+)").find(headers)!!.groupValues[1].toInt()
            at = headEnd + 4
            commands += BplistCodec.decode(plain.data.copyOfRange(at, at + length)) as Map<*, *>
            at += length
        }
        return commands
    }
    @Test fun requestsBeforeEventReadinessAreSentOnceInOrder() {
        val session = session(); val written = mutableListOf<Boolean>()
        session.changeMainAudio(MainAudioTransfer.BORROW, written::add)
        session.changeMainAudio(MainAudioTransfer.UNBORROW, written::add)
        assertTrue(written.isEmpty()); assertEquals(0, output.size())
        eventReady(session)
        assertEquals(listOf(true, true), written)
        val transfers = commands().map { command ->
            val row = (((command["params"] as Map<*, *>)["resources"] as List<*>).single() as Map<*, *>)
            (row["transferType"] as Number).toInt()
        }
        assertEquals(listOf(3, 4), transfers)
    }
    @Test fun aModeMessageDoesNotWaitForAnAckBeforeOneHidPressAndRelease() {
        val session = session(); eventReady(session)
        var written = false; session.changeMainAudio(MainAudioTransfer.UNTAKE) { written = it }
        assertTrue(written)
        session.sendMedia(CarPlayMediaButton.NEXT)
        val commands = commands()
        assertEquals(listOf("changeModes", "hidSendReport", "hidSendReport"), commands.map { it["type"] })
        assertTrue((commands[1]["hidReport"] as ByteArray).any { it.toInt() != 0 })
        assertTrue((commands[2]["hidReport"] as ByteArray).all { it.toInt() == 0 })
    }
    @Test fun closeFailsQueuedRequestsAndLateReadinessCannotSendThem() {
        val session = session(); val replies = mutableListOf<Boolean>()
        session.changeMainAudio(MainAudioTransfer.TAKE, replies::add); session.close()
        eventReady(session)
        assertEquals(listOf(false), replies); assertEquals(0, output.size())
    }
    @Test fun aBoundedQueueRejectsOverflowAndCloseCompletesEachRequestOnce() {
        val session = session(); val replies = mutableListOf<Boolean>()
        repeat(9) { session.changeMainAudio(MainAudioTransfer.TAKE, replies::add) }
        assertEquals(listOf(false), replies)
        session.close(); assertEquals(9, replies.size); assertTrue(replies.none { it })
    }
    @Test fun earlyPhoneOwnershipIsRetainedUntilTheControllerActivatesThisSession() {
        val session = session()
        session.rememberMainAudioModes(mapOf("resources" to listOf(mapOf("resourceID" to 2, "entity" to 1, "permanentEntity" to 1))))
        val cached = session.lastMainAudioModes!!
        val parsed = CarPlayAudioResources(); assertTrue(parsed.update(cached)); assertTrue(parsed.mediaOutputAllowed)
        session.rememberMainAudioModes(mapOf("resources" to listOf(mapOf("resourceID" to 1, "entity" to 2))))
        assertSame(cached, session.lastMainAudioModes)
        session.close(); assertNull(session.lastMainAudioModes)
        session.rememberMainAudioModes(cached); assertNull(session.lastMainAudioModes)
    }
}
