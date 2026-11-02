package com.freddyq.diplay.mediabridge.publisher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import com.freddyq.diplay.customization.MediaItem
import com.freddyq.diplay.customization.MediaSnapshot
import com.freddyq.diplay.customization.VehicleCustomization
import com.freddyq.diplay.mediabridge.contract.IMediaPublishBridge
import com.freddyq.diplay.mediabridge.contract.MediaFrame
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in library, NOT registered in DiPlay 71. No audio focus, player, media-key or VHAL operations. */
class MediaCorePublisher(
    private val target: ComponentName,
    private val receipt: (Bundle) -> Unit = {},
) : VehicleCustomization, Closeable {
    override val id = "mediacore-publish-local"
    private val closed = AtomicBoolean()
    private val thread = HandlerThread("MediaPublishClient").apply { start() }
    private val handler = Handler(thread.looper)
    private var owner: Any? = null
    private var token = Binder()
    private var app: Context? = null
    private var connection: ServiceConnection? = null
    private var service: IMediaPublishBridge? = null
    private var snapshot: MediaSnapshot? = null
    private var sequence = 0L
    private var track: String? = null
    private var image: ByteArray? = null
    private var coverDirty = false
    private var tickScheduled = false
    private var pendingArtwork: Pair<String, ByteArray?>? = null
    private val tick = object : Runnable {
        override fun run() {
            tickScheduled = false
            if (snapshot?.playing == true) {
                flush()
                tickScheduled = true
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onMediaSessionStarted(context: Context, owner: Any) {
        if (closed.get()) return
        handler.post {
            if (this.owner === owner) return@post
            endCurrent()
            this.owner = owner; token = Binder(); app = context.applicationContext
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    handler.post connected@{
                        if (this@MediaCorePublisher.owner !== owner) return@connected
                        val remote = IMediaPublishBridge.Stub.asInterface(binder)
                        runCatching {
                            val result = remote.begin(token)
                            receipt(result)
                            if (result.getString("code") == "OK") {
                                service = remote; coverDirty = true; flush()
                            }
                        }.onFailure { unavailable() }
                    }
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    handler.post { if (this@MediaCorePublisher.owner === owner) { service = null; coverDirty = true } }
                }
                override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
                override fun onNullBinding(name: ComponentName) { onServiceDisconnected(name) }
            }
            if (runCatching { app!!.bindService(Intent().setComponent(target), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)) {
                connection = conn
            } else unavailable()
        }
    }

    override fun onMediaSnapshot(context: Context, owner: Any, snapshot: MediaSnapshot) {
        if (closed.get()) return
        handler.post {
            if (this.owner !== owner) return@post
            val next = trackKey(snapshot.item)
            val important = this.snapshot?.let {
                it.item != snapshot.item || it.playing != snapshot.playing || it.outputOwned != snapshot.outputOwned ||
                    it.sourceSelected != snapshot.sourceSelected
            } != false
            if (track != next) {
                track = next; image = pendingArtwork?.takeIf { it.first == next }?.second; coverDirty = true
            }
            this.snapshot = snapshot
            if (important) flush() // Progress-only callbacks coalesce into the 1 Hz timer.
            if (!snapshot.playing) { handler.removeCallbacks(tick); tickScheduled = false }
            else if (!tickScheduled) { tickScheduled = true; handler.postDelayed(tick, 1000) }
        }
    }

    override fun onMediaArtwork(context: Context, owner: Any, item: MediaItem, bitmap: Bitmap?) {
        if (closed.get()) return
        // Own a copy; the decoder may recycle the callback bitmap after returning.
        val copy = bitmap?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() }
        if (bitmap != null && copy == null) return
        if (!handler.post {
            try {
                if (this.owner !== owner) return@post
                val key = trackKey(item)
                val bytes = copy?.let { encode(it) }
                if (copy != null && bytes == null) return@post
                pendingArtwork = key to bytes
                if (track == key && !(image?.contentEquals(bytes ?: byteArrayOf()) ?: (bytes == null))) {
                    image = bytes; coverDirty = true; flush()
                }
            } finally { copy?.recycle() }
        }) copy?.recycle()
    }

    override fun onMediaSessionEnded(owner: Any) { if (!closed.get()) handler.post { if (this.owner === owner) endCurrent() } }

    fun readback(completed: (Bundle) -> Unit) {
        if (closed.get()) { completed(Bundle().apply { putString("code", "NOT_BOUND") }); return }
        handler.post {
            completed(runCatching { service?.readback() }.getOrNull() ?: Bundle().apply { putString("code", "NOT_BOUND") })
        }
    }

    private fun flush() {
        val state = snapshot ?: return
        val remote = service ?: return
        val cover = if (!coverDirty) MediaFrame.KEEP else if (image == null) MediaFrame.CLEAR else MediaFrame.REPLACE
        val duration = (state.item.durationMillis ?: 0).coerceAtLeast(0)
        val frame = MediaFrame(
            track = track ?: return,
            title = state.item.title.orEmpty().take(MediaFrame.MAX_TEXT),
            artist = state.item.artist.orEmpty().take(MediaFrame.MAX_TEXT),
            album = state.item.album.orEmpty().take(MediaFrame.MAX_TEXT),
            playing = state.playing, outputOwned = state.outputOwned, sourceSelected = state.sourceSelected,
            positionMs = (state.positionAt(SystemClock.elapsedRealtime()) ?: 0).coerceAtLeast(0)
                .let { if (duration > 0) it.coerceAtMost(duration) else it },
            durationMs = duration,
            // Media API has no proven AudioTrack session ID. Never use a display sourceId here.
            audioSessionId = null, cover = cover,
        )
        runCatching {
            val result = remote.publish(token, sequence++, frame.toBundle(), if (cover == MediaFrame.REPLACE) image else null)
            receipt(result)
            if (result.getString("code") == "OK") coverDirty = false
            else if (result.getString("code") == "STALE_OWNER") service = null
        }.onFailure { service = null; unavailable() }
    }

    private fun endCurrent() {
        handler.removeCallbacks(tick)
        tickScheduled = false
        runCatching { service?.end(token)?.let(receipt) }
        connection?.let { runCatching { app?.unbindService(it) } }
        owner = null; service = null; connection = null; app = null; snapshot = null
        sequence = 0; track = null; image = null; coverDirty = false; pendingArtwork = null
    }
    private fun unavailable() { receipt(Bundle().apply { putString("code", "RECEIVER_UNAVAILABLE"); putString("stage", "NOT_CONNECTED") }) }
    override fun close() { if (closed.compareAndSet(false, true)) handler.post { endCurrent(); thread.quitSafely() } }

    companion object {
        fun trackKey(item: MediaItem): String = item.artworkId?.let { "art:$it" } ?: run {
            val text = listOf(item.title, item.artist, item.album).joinToString("\u0000") { it.orEmpty() }
            "text:" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        private fun encode(source: Bitmap): ByteArray? {
            val edge = maxOf(source.width, source.height)
            val scaled = if (edge <= MediaFrame.MAX_COVER_EDGE) source else Bitmap.createScaledBitmap(source,
                (source.width.toLong() * MediaFrame.MAX_COVER_EDGE / edge).toInt().coerceAtLeast(1),
                (source.height.toLong() * MediaFrame.MAX_COVER_EDGE / edge).toInt().coerceAtLeast(1), true)
            try {
                for (quality in listOf(85, 60, 40)) {
                    val out = ByteArrayOutputStream()
                    if (scaled.compress(Bitmap.CompressFormat.JPEG, quality, out) && out.size() <= MediaFrame.MAX_JPEG_BYTES) return out.toByteArray()
                }
                return null
            } finally { if (scaled !== source) scaled.recycle() }
        }
    }
}
