package com.freddyq.diplay.mediabridge.lab

import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.*
import com.freddyq.diplay.customization.MediaItem
import com.freddyq.diplay.customization.MediaSnapshot
import com.freddyq.diplay.mediabridge.contract.BridgeReceipt
import com.freddyq.diplay.mediabridge.contract.MediaFrame
import com.freddyq.diplay.mediabridge.publisher.MediaCorePublisher
import com.freddyq.diplay.mediabridge.receiver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PublisherReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var endpoint: BridgeEndpoint
    private lateinit var publisher: MediaCorePublisher
    private lateinit var dir: File
    private val owner = Any()
    private var binds = 0
    private var unbinds = 0
    private var unavailable = false
    private val receipts = java.util.concurrent.CopyOnWriteArrayList<Bundle>()
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            binds++
            assertEquals("local.host", intent.component!!.packageName)
            if (unavailable) return false
            val proxyBinder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) = endpoint.transact(code, data, reply, flags)
            }
            connection.onServiceConnected(intent.component, proxyBinder)
            return true
        }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }
    @Before fun setup() {
        dir = File(app.filesDir, "mediacore-bridge-covers").apply { mkdirs() }
        val sink = object : ProviderSink {
            override val stage = BridgeReceipt.LOCAL_MODEL
            override fun stage(media: PublishedMedia?) { }
        }
        endpoint = BridgeEndpoint(BridgeState(CoverStore(dir, app.packageName + ".mediacore.bridge.covers"), sink)) { true }
        publisher = MediaCorePublisher(ComponentName("local.host", "local.host.Receiver")) { receipts += it }
    }
    @After fun teardown() { publisher.close(); endpoint.close(); dir.deleteRecursively() }
    private fun value(playing: Boolean = true, id: Int = 1) = MediaSnapshot(
        MediaItem("曲$id", "歌手", "专辑", 180000, id), playing, 1000, SystemClock.elapsedRealtime(), true)
    private fun read(): Bundle {
        val future = CompletableFuture<Bundle>()
        publisher.readback { future.complete(it) }
        return future.get(3, TimeUnit.SECONDS)
    }
    private fun begin(v: MediaSnapshot = value()) {
        publisher.onMediaSessionStarted(context, owner); publisher.onMediaSnapshot(context, owner, v)
        read(); read() // Drain the bind callback queued by session start, then publication.
    }
    private fun artwork(v: MediaSnapshot = value(), color: Int = Color.BLUE) {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        publisher.onMediaArtwork(context, owner, v.item, bitmap); bitmap.recycle(); read()
    }
    @Test fun completeCallbackParcelReceiverPauseAndDisconnectPath() {
        begin(); artwork(); val first = read()
        assertEquals(BridgeReceipt.LOCAL_MODEL, first.getString("stage")); assertEquals(12, first.getInt("provider"))
        assertNotNull(first.getString("coverUri")); assertNull(MediaFrame.fromBundle(first.getBundle("media")!!).audioSessionId)
        publisher.onMediaSnapshot(context, owner, value(false)); val paused = read()
        assertFalse(MediaFrame.fromBundle(paused.getBundle("media")!!).playing)
        assertEquals(first.getString("coverUri"), paused.getString("coverUri"))
        publisher.onMediaSessionEnded(owner); read()
        assertFalse(endpoint.readback().getBoolean("connected")); assertTrue(dir.listFiles()!!.isEmpty())
        assertEquals(1, binds); assertEquals(1, unbinds)
        assertNull(Shadows.shadowOf(app.getSystemService(AudioManager::class.java)).lastAudioFocusRequest)
    }
    @Test fun repeatedSameArtworkDoesNotGenerateAnotherUri() {
        begin(); artwork(); val uri = read().getString("coverUri")
        artwork(); assertEquals(uri, read().getString("coverUri"))
    }
    @Test fun oldTrackArtworkCannotOverwriteCurrentTrack() {
        begin(); artwork(); publisher.onMediaSnapshot(context, owner, value(id = 2)); read()
        artwork(value(id = 1))
        assertNull(read().getString("coverUri"))
        artwork(value(id = 2)); assertNotNull(read().getString("coverUri"))
    }
    @Test fun previousOwnerEndAndArtworkDoNotTouchCurrentSession() {
        begin(); publisher.onMediaSessionStarted(context, "new-owner")
        publisher.onMediaSnapshot(context, "new-owner", value(id = 2)); read(); read()
        publisher.onMediaSessionEnded(owner); artwork()
        val result = read()
        assertTrue(result.getBoolean("connected")); assertEquals("曲2", result.getBundle("media")!!.getString("title"))
    }
    @Test fun artworkArrivingBeforeMetadataIsUsedWhenTrackMatches() {
        publisher.onMediaSessionStarted(context, owner); artwork(); publisher.onMediaSnapshot(context, owner, value())
        read(); assertNotNull(read().getString("coverUri"))
    }
    @Test fun absentReceiverProducesUnavailableInsteadOfOemSuccess() {
        unavailable = true; begin()
        assertEquals("NOT_BOUND", read().getString("code"))
        assertTrue(receipts.any { it.getString("code") == "RECEIVER_UNAVAILABLE" })
        assertFalse(receipts.any { it.getString("stage") == BridgeReceipt.OEM_PROVIDER_STAGED })
    }
    @Test fun localCoverCanBeReadBySceneHelperAndOtherAuthoritiesAreRejected() {
        begin(); artwork(); val uri = read().getString("coverUri")!!
        val controller = Robolectric.buildContentProvider(BridgeCoverProvider::class.java).create(app.packageName + ".mediacore.bridge.covers")
        val bitmap = SceneCompatibility.loadCover(app.contentResolver, uri, app.packageName + ".mediacore.bridge.covers")
        assertNotNull(bitmap); assertEquals(64, bitmap!!.width); bitmap.recycle()
        assertNull(SceneCompatibility.loadCover(app.contentResolver, uri, "other.authority"))
        assertNull(SceneCompatibility.loadCover(app.contentResolver, "file:///private/cover.jpg", app.packageName + ".mediacore.bridge.covers"))
        try { controller.get().openFile(Uri.parse(uri), "w"); fail("Write must be denied") } catch (_: IllegalArgumentException) { }
    }
}
