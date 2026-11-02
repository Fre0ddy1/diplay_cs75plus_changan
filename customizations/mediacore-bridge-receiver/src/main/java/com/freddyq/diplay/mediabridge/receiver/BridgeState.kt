package com.freddyq.diplay.mediabridge.receiver

import android.os.Bundle
import android.os.IBinder
import com.freddyq.diplay.mediabridge.contract.BridgeReceipt
import com.freddyq.diplay.mediabridge.contract.MediaFrame

data class PublishedMedia(val frame: MediaFrame, val coverUri: String?)

/** Must update only this provider's local state and enqueue observers, never switch/play an audio source. */
interface ProviderSink {
    val stage: String
    fun stage(media: PublishedMedia?)
}

/** Called only on the endpoint's single worker, including death cleanup. */
class BridgeState(private val covers: CoverStore, private val sink: ProviderSink) {
    private var owner: IBinder? = null
    private var sequence = -1L
    private var current: PublishedMedia? = null

    fun begin(token: IBinder): Bundle {
        if (owner == token) return receipt("OK")
        sink.stage(null) // Failure leaves the previous session intact; no false acceptance.
        owner = token; sequence = -1; current = null; covers.retain(null)
        return receipt("OK")
    }

    fun publish(token: IBinder, next: Long, bundle: Bundle, jpeg: ByteArray?): Bundle {
        if (owner != token) return receipt("STALE_OWNER")
        if (next < 0 || next <= sequence) return receipt("STALE_SEQUENCE")
        val frame = MediaFrame.fromBundle(bundle)
        require(if (frame.cover == MediaFrame.REPLACE) jpeg != null else jpeg == null)
        val uri = when (frame.cover) {
            MediaFrame.REPLACE -> covers.put(jpeg!!)
            MediaFrame.CLEAR -> null
            else -> current?.takeIf { it.frame.track == frame.track }?.coverUri
        }
        val updated = PublishedMedia(frame, uri)
        try { sink.stage(updated) }
        catch (e: Exception) { covers.retain(current?.coverUri); throw e }
        covers.retain(uri, if (uri != null) current?.coverUri else null)
        current = updated; sequence = next
        return receipt("OK")
    }

    fun end(token: IBinder): Bundle {
        if (owner != token) return receipt("STALE_OWNER")
        sink.stage(null); current = null; owner = null; sequence = -1; covers.retain(null)
        return receipt("OK")
    }

    fun readback() = receipt("OK").apply {
        putBoolean("connected", owner != null)
        putInt("provider", MediaFrame.CARPLAY_PROVIDER)
        current?.let { putBundle("media", it.frame.toBundle()); putString("coverUri", it.coverUri) }
    }

    private fun receipt(code: String) = BridgeReceipt.of(code, sink.stage, sequence)
}
