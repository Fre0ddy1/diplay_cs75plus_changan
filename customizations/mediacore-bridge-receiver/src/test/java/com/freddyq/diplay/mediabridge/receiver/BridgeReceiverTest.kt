package com.freddyq.diplay.mediabridge.receiver

import android.graphics.Bitmap
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import com.freddyq.diplay.mediabridge.contract.BridgeReceipt
import com.freddyq.diplay.mediabridge.contract.IMediaPublishBridge
import com.freddyq.diplay.mediabridge.contract.MediaFrame
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BridgeReceiverTest {
    private lateinit var dir: File
    private lateinit var store: CoverStore
    private lateinit var state: BridgeState
    private lateinit var endpoint: BridgeEndpoint
    private lateinit var remote: IMediaPublishBridge
    private val token = Binder()
    private var staged: PublishedMedia? = null
    private var reject = false
    @Before fun setup() {
        dir = Files.createTempDirectory("bridge-covers").toFile()
        store = CoverStore(dir, "test.covers")
        state = BridgeState(store, object : ProviderSink {
            override val stage = BridgeReceipt.LOCAL_MODEL
            override fun stage(media: PublishedMedia?) { if (reject) error("Port missing"); staged = media }
        })
        endpoint = BridgeEndpoint(state) { true }
        // Force generated AIDL Proxy -> Parcel -> Stub, rather than the same-process fast path.
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) = endpoint.transact(code, data, reply, flags)
        }
        remote = IMediaPublishBridge.Stub.asInterface(binder)
        assertFalse(remote === endpoint)
        assertEquals("OK", remote.begin(token).getString("code"))
    }
    @After fun teardown() { reject = false; endpoint.close(); dir.deleteRecursively() }
    private fun publish(f: MediaFrame, seq: Long = 0, jpg: ByteArray? = null) = remote.publish(token, seq, f.toBundle(), jpg)
    private fun playing(cover: String = MediaFrame.KEEP) = MediaFrame("a", "歌名", "歌手", playing = true,
        outputOwned = true, sourceSelected = true, positionMs = 6000, durationMs = 180000, cover = cover)
    private fun jpeg(edge: Int = 32): ByteArray {
        val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out); bitmap.recycle(); out.toByteArray() }
    }

    @Test fun pauseRetainsCoverAndMetadataWithoutSelectingAnAudioSource() {
        assertEquals("OK", publish(playing(MediaFrame.REPLACE), jpg = jpeg()).getString("code"))
        val uri = staged!!.coverUri
        publish(playing().copy(playing = false), 1)
        assertEquals(uri, staged!!.coverUri); assertEquals("歌名", staged!!.frame.title)
        assertFalse(staged!!.frame.playing)
        assertEquals(BridgeReceipt.LOCAL_MODEL, remote.readback().getString("stage"))
    }
    @Test fun progressKeepsSameUri() {
        publish(playing(MediaFrame.REPLACE), jpg = jpeg()); val uri = staged!!.coverUri
        publish(playing().copy(positionMs = 8000), 1)
        assertEquals(uri, staged!!.coverUri); assertEquals(1, dir.listFiles()!!.size)
    }
    @Test fun disconnectClearsStateAndFiles() {
        publish(playing(MediaFrame.REPLACE), jpg = jpeg()); remote.end(token)
        assertNull(staged); assertFalse(remote.readback().getBoolean("connected")); assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun previousOwnerCannotPublishOrClearNewOwner() {
        remote.begin(Binder())
        assertEquals("STALE_OWNER", publish(playing()).getString("code"))
        assertEquals("STALE_OWNER", remote.end(token).getString("code"))
        assertTrue(remote.readback().getBoolean("connected"))
    }
    @Test fun duplicateAndReorderedSequencesRejected() {
        publish(playing(), 2)
        assertEquals("STALE_SEQUENCE", publish(playing(), 2).getString("code"))
        assertEquals("STALE_SEQUENCE", publish(playing(), 1).getString("code"))
    }
    @Test fun newTrackDoesNotReuseOldArtwork() {
        publish(playing(MediaFrame.REPLACE), jpg = jpeg()); publish(playing().copy(track = "b"), 1)
        assertNull(staged!!.coverUri); assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun failedPortIsNeverReportedAsAcceptedAndDoesNotAdvanceSequence() {
        reject = true
        assertEquals("RECEIVER_NOT_READY", publish(playing()).getString("code"))
        reject = false
        assertEquals("OK", publish(playing()).getString("code"))
    }
    @Test fun oversizedAndInvalidImagesRejectedWithoutMutation() {
        assertEquals("INVALID_FRAME", publish(playing(MediaFrame.REPLACE), jpg = ByteArray(131073)).getString("code"))
        assertEquals("INVALID_FRAME", publish(playing(MediaFrame.REPLACE), jpg = byteArrayOf(1, 2, 3, 4)).getString("code"))
        assertNull(staged); assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun oversizedDimensionsRejectedBeforeDecode() {
        assertEquals("INVALID_FRAME", publish(playing(MediaFrame.REPLACE), jpg = jpeg(385)).getString("code"))
        assertNull(staged)
    }
    @Test fun unexpectedImageWithKeepRejected() {
        assertEquals("INVALID_FRAME", publish(playing(), jpg = jpeg()).getString("code"))
    }
    @Test fun failedReplacementKeepsPreviousCover() {
        publish(playing(MediaFrame.REPLACE), jpg = jpeg()); val uri = staged!!.coverUri
        reject = true; publish(playing(MediaFrame.REPLACE), 1, jpeg()); reject = false
        assertEquals(uri, staged!!.coverUri); assertTrue(store.resolve(android.net.Uri.parse(uri)).isFile)
        assertEquals(1, dir.listFiles()!!.size)
    }
    @Test fun liveCacheNeverExceedsTwoCovers() {
        repeat(5) { publish(playing(MediaFrame.REPLACE), it.toLong(), jpeg()); assertTrue(dir.listFiles()!!.size <= 2) }
    }
    @Test fun unauthorizedCallerRejectedBeforeReadOrWrite() {
        val denied = BridgeEndpoint(state) { false }
        try { try { denied.readback(); fail("Read must be protected") } catch (_: SecurityException) { } }
        finally { denied.close() }
    }
    @Test fun binderDeathClearsOnlyMatchingOwner() {
        class Token : Binder() {
            var recipient: IBinder.DeathRecipient? = null
            override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { this.recipient = recipient }
            override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
        }
        val old = Token(); remote.begin(old); val oldDeath = old.recipient!!
        val latest = Token(); remote.begin(latest)
        oldDeath.binderDied(); assertTrue(remote.readback().getBoolean("connected"))
        latest.recipient!!.binderDied(); assertFalse(remote.readback().getBoolean("connected"))
    }
    @Test fun coverProviderPathCannotEscapeStore() {
        try { store.resolve(android.net.Uri.parse("content://test.covers/..%2Fsecret.jpg")); fail("Traversal") }
        catch (_: IllegalArgumentException) { }
    }
}
