package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.shilapi.xcertplay.AirPlayPersistence
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

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
        /** getMusicRhythmSwitchState(): true while the service still considers us the source. */
        private const val TX_SWITCH_STATE = 10
        /** musicAlbumPicture(String): a file path the service decodes and runs Palette over. */
        private const val TX_ALBUM_PICTURE = 4
        /** Square size of the redrawn cover; the service only samples it for a colour. */
        private const val COVER_SIZE = 512
        private const val COVER_QUALITY = 92
        /** The car quantises whatever it is given to 24 hues, so we rebuild the picture in one. */
        private const val HUE_BUCKETS = 24
        /** 2 = playing; only this value starts the manager's spectrum loop. */
        private const val PLAYING = 2
        /** 1 = not playing; anything but 2 stops the loop. */
        private const val NOT_PLAYING = 1
        /** The sink prebuffers for ~1 s before the first PCM write; the Visualizer needs audio. */
        private const val HANDOFF_DELAY_MS = 1_500L
        /**
         * How often to check whether we are still the service's rhythm source.
         *
         * The car's own media UI takes the flag back the moment it comes up (field 2026-11-02:
         * opening the Bluetooth music screen killed the rhythm and nothing brought it back). The
         * flag is memory only and the service reports it through transaction 10, so a light poll
         * can put it back without waiting for the next track.
         */
        private const val REASSERT_INTERVAL_MS = 3_000L

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
    /** Set when the service turns out to lack the state query; polling would only churn. */
    private var reassertDisabled = false
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
            if (binder == null) return
            // A fresh binding knows nothing about us, and the handoff that asked for it may have
            // run while it was still connecting. Replay the whole state instead of waiting for the
            // next track; without this a slow bind leaves the lamps dark until something changes.
            switchedOn = false
            handedSession = 0
            if (lastSessionId > 0 && AirPlayPersistence.loadAmbientRhythmEnabled(context)) {
                handler.removeCallbacks(handoff)
                handler.postDelayed(handoff, HANDOFF_DELAY_MS)
            }
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
            // Every start re-declares the source, the same way the reference app does. The car's
            // media UI clears the flag whenever it wakes, and a paused-then-resumed track reuses
            // its session id, so waiting for a session change leaves the lamps dark.
            switchOn(force = true)
            handSession(session)
            report(PLAYING, "session $session playing")
            // After the play status: the service only decodes the picture once its status is > 0.
            pushCover()
            startReassert()
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
    private fun writeCover(artwork: Bitmap) {
        val dir = context.getExternalFilesDir("rhythm") ?: File(context.cacheDir, "rhythm")
        if (!dir.exists() && !dir.mkdirs()) {
            log("Ambient: cover dir unavailable ${dir.absolutePath}")
            return
        }
        val hue = dominantHue(artwork)
        val cover = redraw(hue)
        val file = File(dir, "cover_${System.currentTimeMillis()}.jpg")
        try {
            FileOutputStream(file).use {
                cover.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, it)
            }
            file.setReadable(true, false)
            dir.listFiles { candidate -> candidate.name.startsWith("cover_") && candidate != file }
                ?.forEach { it.delete() }
            coverPath = file.absolutePath
            log("Ambient: cover hue=${hue.toInt()} ${file.length()} bytes -> ${file.absolutePath}")
            handler.post { pushCover() }
        } catch (error: Exception) {
            log("Ambient: cover write failed ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            cover.recycle()
        }
    }

    /**
     * The hue the car would settle on, found the way its own picker does: drop grey, black and
     * blown-out pixels, weight the rest by S²V, then take the heaviest 15-degree bucket. Handing it
     * the raw artwork instead often lands further than 100 away from its 64-colour palette, which
     * makes the service fall back to a fixed default colour.
     */
    private fun dominantHue(source: Bitmap): Float {
        val sample = Bitmap.createScaledBitmap(source, 64, 64, true)
        val weight = FloatArray(HUE_BUCKETS)
        val sumR = FloatArray(HUE_BUCKETS)
        val sumG = FloatArray(HUE_BUCKETS)
        val sumB = FloatArray(HUE_BUCKETS)
        val hsv = FloatArray(3)
        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var pixels = 0
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val pixel = sample.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                totalR += r
                totalG += g
                totalB += b
                pixels++
                Color.RGBToHSV(r, g, b, hsv)
                if (hsv[1] < 0.25f || hsv[2] < 0.12f || hsv[2] > 0.95f) continue
                val gain = hsv[1] * hsv[1] * hsv[2]
                val bucket = (hsv[0] / 15f).toInt().coerceIn(0, HUE_BUCKETS - 1)
                weight[bucket] += gain
                sumR[bucket] += r * gain
                sumG[bucket] += g * gain
                sumB[bucket] += b * gain
            }
        }
        if (sample !== source) sample.recycle()
        var best = -1
        var bestWeight = 0f
        for (i in 0 until HUE_BUCKETS) {
            if (weight[i] > bestWeight) {
                bestWeight = weight[i]
                best = i
            }
        }
        val rgb = if (best >= 0 && bestWeight > 0f) {
            Color.rgb(
                (sumR[best] / bestWeight).toInt().coerceIn(0, 255),
                (sumG[best] / bestWeight).toInt().coerceIn(0, 255),
                (sumB[best] / bestWeight).toInt().coerceIn(0, 255),
            )
        } else {
            Color.rgb(
                (totalR / pixels).toInt().coerceIn(0, 255),
                (totalG / pixels).toInt().coerceIn(0, 255),
                (totalB / pixels).toInt().coerceIn(0, 255),
            )
        }
        Color.colorToHSV(rgb, hsv)
        return hsv[0]
    }

    /** Repaints that one hue as a bright diagonal gradient so the palette match is a sure thing. */
    private fun redraw(hue: Float): Bitmap {
        val cover = Bitmap.createBitmap(COVER_SIZE, COVER_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cover)
        val from = floatArrayOf(hue, 0.80f, 0.85f)
        val to = floatArrayOf(hue, 0.95f, 0.98f)
        val paint = Paint().apply {
            shader = LinearGradient(
                0f,
                0f,
                COVER_SIZE.toFloat(),
                COVER_SIZE.toFloat(),
                Color.HSVToColor(from),
                Color.HSVToColor(to),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, COVER_SIZE.toFloat(), COVER_SIZE.toFloat(), paint)
        return cover
    }

    private fun pushCover() {
        val path = coverPath ?: return
        if (service == null) return
        val sent = call(TX_ALBUM_PICTURE) { it.writeString(path) }
        log("Ambient: album picture -> $sent")
    }

    /**
     * Puts the source flag back if the car's own media UI took it. Runs only while a track is
     * loaded and the service is bound; a cleared flag means the session we handed over is being
     * stored and ignored, so both calls are repeated.
     */
    private val reassert = object : Runnable {
        override fun run() {
            if (reassertDisabled) return
            if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) return
            val session = lastSessionId
            if (session > 0 && service != null) {
                val stillOurs = call(TX_SWITCH_STATE) { }
                if (!stillOurs) {
                    switchOn(force = true)
                    call(TX_AUDIO_SESSION) { it.writeInt(session) }
                    // Read it back: on firmware without transaction 10 every read looks "cleared",
                    // and re-declaring every 3 s would rebuild the Visualizer and chop the rhythm.
                    val confirmed = call(TX_SWITCH_STATE) { }
                    if (!confirmed) {
                        reassertDisabled = true
                        log("Ambient: switch state unreadable, stopped polling")
                        return
                    }
                    log("Ambient: source flag was cleared, re-asserted session $session")
                }
            }
            handler.postDelayed(this, REASSERT_INTERVAL_MS)
        }
    }

    private fun startReassert() {
        if (reassertDisabled) return
        handler.removeCallbacks(reassert)
        handler.postDelayed(reassert, REASSERT_INTERVAL_MS)
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
                switchOn(force = true)
                handSession(session)
                report(PLAYING, "session $session playing")
                startReassert()
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
        handler.removeCallbacks(reassert)
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
        val handed = call(TX_AUDIO_SESSION) { it.writeInt(sessionId) }
        handedSession = sessionId
        log("Ambient: audio session $sessionId -> $handed")
    }

    /** Tells the service that the rhythm source is us. The flag is memory only, so it can be taken back. */
    private fun switchOn(force: Boolean = false) {
        if (switchedOn && !force) return
        val ok = call(TX_RHYTHM_SWITCH) { it.writeInt(1) }
        switchedOn = ok
        log("Ambient: music rhythm source -> $ok${if (force) " (re-assert)" else ""}")
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
