package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MicrophoneEngineLifecycleTest {
    private class Rig : AutoCloseable {
        val started = CopyOnWriteArrayList<MicrophoneConfig>()
        val stopped = CopyOnWriteArrayList<AudioStreamId>()
        val gotInput = CountDownLatch(1)
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { started += config; gotInput.countDown() }
            override fun onMicrophoneStopped(id: AudioStreamId) { stopped += id }
        }, microphoneEnabled = true)
        private val loopback = InetAddress.getLoopbackAddress()
        val phone = AirPlaySession(object : Socket() {
            override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(loopback, 5000)
            override fun getInetAddress(): InetAddress = loopback
            override fun getLocalAddress(): InetAddress = loopback
        }, AirPlayConfig("test", "02:00:00:00:00:02", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, engine)
        init { ReflectionHelpers.setField(phone.pairVerify, "sharedSecret", ByteArray(32) { 7 }) }
        fun setup(type: String = "default", bits: Long = 0x4000L, input: Boolean = true) {
            assertNotNull(engine.onAudio(phone, 100, mapOf("audioType" to type, "audioFormat" to bits,
                "streamConnectionID" to 1L, "dataPort" to 9, "input" to input)))
        }
        override fun close() = phone.close()
    }
    @Test fun appInputWithoutAudioStartsOnceAndSessionCloseStopsIt() {
        Rig().use { rig ->
            rig.setup()
            assertTrue(rig.gotInput.await(2, TimeUnit.SECONDS))
            assertEquals(1, rig.started.size)
            assertEquals(10, rig.started.single().frameMillis)
            rig.stopped.clear()
            rig.phone.close()
            assertEquals(listOf(AudioStreamId(100, "default")), rig.stopped)
        }
    }
    @Test fun teardownBeforeTheTimerCannotStartARecorderLate() {
        Rig().use { rig ->
            rig.setup()
            rig.engine.onTeardown(rig.phone, 100)
            assertFalse(rig.gotInput.await(800, TimeUnit.MILLISECONDS))
            assertTrue(rig.started.isEmpty())
        }
    }
    @Test fun aReplacementSetupInvalidatesTheOldDelayedStart() {
        Rig().use { rig ->
            rig.setup()
            rig.setup(bits = 0x20000000L)
            assertTrue(rig.gotInput.await(2, TimeUnit.SECONDS))
            assertEquals(1, rig.started.size)
            assertEquals(48_000, rig.started.single().sampleRate)
            assertEquals(24_000, rig.started.single().opusClockRate)
            assertEquals(480, rig.started.single().rtpSamplesPerPacket)
        }
    }
    @Test fun callsAndOutputOnlyPromptsDoNotStartRecordingWithoutDownlink() {
        for ((type, input) in listOf("telephony" to true, "default" to false)) {
            Rig().use { rig ->
                rig.setup(type = type, input = input)
                assertFalse(rig.gotInput.await(800, TimeUnit.MILLISECONDS))
                assertTrue(rig.started.isEmpty())
            }
        }
    }
}
