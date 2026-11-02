package com.freddyq.diplay.mediabridge.receiver

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import com.freddyq.diplay.mediabridge.contract.BridgeReceipt
import com.freddyq.diplay.mediabridge.contract.IMediaPublishBridge
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** All state and sink callbacks run in order, outside Binder threads and state locks. */
class BridgeEndpoint(
    private val state: BridgeState,
    private val authorize: (Int) -> Boolean,
) : IMediaPublishBridge.Stub(), Closeable {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "MediaPublishReceiver") }
    private var owner: IBinder? = null
    private var death: IBinder.DeathRecipient? = null

    override fun begin(token: IBinder): Bundle = call {
        if (owner == token) return@call state.begin(token)
        val recipient = IBinder.DeathRecipient {
            runCatching { worker.execute { if (owner == token) clear(token) } }
        }
        token.linkToDeath(recipient, 0)
        try {
            val result = state.begin(token)
            unlink(); owner = token; death = recipient
            result
        } catch (e: Exception) { token.unlinkToDeath(recipient, 0); throw e }
    }

    override fun publish(token: IBinder, sequence: Long, media: Bundle, jpeg: ByteArray?): Bundle =
        call { state.publish(token, sequence, media, jpeg) }

    override fun end(token: IBinder): Bundle = call { clear(token) }
    override fun readback(): Bundle = call { state.readback() }

    private fun clear(token: IBinder): Bundle {
        val result = state.end(token)
        if (owner == token) { unlink(); owner = null }
        return result
    }
    private fun unlink() { death?.let { owner?.unlinkToDeath(it, 0) }; death = null }

    private fun call(action: () -> Bundle): Bundle {
        if (!authorize(Binder.getCallingUid())) throw SecurityException("Publisher UID denied")
        val task = worker.submit<Bundle> {
            try { action() }
            catch (e: IllegalArgumentException) { BridgeReceipt.of("INVALID_FRAME", "REJECTED") }
            catch (e: Exception) { BridgeReceipt.of("RECEIVER_NOT_READY", "REJECTED") }
        }
        return try { task.get(3, TimeUnit.SECONDS) }
        catch (e: Exception) {
            task.cancel(false)
            // Timeout is not evidence that the native port did or did not apply an update.
            BridgeReceipt.of("UNKNOWN_TIMEOUT", "UNKNOWN")
        }
    }

    override fun close() {
        runCatching { worker.submit { owner?.let { clear(it) } }.get(3, TimeUnit.SECONDS) }
        worker.shutdownNow()
    }
}
