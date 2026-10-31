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
 * Drives the head unit's own ambient-light music rhythm from CarPlay's music.
 *
 * The car does not react to whatever is on the output mix. Its music apps hand the system the
 * session id of their own AudioTrack and the system attaches a Visualizer to THAT session, which
 * is why the car's QQ Music lights up the cabin and CarPlay never did: nobody handed CarPlay's
 * session over. The interface is in `com.incall.interactive`'s public client library
 * (com/incall/apps/commoninterface/vehiclesetting):
 *
 *   setMusicPlayStatus(int)      transaction 1
 *   setAudioSessionId(int)       transaction 2
 *   musicRhythmSwitchOn(boolean) transaction 3
 *   musicAlbumPicture(String)    transaction 4 (cover colour; not used yet)
 *
 * The service lives in `com.incall.apps.commonservice` and is reached with
 * `com.incall.apps.start.VehicleSettingService`. The AIDL is hand-written here rather than
 * generated: the project has no aidl source set, and the transaction layout is fixed.
 *
 * Every call logs its result, because the first question in the field is whether the service
 * rejects us (returns false / throws) or whether the rhythm starts but the lamps stay dark.
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
        private const val TX_RHYTHM_ON = 3
        /** No session queued. */
        private const val NONE = -1
        /** The session to hand over: 0 means "media stopped". */
        private const val STOPPED = 0

        /** The instance a running session uses, so the settings page can apply a switch change. */
        @Volatile private var current: IncallMusicRhythm? = null

        /** Called from settings: turn the rhythm off, or hand over the session already playing. */
        fun refreshActive() {
            current?.refreshFromSettings()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var service: IBinder? = null
    private var binding = false
    private var bound = false
    private var pendingSession = NONE
    private var active = false
    private var lastLoggedSession = NONE
    /** Last session the sink reported, kept even while the switch is off. */
    @Volatile private var lastSessionId = 0

    init {
        current = this
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder
            binding = false
            log("Ambient: vehicle setting service connected=${binder != null}")
            pendingSession.takeIf { it != NONE }?.let(::applySession)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            log("Ambient: vehicle setting service disconnected")
        }
    }

    /**
     * The media AudioTrack started (sessionId > 0) or went away (0). Called from the media
     * threads; everything else happens on the main thread.
     */
    fun onMediaTrack(sessionId: Int) {
        lastSessionId = sessionId
        handler.post {
            if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) {
                if (active) stop("switch off")
                return@post
            }
            if (sessionId <= 0) {
                pendingSession = STOPPED
                if (service != null) applySession(STOPPED)
                return@post
            }
            pendingSession = sessionId
            if (service != null) applySession(sessionId) else bind()
        }
    }

    /**
     * The settings switch changed. Off stops the rhythm at once; on hands over the session that is
     * already playing, so the driver does not have to wait for the next track.
     */
    fun refreshFromSettings() {
        handler.post {
            val enabled = AirPlayPersistence.loadAmbientRhythmEnabled(context)
            if (!enabled) {
                stop("switch off")
                return@post
            }
            val session = lastSessionId
            if (session <= 0) {
                log("Ambient: switched on, waiting for a music track")
                return@post
            }
            pendingSession = session
            if (service != null) applySession(session) else bind()
        }
    }

    /** Turns the rhythm off and lets go of the service; safe to call more than once. */
    fun release() {
        handler.post {
            stop("released")
            lastSessionId = 0
            if (current === this) current = null
        }
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

    private fun stop(reason: String) {
        if (service != null && active) {
            val off = call(TX_RHYTHM_ON) { it.writeInt(0) }
            log("Ambient: rhythm off ($reason) -> $off")
        }
        active = false
        pendingSession = NONE
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        service = null
        binding = false
        lastLoggedSession = NONE
    }

    /**
     * Hands one session to the car: rhythm on, then the session, then the play state. The order
     * matters on this service - switchOn is what creates the Visualizer holder, and a session id
     * sent before it is dropped (observed ordering in the car's own client: switch, session,
     * status).
     */
    private fun applySession(sessionId: Int) {
        pendingSession = NONE
        if (sessionId == STOPPED) {
            val stopped = call(TX_PLAY_STATUS) { it.writeInt(0) }
            log("Ambient: media stopped -> play status 0 accepted=$stopped")
            return
        }
        if (!active) {
            val on = call(TX_RHYTHM_ON) { it.writeInt(1) }
            log("Ambient: rhythm on -> $on")
            active = on
        }
        if (sessionId != lastLoggedSession) {
            val handed = call(TX_AUDIO_SESSION) { it.writeInt(sessionId) }
            log("Ambient: audio session $sessionId -> $handed")
            lastLoggedSession = sessionId
        }
        val playing = call(TX_PLAY_STATUS) { it.writeInt(1) }
        log("Ambient: play status 1 accepted=$playing")
    }

    private fun call(transaction: Int, write: (Parcel) -> Unit): Boolean {
        val binder = service ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            if (!binder.transact(transaction, data, reply, 0)) return false
            reply.readException()
            reply.readInt() != 0
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
