package com.freddyq.diplay.probe

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.freddyq.diplay.customization.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LvdsRequestProbeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private class Endpoint(private val status: Int = 1, private val supported: Boolean = true) : Binder() {
        var calls = 0
        val tickets = mutableListOf<String>()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            calls++
            assertEquals(LvdsProbeProtocol.TRANSACTION, code)
            assertEquals(0, flags)
            data.enforceInterface(LvdsProbeProtocol.TOKEN)
            assertEquals(LvdsProbeProtocol.MAGIC, data.readInt())
            assertEquals(1, data.readInt())
            assertEquals(0x1b, data.readInt())
            assertEquals(5, data.readInt())
            assertEquals(1, data.readInt())
            assertEquals(3, data.readInt())
            tickets += data.readString()!!
            assertEquals(0, data.dataAvail())
            if (!supported) return false
            reply!!.writeNoException()
            reply.writeInt(LvdsProbeProtocol.MAGIC)
            reply.writeInt(1)
            reply.writeInt(status)
            return true
        }
    }
    private fun LvdsRequestProbe.connect(owner: Any) =
        onWirelessAudioHandoff(context, WirelessAudioConnection(owner, "00:00:00:00:00:00"))
    private fun LvdsRequestProbe.frame(window: Any) =
        onProjectionContentReady(context, window, ProjectionContentControl { fail("probe must not start another source loop") })

    @Test fun sourceWithoutCurrentFrameOrPhoneNeverSends() {
        val probe = LvdsRequestProbe(); val endpoint = Endpoint(); val window = Any()
        probe.onProjectionSourceReady(context, window, endpoint)
        probe.connect(Any())
        probe.onProjectionSourceReady(context, window, endpoint)
        probe.frame(window)
        probe.onProjectionSourceReady(context, Any(), endpoint)
        assertEquals(0, endpoint.calls)
    }
    @Test fun duplicateReadyEventsAndWindowChangesStillSendOncePerConnection() {
        val probe = LvdsRequestProbe(); val endpoint = Endpoint(); val owner = Any(); val window = Any()
        probe.connect(owner); probe.frame(window)
        repeat(5) { probe.onProjectionSourceReady(context, window, endpoint) }
        probe.connect(owner); val replacement = Any(); probe.frame(replacement)
        probe.onProjectionSourceReady(context, replacement, endpoint)
        assertEquals(1, endpoint.calls)
    }
    @Test fun unavailableAgentIsConsumedWithoutFallbackOrResend() {
        val probe = LvdsRequestProbe(); val endpoint = Endpoint(supported = false); val window = Any()
        probe.connect(Any()); probe.frame(window)
        repeat(3) { probe.onProjectionSourceReady(context, window, endpoint) }
        assertEquals(1, endpoint.calls)
        assertFalse(probe.dispatchProjectionLayout(context, ProjectionLayout.FULLSCREEN, endpoint))
        assertFalse(probe.dispatchProjectionLayout(context, ProjectionLayout.CARD, endpoint))
        assertEquals(1, endpoint.calls)
    }
    @Test fun replacementConnectionGetsNewTicketAndLateOldDisconnectDoesNotDisarmIt() {
        val probe = LvdsRequestProbe(); val endpoint = Endpoint(); val old = Any(); val window = Any()
        probe.connect(old); probe.frame(window); probe.onProjectionSourceReady(context, window, endpoint)
        val fresh = Any(); probe.connect(fresh); probe.onWirelessAudioEnded(old)
        probe.onProjectionSourceReady(context, window, endpoint) // Old frame was discarded.
        assertEquals(1, endpoint.calls)
        probe.frame(window); probe.onProjectionSourceReady(context, window, endpoint)
        assertEquals(2, endpoint.calls); assertNotEquals(endpoint.tickets[0], endpoint.tickets[1])
    }
    @Test fun contentLossAndSessionEndPreventLateRequests() {
        val probe = LvdsRequestProbe(); val endpoint = Endpoint(); val window = Any()
        probe.connect(Any()); probe.frame(window); probe.onProjectionContentLost(window)
        probe.onProjectionSourceReady(context, window, endpoint)
        probe.frame(window); probe.onSessionEnded(); probe.onProjectionSourceReady(context, window, endpoint)
        assertEquals(0, endpoint.calls)
    }
    @Test fun handlerResultNeverClaimsPanelSuccess() {
        assertEquals("original-handler-returned", LvdsProbeProtocol.request(Endpoint(), "test"))
        assertEquals("original-handler-not-ready", LvdsProbeProtocol.request(Endpoint(0), "test"))
        assertEquals("rejected-or-expired", LvdsProbeProtocol.request(Endpoint(-1), "test"))
        assertEquals("already-consumed", LvdsProbeProtocol.request(Endpoint(-2), "test"))
    }
}
