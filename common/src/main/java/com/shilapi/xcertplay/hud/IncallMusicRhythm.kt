package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.shilapi.xcertplay.AirPlayPersistence
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Drives the head unit's ambient-light music rhythm from CarPlay's music.
 *
 * Transactions used, and what each one buys (ids verified against the service's Stub):
 *
 *   musicRhythmSwitchOn(boolean)  transaction 3 - declares this app the rhythm source. The
 *                                 service keeps that in a runtime flag, and its spectrum loop
 *                                 refuses to build a Visualizer while the flag is not "2" (it logs
 *                                 "Visualizer不需要初始化：rhythmSwitchOn=1"). Without it a session
 *                                 id is stored and then ignored, which is why the lamps only ever
 *                                 followed the car's own player and needed its Bluetooth media
 *                                 audio switched off.
 *   setAudioSessionId(int)        transaction 2 - the session of the music AudioTrack.
 *   setMusicPlayStatus(int)       transaction 1 - 2 while playing, 1 when not.
 *   setMusicLightPlayStatus(bool) transaction 9 - the boolean form newer firmware reads.
 *   musicAlbumPicture(String)     transaction 4 - absolute path of a cover image. The service
 *                                 runs BitmapFactory.decodeFile + Palette over it to pick the
 *                                 lamp colour; without it the colour never changes.
 *
 * The flag is memory only, so it is re-sent for every binding. The car's own player never calls
 * musicRhythmSwitchOn, so taking it over here does not fight anything - but it is not undone on
 * stop either, matching what the reference app does.
 *
 * It never attaches a Visualizer of its own. One session can carry a single Visualizer, so a
 * probe here would knock the manager's listener off the session it was just given (field
 * 2026-11-01 13:30: lamps went dark on connect, lit once playback started).
 */
class IncallMusicRhythm(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    companion object {
        private const val DESCRIPTOR =
            "com.incall.apps.commoninterface.vehiclesetting.IVehicleSettingService"
        private const val SERVICE_ACTION = "com.incall.apps.start.VehicleSettingService"
        private const val SERVICE_PACKAGE = "com.incall.apps.commonservice"
        private const val TX_PLAY_STATUS = 1
        private const val TX_AUDIO_SESSION = 2
        /**
         * musicRhythmSwitchOn(boolean). The service keeps a runtime flag that only this call moves
         * to "2"; its spectrum loop refuses to build a Visualizer unless the flag is "2" (it logs
         * "Visualizer不需要初始化：rhythmSwitchOn=1"). Without it a session id is stored but never
         * analysed, which is why the lamps only followed the car's own player.
         */
        private const val TX_RHYTHM_SWITCH = 3
        /** setMusicLightPlayStatus(boolean); newer firmware wants this instead of the int status. */
        private const val TX_LIGHT_STATUS = 9
        /** musicAlbumPicture(String): a file path the service decodes and runs Palette over. */
        private const val TX_ALBUM_PICTURE = 4
        /** Longest edge the cover is scaled to; the service only samples it for a colour. */
        private const val COVER_MAX_EDGE = 512
        private const val COVER_QUALITY = 92
        /** 2 = playing; only this value starts the manager's spectrum loop. */
        private const val PLAYING = 2
        /** 1 = not playing; anything but 2 stops the loop. */
        private const val NOT_PLAYING = 1
        /** The sink prebuffers for ~1 s before the first PCM write; the Visualizer needs audio. */
        private const val HANDOFF_DELAY_MS = 1_500L

        @Volatile private var current: IncallMusicRhythm? = null

        /** Called from settings: stop at once, or take over the session already playing. */
        fun refreshActive() {
            current?.refreshFromSettings()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var service: IBinder? = null
    private var binding = false
    private var bound = false
    private var lastSessionId = 0
    private var handedSession = 0
    /** The source flag lives in the service's memory only, so it is re-sent per binding. */
    private var switchedOn = false
    /** Absolute path of the cover last written for the service, and the bitmap it came from. */
    @Volatile private var coverPath: String? = null
    private var coverIdentity = 0
    private val coverIo = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-ambient-cover").apply { isDaemon = true }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder
            binding = false
            log("Ambient: vehicle setting service connected=${binder != null}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            log("Ambient: vehicle setting service disconnected")
        }
    }

    /** The media AudioTrack started (id > 0) or went away (0). Called from the media threads. */
    fun onMediaTrack(sessionId: Int) {
        lastSessionId = sessionId
        handler.removeCallbacks(handoff)
        handler.post {
            if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) {
                stop("switch off")
                return@post
            }
            if (sessionId <= 0) {
                report(NOT_PLAYING, "media stopped")
                return@post
            }
            if (service == null) bind()
            handler.postDelayed(handoff, HANDOFF_DELAY_MS)
        }
    }

    private val handoff = Runnable {
        val session = lastSessionId
        if (session <= 0) return@Runnable
        if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) {
            stop("switch off")
            return@Runnable
        }
        if (service != null) {
            handSession(session)
            report(PLAYING, "session $session playing")
            // After the play status: the service only decodes the picture once its status is > 0.
            pushCover()
        } else {
            bind()
        }
    }

    /**
     * CarPlay's artwork for the current item changed. Called on the main thread, null included.
     *
     * A null (the gap between two items) keeps whatever colour is already set; clearing it would
     * blink the lamps back to the default every track change.
     */
    fun onArtwork(bitmap: Bitmap?) {
        if (bitmap == null) return
        handler.post {
            if (coverIo.isShutdown) return@post
            if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) return@post
            val identity = System.identityHashCode(bitmap)
            if (identity == coverIdentity) return@post
            coverIdentity = identity
            coverIo.execute { writeCover(bitmap) }
        }
    }

    /** Writes the cover where the service can decode it; its own uid reads the path directly. */
    private fun writeCover(bitmap: Bitmap) {
        val dir = context.getExternalFilesDir("rhythm") ?: File(context.cacheDir, "rhythm")
        if (!dir.exists() && !dir.mkdirs()) {
            log("Ambient: cover dir unavailable ${dir.absolutePath}")
            return
        }
        val longest = max(bitmap.width, bitmap.height)
        val scale = if (longest > COVER_MAX_EDGE) COVER_MAX_EDGE.toFloat() / longest else 1f
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).toInt()),
                max(1, (bitmap.height * scale).toInt()),
                true,
            )
        } else {
            bitmap
        }
        val file = File(dir, "cover_${System.currentTimeMillis()}.jpg")
        try {
            FileOutputStream(file).use {
                scaled.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, it)
            }
            file.setReadable(true, false)
            dir.listFiles { candidate -> candidate.name.startsWith("cover_") && candidate != file }
                ?.forEach { it.delete() }
            coverPath = file.absolutePath
            log("Ambient: cover ${file.length()} bytes -> ${file.absolutePath}")
            handler.post { pushCover() }
        } catch (error: Exception) {
            log("Ambient: cover write failed ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun pushCover() {
        val path = coverPath ?: return
        if (service == null) return
        val sent = call(TX_ALBUM_PICTURE) { it.writeString(path) }
        log("Ambient: album picture -> $sent")
    }

    /** The settings switch changed. */
    fun refreshFromSettings() {
        handler.post {
            if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) {
                stop("switch off")
                return@post
            }
            val session = lastSessionId
            if (session <= 0) {
                log("Ambient: switched on, waiting for a music track")
                return@post
            }
            if (service != null) {
                handSession(session)
                report(PLAYING, "session $session playing")
            } else {
                bind()
            }
        }
    }

    /** Reports "not playing" and lets go of the service. Leaves the car's switch untouched. */
    fun release() {
        handler.post {
            stop("released")
            lastSessionId = 0
            coverIo.shutdownNow()
            if (current === this) current = null
        }
    }

    private fun stop(reason: String) {
        if (service != null) report(NOT_PLAYING, "loop stopped ($reason)")
        handedSession = 0
        switchedOn = false
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        service = null
        binding = false
    }

    private fun bind() {
        if (bound || binding) return
        binding = true
        val intent = Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE)
        val ok = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            log("Ambient: bind failed ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
        if (!ok) {
            binding = false
            log("Ambient: bindService returned false (service missing or not exported)")
        } else {
            bound = true
            log("Ambient: bound $SERVICE_PACKAGE/$SERVICE_ACTION")
        }
    }

    private fun handSession(sessionId: Int) {
        if (sessionId == handedSession) return
        // Before the session: the switch handler re-runs the spectrum setup with whatever session
        // the service already holds, so declaring the source first lets the id below pass straight
        // through instead of being stored and ignored.
        switchOn()
        val handed = call(TX_AUDIO_SESSION) { it.writeInt(sessionId) }
        handedSession = sessionId
        log("Ambient: audio session $sessionId -> $handed")
    }

    /** Tells the service that the rhythm source is us. Kept once per binding; the flag is not persisted. */
    private fun switchOn() {
        if (switchedOn) return
        val ok = call(TX_RHYTHM_SWITCH) { it.writeInt(1) }
        switchedOn = ok
        log("Ambient: music rhythm source -> $ok")
    }

    private fun report(status: Int, what: String) {
        val ok = call(TX_PLAY_STATUS) { it.writeInt(status) }
        val playing = status == PLAYING
        val light = call(TX_LIGHT_STATUS, readResult = false) { it.writeInt(if (playing) 1 else 0) }
        log("Ambient: $what -> play status $status accepted=$ok light=$light")
    }

    private fun call(transaction: Int, readResult: Boolean = true, write: (Parcel) -> Unit): Boolean {
        val binder = service ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            if (!binder.transact(transaction, data, reply, 0)) return false
            reply.readException()
            if (!readResult) true else reply.readInt() != 0
        } catch (error: Exception) {
            log(
                "Ambient: transact $transaction failed " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
