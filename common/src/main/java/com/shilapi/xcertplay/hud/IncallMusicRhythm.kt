package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.shilapi.xcertplay.AirPlayPersistence

/**
 * Drives the head unit's ambient-light music rhythm from CarPlay's music.
 *
 * What the car's own player does, and therefore what this class does - nothing else:
 *
 *   setAudioSessionId(int)   transaction 2 - the session of the music AudioTrack
 *   setMusicPlayStatus(int)  transaction 1 - 2 while playing, 1 when not
 *
 * It never calls musicRhythmSwitchOn: that switch is the driver's setting and the head unit's own
 * player leaves it alone too (its log shows only sendMusicPlayStatus(mediaSource, 3|5), which the
 * manager maps to 1 and 2).
 *
 * It also never attaches a Visualizer of its own. One session can carry a single Visualizer, so a
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
        } else {
            bind()
        }
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
