package com.freddyq.diplay.changan.media

import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.os.*
import com.freddyq.diplay.customization.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ChanganMediaPublisherTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val transactions = mutableListOf<Int>()
    private val metadata = mutableListOf<JSONObject>()
    private val progress = mutableListOf<Triple<Int, Int, Int>>()
    private val covers = mutableListOf<Pair<Int, File>>()
    private var onMetadata: (() -> Unit)? = null
    private val native = object : Binder() {
        init { attachInterface(null, IncallMediaProtocol.TOKEN) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(IncallMediaProtocol.TOKEN); transactions += code
            when (code) {
                37 -> { metadata += JSONObject(data.readString()!!); onMetadata?.invoke() }
                38 -> progress += Triple(data.readInt(), data.readInt(), data.readInt())
                39 -> { val id = data.readInt(); data.readString(); covers += id to File(data.readString()!!) }
                else -> fail("display module issued a control transaction: $code")
            }
            reply!!.writeNoException(); return true
        }
    }
    private val folder by lazy { File(app.cacheDir, "native-media-${UUID.randomUUID()}").apply { mkdirs() } }
    private var binds = 0
    private var unbinds = 0
    private var rejectBinding = false
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun getExternalFilesDir(type: String?): File = folder
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            binds++
            assertEquals("com.incall.interactive", intent.component!!.packageName)
            if (rejectBinding) return false
            connection.onServiceConnected(intent.component, native); return true
        }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }
    private val module = ChanganMediaCustomization(Handler(Looper.getMainLooper()))
    private val owner = Any()
    private fun idle(ms: Long) = Shadows.shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    private fun snapshot(title: String = "QQ歌曲A", playing: Boolean = true, position: Long = 2000, owned: Boolean = true) =
        MediaSnapshot(MediaItem(title, "歌手", "专辑", 60000, 3), playing, position, SystemClock.elapsedRealtime(), owned)
    private fun begin(value: MediaSnapshot = snapshot()) {
        module.onMediaSessionStarted(context, owner); module.onMediaSnapshot(context, owner, value)
    }
    private fun image(color: Int) = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    @After fun end() { module.onMediaSessionEnded(owner); idle(0) }

    @Test fun continuousStateAndProgressUseOneStableIdWithoutAudioControls() {
        begin(); idle(4200)
        assertTrue(metadata.size >= 4); assertEquals(metadata.size, progress.size)
        assertEquals(1, metadata.map { it.getInt("sourceID") }.distinct().size)
        assertEquals(1, binds)
        assertTrue(transactions.all { it == 37 || it == 38 })
        assertNull(Shadows.shadowOf(app.getSystemService(AudioManager::class.java)).lastAudioFocusRequest)
    }
    @Test fun qqLiveLyricsKeepOneIdentityAndOneCoverWhileTheCaptionChanges() {
        val value = snapshot().copy(item = MediaItem("藕虽断了丝还连", "爱江山更爱美人 (Live)", "任贤齐/刘宇宁", 225000, 3, "QQ音乐"))
        begin(value)
        val bitmap = image(Color.BLUE)
        module.onMediaArtwork(context, owner, value.item, bitmap); idle(1200)
        for (line in listOf("壮志豪情四海远名扬", "愁情烦事别放心头", "今宵离别后")) {
            val next = value.copy(item = value.item.copy(title = line), positionMillis = 4000, positionUpdatedAtMillis = SystemClock.elapsedRealtime())
            module.onMediaSnapshot(context, owner, next)
            module.onMediaArtwork(context, owner, next.item, bitmap)
            idle(1200)
        }
        bitmap.recycle()
        assertEquals(1, metadata.map { it.getInt("sourceID") }.distinct().size)
        assertEquals(1, covers.size)
        assertEquals("今宵离别后", metadata.last().getString("songName"))
        module.onMediaSnapshot(context, owner, value.copy(item = value.item.copy(artist = "下一首真实曲名")))
        idle(1200)
        assertEquals(1, metadata.map { it.getInt("sourceID") }.distinct().size)
        assertEquals("下一首真实曲名", metadata.last().getString("singerName"))
    }
    @Test fun regainingTheExistingOutputResendsItsCoverOnceWithoutAPeriodicImageLoop() {
        val value = snapshot(); begin(value)
        val bitmap = image(Color.BLUE); module.onMediaArtwork(context, owner, value.item, bitmap); bitmap.recycle()
        idle(1200); assertEquals(1, covers.size)
        module.onMediaSnapshot(context, owner, value.copy(outputOwned = false, sourceSelected = false)); idle(1200)
        module.onMediaSnapshot(context, owner, value.copy(positionUpdatedAtMillis = SystemClock.elapsedRealtime())); idle(3200)
        assertEquals(2, covers.size)
        assertEquals(1, covers.map { it.first }.distinct().size)
    }
    @Test fun aPausedNextCoverCanPrecedeFinalTextAndStillBeAvailableOnResume() {
        val old = snapshot(); begin(old); idle(1200)
        module.onMediaSnapshot(context, owner, old.copy(playing = false)); idle(100)
        val early = old.copy(item = old.item.copy(title = "下一首", artworkId = 4), playing = false, positionMillis = 0)
        module.onMediaSnapshot(context, owner, early)
        val bitmap = image(Color.RED); module.onMediaArtwork(context, owner, early.item, bitmap); bitmap.recycle()
        val complete = early.copy(item = early.item.copy(artist = "新的歌手", album = "新的专辑"))
        module.onMediaSnapshot(context, owner, complete); idle(1200)
        assertEquals(1, covers.size)
        val nextId = metadata.last().getInt("sourceID")
        assertEquals(nextId, covers.single().first)
        val before = progress.size
        module.onMediaSnapshot(context, owner, complete.copy(playing = true, positionUpdatedAtMillis = SystemClock.elapsedRealtime()))
        idle(2200)
        assertTrue(progress.size > before)
        assertEquals(nextId, progress.last().first)
        assertEquals(1, covers.size)
    }
    @Test fun aPhonePlayingThroughAnotherSourceDoesNotCauseNativeSourceCompetition() {
        begin(snapshot(owned = false)); idle(3200)
        assertEquals(0, binds); assertTrue(transactions.isEmpty())
        module.onMediaSnapshot(context, owner, snapshot(owned = true)); idle(1200)
        assertTrue(metadata.isNotEmpty())
        module.onMediaSnapshot(context, owner, snapshot(owned = false))
        val count = metadata.size; idle(3200)
        assertEquals(count, metadata.size); assertEquals(0, unbinds)
    }
    @Test fun nativePausePublishesPausedMetadataButDoesNotInventProgressOrARepeatedHeartbeat() {
        begin(); idle(1200)
        module.onMediaSnapshot(context, owner, snapshot(playing = false, position = 3456, owned = false))
        val before = metadata.size; val progressBefore = progress.size; idle(5500)
        assertEquals(1, metadata.size - before)
        assertTrue(metadata.drop(before).all { it.getInt("playStatus") == 1 })
        assertEquals(progressBefore, progress.size)
        assertEquals(0, unbinds)
        assertEquals(1, metadata.map { it.getInt("sourceID") }.distinct().size)
    }
    @Test fun pausedSessionRemainsBoundAndResumeRestartsPeriodicUpdatesUntilDisconnect() {
        begin(snapshot(playing = false, owned = false)); idle(2200)
        assertEquals(1, metadata.size); assertTrue(progress.isEmpty()); assertEquals(0, unbinds)
        module.onMediaSnapshot(context, owner, snapshot(playing = true)); idle(2200)
        assertTrue(metadata.size >= 3); assertEquals(1, binds)
        module.onMediaSessionEnded(owner); val count = metadata.size; idle(8000)
        assertEquals(count, metadata.size)
    }
    @Test fun clearedNowPlayingDoesNotInventMetadataOrADeviceClearCommand() {
        begin(); idle(1200)
        module.onMediaSnapshot(context, owner, MediaSnapshot(MediaItem(null, null, null, null, null), false,
            null, SystemClock.elapsedRealtime(), false))
        val before = metadata.size; idle(3200)
        assertEquals(before, metadata.size)
        assertEquals("QQ歌曲A", metadata.last().getString("songName"))
        assertEquals(2, metadata.last().getInt("playStatus")); assertEquals(0, unbinds)
    }
    @Test fun coverIsSentOnceAndNeverReencodedEveryTick() {
        val value = snapshot(); begin(value)
        val bitmap = image(Color.BLUE)
        module.onMediaArtwork(context, owner, value.item, bitmap); bitmap.recycle()
        idle(4200); assertEquals(1, covers.size); assertTrue(covers.single().second.isFile)
        assertEquals(metadata.last().getInt("sourceID"), covers.single().first)
        val duplicate = image(Color.BLUE)
        module.onMediaArtwork(context, owner, value.item, duplicate); duplicate.recycle()
        idle(2200); assertEquals(1, covers.size)
        val changed = image(Color.RED)
        module.onMediaArtwork(context, owner, value.item, changed); changed.recycle()
        idle(2200); assertEquals(2, covers.size)
    }
    @Test fun wrongTrackOrEndedOwnerCannotSendAnArtwork() {
        val value = snapshot(); begin(value)
        val bitmap = image(Color.RED)
        module.onMediaArtwork(context, owner, value.item.copy(title = "旧歌"), bitmap)
        module.onMediaArtwork(context, Any(), value.item, bitmap); bitmap.recycle()
        idle(2200); assertTrue(covers.isEmpty())
    }
    @Test fun nativePauseRefreshesTheCoverOnceAndResumeDoesNotDuplicateIt() {
        val value = snapshot(); begin(value)
        val bitmap = image(Color.BLUE)
        module.onMediaArtwork(context, owner, value.item, bitmap); bitmap.recycle()
        idle(1200); assertEquals(1, covers.size)
        module.onMediaSnapshot(context, owner, snapshot(playing = false, position = 2500, owned = false))
        idle(5200); assertEquals(2, covers.size)
        assertEquals(1, metadata.last().getInt("playStatus"))
        module.onMediaSnapshot(context, owner, snapshot(playing = true, position = 2500))
        idle(2200); assertEquals(2, covers.size)
    }
    @Test fun lateOldDisconnectCannotCancelANewPhoneSession() {
        begin(); idle(1200)
        val next = Any()
        module.onMediaSessionStarted(context, next)
        module.onMediaSnapshot(context, next, snapshot("新手机歌曲"))
        module.onMediaSessionEnded(owner); idle(2200)
        assertEquals("新手机歌曲", metadata.last().getString("songName"))
        module.onMediaSessionEnded(next)
    }
    @Test fun disconnectDuringMetadataCannotDispatchQueuedProgressOrCover() {
        begin(); onMetadata = { module.onMediaSessionEnded(owner) }
        idle(2200)
        assertEquals(listOf(37), transactions)
        idle(4000); assertEquals(1, metadata.size)
    }
    @Test fun missingTitleNeverPublishesAPlaceholderCardAndBindsAreBounded() {
        begin(snapshot(title = "")); idle(3000); assertEquals(0, binds)
        rejectBinding = true
        module.onMediaSnapshot(context, owner, snapshot()); idle(12000)
        assertEquals(3, binds); assertTrue(transactions.isEmpty())
    }
    @Test fun localDisplaySessionIdSurvivesTextCorrectionsPauseAndNextTrack() {
        val value = snapshot(); begin(value); idle(1200)
        val first = metadata.last().getInt("sourceID")
        module.onMediaSnapshot(context, owner, value.copy(item = value.item.copy(durationMillis = 61000), playing = false))
        idle(1200); assertEquals(first, metadata.last().getInt("sourceID"))
        module.onMediaSnapshot(context, owner, value.copy(item = value.item.copy(artworkId = 77)))
        idle(1200); assertEquals(first, metadata.last().getInt("sourceID"))
        module.onMediaSnapshot(context, owner, snapshot("第二首")); idle(1200)
        assertEquals(first, metadata.last().getInt("sourceID"))
        assertEquals("第二首", metadata.last().getString("songName"))
    }

    @Test fun recordedQqFieldsCanSwapWithoutReallocatingTheDisplayOrResendingTheSameCover() {
        val value = snapshot().copy(item = MediaItem("没有我你怎么办", "郑杰伦", "没有我你怎么办", 225000, 3, "QQ音乐"))
        begin(value)
        val bitmap = image(Color.BLUE); module.onMediaArtwork(context, owner, value.item, bitmap); idle(1200)
        val next = value.copy(item = value.item.copy(title = "没有我你怎么办 - 郑杰伦", artist = "没有我你怎么办", album = "郑杰伦"))
        module.onMediaSnapshot(context, owner, next)
        module.onMediaArtwork(context, owner, next.item, bitmap); idle(2200)
        bitmap.recycle()
        assertEquals(1, metadata.map { it.getInt("sourceID") }.distinct().size)
        assertEquals(1, covers.size)
        assertEquals("没有我你怎么办", metadata.last().getString("singerName"))
        assertTrue(progress.isNotEmpty())
    }
    @Test fun focusInterruptionDoesNotRebuildTheNativeCoverAndPausedNativeSourceSelectionIsRespected() {
        val value = snapshot(); begin(value)
        val bitmap = image(Color.BLUE); module.onMediaArtwork(context, owner, value.item, bitmap); bitmap.recycle(); idle(1200)
        repeat(4) {
            module.onMediaSnapshot(context, owner, value.copy(outputOwned = false)); idle(200)
            module.onMediaSnapshot(context, owner, value); idle(200)
        }
        assertEquals(1, covers.size)
        val before = transactions.size
        module.onMediaSnapshot(context, owner, value.copy(playing = false, outputOwned = false, sourceSelected = false)); idle(2200)
        assertEquals(before, transactions.size)
        assertEquals(0, unbinds)
    }
}
