package com.freddyq.diplay.changan.media

import android.content.*
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import com.freddyq.diplay.customization.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** One current snapshot, one pending image and one queued wake; Binder/file work never holds the UI lock. */
class ChanganMediaCustomization internal constructor(private val testWorker: Handler?, private val random: java.util.Random = java.util.Random()) : VehicleCustomization {
    constructor() : this(null)
    override val id = "changan-native-media"
    private val main = Handler(Looper.getMainLooper())
    private val worker by lazy { testWorker ?: Handler(HandlerThread("diplay-native-media").apply { start() }.looper) }
    private val localItemIndices = AtomicInteger(0)
    private var current: Session? = null
    private data class Cover(val key: CoverKey, val bitmap: Bitmap)

    private fun report(message: String) = VehicleCustomizations.diagnostic("ChanganMedia", message)
    @Synchronized override fun onMediaSessionStarted(context: Context, owner: Any) {
        if (current?.owner === owner) return
        current?.close()
        current = Session(context.applicationContext, owner)
        report("session started; display data only")
    }
    @Synchronized override fun onMediaSnapshot(context: Context, owner: Any, snapshot: MediaSnapshot) {
        val session = current?.takeIf { it.owner === owner } ?: return
        val previous = session.latest.getAndSet(snapshot)
        if (session.eligible(snapshot)) session.wake(urgent = previous?.item != snapshot.item || previous?.playing != snapshot.playing)
        // No synthetic paused item and no clearing/unbinding on an empty phone update.
    }
    @Synchronized override fun onMediaArtwork(context: Context, owner: Any, item: MediaItem, bitmap: Bitmap?) {
        val session = current?.takeIf { it.owner === owner } ?: return
        if (bitmap == null) { session.pending.getAndSet(null)?.bitmap?.recycle(); return }
        if (bitmap.width !in 1..384 || bitmap.height !in 1..384) return
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        session.pending.getAndSet(Cover(CoverKey(item), copy))?.bitmap?.recycle()
        session.wake(urgent = true)
    }
    @Synchronized override fun onMediaSessionEnded(owner: Any) {
        if (current?.owner !== owner) return
        current?.close(); current = null
        report("session ended; publication stopped")
    }

    private inner class Session(val context: Context, val owner: Any) {
        val latest = AtomicReference<MediaSnapshot?>()
        val pending = AtomicReference<Cover?>()
        private val live = AtomicBoolean(true)
        private val scheduleLock = Any()
        private var scheduled = false
        @Volatile private var remote: IBinder? = null
        @Volatile private var remoteEpoch = 0
        private var bound = false // Main thread only.
        @Volatile private var attempts = 0 // Written on main; maximum three binds per phone session.
        private var track: TrackKey? = null
        private var sourceId = 0
        private var coverKey: CoverKey? = null
        private var coverFile: File? = null
        private var sentCover: Triple<Int, Int, File>? = null
        private var lastStatus: Pair<Boolean, Boolean>? = null
        private var lastPlaying: Boolean? = null
        private var lastMetadata: Pair<Int, String>? = null
        private var ticks = 0
        private val cache by lazy {
            MediaCoverCache(context.getExternalFilesDir("incall-media-covers")
                ?: throw java.io.IOException("external cover storage unavailable"))
        }
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (!live.get()) { disconnect(); return }
                remote = service
                remoteEpoch++
                report("native service connected")
                wake(urgent = true)
            }
            override fun onServiceDisconnected(name: ComponentName?) { disconnect(); wake() }
            override fun onBindingDied(name: ComponentName?) { disconnect(); wake() }
            override fun onNullBinding(name: ComponentName?) { disconnect(); report("native service returned no binder"); wake(1_000) }
        }
        // Paused metadata is valid. It stays cached at the native receiver, without an invented heartbeat.
        fun eligible(snapshot: MediaSnapshot?) = live.get() && snapshot != null &&
            !snapshot.item.title.isNullOrBlank() && (!snapshot.playing || snapshot.outputOwned)
        fun wake(delay: Long = 0, urgent: Boolean = false): Unit = synchronized(scheduleLock) {
            if (!live.get()) return@synchronized
            if (scheduled) {
                if (!urgent) return@synchronized
                worker.removeCallbacks(tick)
            }
            scheduled = true
            worker.postDelayed(tick, delay)
            Unit
        }
        private fun same(snapshot: MediaSnapshot) = eligible(snapshot) && latest.get() === snapshot
        private val tick: Runnable = Runnable {
            synchronized(scheduleLock) { scheduled = false }
            val snapshot = latest.get()
            if (snapshot != null && eligible(snapshot)) {
                try { publish(snapshot) }
                catch (error: Exception) {
                    report("display publication failed: ${error.javaClass.simpleName}")
                    main.post { disconnect(); wake(1_000) }
                } finally {
                    if (!live.get()) pending.getAndSet(null)?.bitmap?.recycle()
                }
                val next = latest.get()
                if (eligible(next) && (next?.playing == true ||
                        (remote == null && attempts < 3) || (remote != null && pending.get() != null))) wake(1_000)
            }
        }
        private fun publish(snapshot: MediaSnapshot) {
            val binder = remote
            val epoch = remoteEpoch
            if (binder?.isBinderAlive != true) { main.post { connect() }; return }
            if (binder.interfaceDescriptor != IncallMediaProtocol.TOKEN) throw java.io.IOException("unexpected native descriptor")
            val key = TrackKey(snapshot.item)
            if (track != key) {
                track = key
                // OEM Utils.getRandomNum: 100000 + itemIndex + random(0..999).
                // CarPlay has no WecarFlow SDK itemIndex; use this session's local item index.
                val index = localItemIndices.getAndUpdate { (it + 1) % 1_000_000 }
                sourceId = 100000 + index + random.nextInt(1000)
                sentCover = null
                report("track changed sourceID=$sourceId")
            }
            if (!same(snapshot)) return
            val protocol = IncallMediaProtocol(binder)
            val json = IncallMediaProtocol.metadataJson(sourceId, snapshot)
            val signature = epoch to json
            if (snapshot.playing || lastMetadata != signature || pending.get()?.key == CoverKey(snapshot.item)) {
                protocol.metadata(json)
                lastMetadata = signature
            }
            if (!same(snapshot)) return
            // OEM pause clears mOldAlbumPath. Re-send once at the pause transition, not every heartbeat.
            if (lastPlaying == true && !snapshot.playing) sentCover = null
            lastPlaying = snapshot.playing
            val artwork = pending.getAndSet(null)
            if (artwork != null) {
                try {
                    if (artwork.key == CoverKey(snapshot.item)) {
                        val saved = cache.store(artwork.bitmap)
                        if (saved == null) {
                            if (!pending.compareAndSet(null, artwork)) artwork.bitmap.recycle()
                        } else {
                            coverKey = artwork.key; coverFile = saved; artwork.bitmap.recycle()
                        }
                    } else artwork.bitmap.recycle()
                } catch (error: Exception) {
                    artwork.bitmap.recycle()
                    report("cover preparation failed: ${error.javaClass.simpleName}")
                }
            }
            val file = coverFile
            if (same(snapshot) && coverKey == CoverKey(snapshot.item) && file?.isFile == true && sentCover != Triple(epoch, sourceId, file)) {
                file.setLastModified(System.currentTimeMillis())
                protocol.cover(sourceId, file)
                sentCover = Triple(epoch, sourceId, file)
                report("cover dispatched sourceID=$sourceId bytes=${file.length()}")
            }
            if (!same(snapshot)) return
            protocol.progress(sourceId, snapshot, SystemClock.elapsedRealtime())
            val status = snapshot.playing to snapshot.outputOwned
            if (status != lastStatus) { report("display playing=${snapshot.playing} owned=${snapshot.outputOwned}"); lastStatus = status }
            if (++ticks % 60 == 0) report("publication ticks=$ticks; progress only while playing; covers on change/pause")
        }
        private fun connect() {
            if (!eligible(latest.get()) || bound || attempts >= 3) return
            attempts++
            try {
                bound = context.bindService(Intent().setComponent(ComponentName("com.incall.interactive",
                    "com.incall.interactive.service.DoubleInteractiveService")), connection, Context.BIND_AUTO_CREATE)
                if (!bound) report("native bind rejected attempt=$attempts")
            } catch (error: Exception) { report("native bind failed: ${error.javaClass.simpleName}") }
        }
        fun disconnect() {
            remote = null
            if (bound) { runCatching { context.unbindService(connection) }; bound = false }
        }
        fun close() {
            live.set(false); pending.getAndSet(null)?.bitmap?.recycle()
            synchronized(scheduleLock) {
                if (scheduled) worker.removeCallbacks(tick)
                scheduled = false
            }
            main.post { disconnect() }
        }
    }
}

class ChanganMediaInitializer : ContentProvider() {
    override fun onCreate(): Boolean { VehicleCustomizations.register(ChanganMediaCustomization()); return true }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
