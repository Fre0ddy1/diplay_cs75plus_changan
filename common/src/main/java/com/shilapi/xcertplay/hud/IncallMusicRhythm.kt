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
        /** Read-only: getMusicRhythmSwitchState(). Proves we reached the real manager object. */
        private const val TX_GET_SWITCH = 10
        /**
         * MusicRhythmManager.onPlayingMusic(): 2 is the only value that starts the 200 ms
         * spectrum loop. Anything else stops it, and the service's own default is 1.
         */
        private const val PLAYING = 2
        private const val PAUSED = 0
        /** The sink's prebuffer (startMs ~1000) must be over before the Visualizer can capture. */
        private const val HANDOFF_DELAY_MS = 1_500L
        /** Twice a second is enough to absorb a reboot, a failed init or a menu toggle. */
        private const val RECONCILE_MS = 2_000L
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
    /** True only when this client turned the car's rhythm switch on, so stopping can put it back. */
    private var ownsSwitch = false
    /** True while a pause has parked the car's rhythm; the next track takes it back. */
    private var switchSuspended = false
    private var ticks = 0
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
            // Read the manager's own switch state: it is the one call whose answer cannot be a
            // hollow "true" from a stub, so the log proves which object we are talking to.
            if (binder != null) {
                log("Ambient: manager switch state=${readSwitchState()}")
            }
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
        handler.removeCallbacks(handoff)
        handler.post {
            if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) {
                stop("switch off")
                return@post
            }
            if (sessionId <= 0) {
                pendingSession = STOPPED
                if (service != null) applySession(STOPPED)
                return@post
            }
            // Bind now, hand over later: the sink prebuffers for startMs (~1 s) before the first
            // PCM write, and a Visualizer attached to a session that is not playing yet captures
            // nothing - which is a lamp that goes dark instead of dancing.
            if (service == null) bind()
            handler.postDelayed(handoff, HANDOFF_DELAY_MS)
        }
    }

    /**
     * Hands over whatever session is live once the track has actually started playing.
     *
     * Before touching the car it measures OUR OWN session: a Visualizer is created on it for a
     * fraction of a second and the loudest waveform sample is taken. If that is zero, this
     * platform does not expose our playback to a session Visualizer, the car would only be told
     * "here is a session that never produces a spectrum" - which is exactly the dashboard that
     * went dark and stayed dark until the menu was toggled (field 2026-11-01 11:36).
     */
    private val handoff = Runnable {
        val session = lastSessionId
        if (session <= 0) return@Runnable
        if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) {
            stop("switch off")
            return@Runnable
        }
        Thread {
            val peak = measureSpectrumPeak(session)
            handler.post {
                if (session != lastSessionId) return@post
                if (peak <= 0) {
                    log("Ambient: our session yields no spectrum (peak=0), car lights untouched")
                    return@post
                }
                log("Ambient: our session spectrum peak=$peak, handing it over")
                if (service != null) applySession(session) else bind()
            }
        }.start()
    }

    /**
     * Loudest waveform sample of [sessionId] over ~700 ms, 0 when nothing is captured.
     *
     * Runs on a worker thread: Visualizer construction and enablement are synchronous and the
     * capture only fills in after a few callbacks.
     */
    private fun measureSpectrumPeak(sessionId: Int): Int {
        var visualizer: android.media.audiofx.Visualizer? = null
        val peak = java.util.concurrent.atomic.AtomicInteger(0)
        return try {
            visualizer = android.media.audiofx.Visualizer(sessionId)
            val range = android.media.audiofx.Visualizer.getCaptureSizeRange()
            visualizer.setCaptureSize(range[0])
            visualizer.setDataCaptureListener(
                object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: android.media.audiofx.Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int,
                    ) {
                        waveform ?: return
                        for (b in waveform) {
                            val level = Math.abs(b.toInt() - 128)
                            if (level > peak.get()) peak.set(level)
                        }
                    }

                    override fun onFftDataCapture(
                        v: android.media.audiofx.Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) = Unit
                },
                android.media.audiofx.Visualizer.getMaxCaptureRate() / 2,
                true,
                false,
            )
            visualizer.setEnabled(true)
            Thread.sleep(700)
            peak.get()
        } catch (error: Throwable) {
            log("Ambient: spectrum probe failed ${error.javaClass.simpleName}: ${error.message}")
            0
        } finally {
            runCatching {
                visualizer?.setEnabled(false)
                visualizer?.release()
            }
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
        // Stop the spectrum loop, but leave the car's own switch exactly as we found it. Turning it
        // off here took the driver's setting away with us: his menu still showed music rhythm on
        // while the manager held "1" (off), so neither CarPlay nor the car's own player lit up
        // until he toggled the menu again (field 2026-11-01 11:12-11:15).
        if (service != null) {
            val stopped = call(TX_PLAY_STATUS) { it.writeInt(PAUSED) }
            log("Ambient: loop stopped ($reason) -> play status $PAUSED accepted=$stopped")
            if (ownsSwitch) {
                val off = call(TX_RHYTHM_ON) { it.writeInt(0) }
                log("Ambient: rhythm off (we turned it on) -> $off")
            }
        }
        handler.removeCallbacks(reconcile)
        ticks = 0
        // Handing the lamps back before we let go keeps them on the car's own colour instead of
        // leaving the manager pointed at a socket that is about to disappear (field 12:45: dark
        // until the next successful connect).
        if (service != null && lastLoggedSession != NONE) {
            val off = call(TX_RHYTHM_ON) { it.writeInt(0) }
            log("Ambient: rhythm parked on release -> $off")
        }
        ownsSwitch = false
        switchSuspended = false
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
     * Hands one session to the car.
     *
     * The manager's switch is the driver's setting and this client must not flip it: it is read
     * first, and only turned on when it was off - in which case we remember that we were the ones
     * who turned it on, so stopping can put it back. The session is handed before the switch so
     * that switchOn's own init call already sees a valid session.
     */
    private fun applySession(sessionId: Int) {
        pendingSession = NONE
        if (sessionId == STOPPED) {
            val stopped = call(TX_PLAY_STATUS) { it.writeInt(PAUSED) }
            log("Ambient: media stopped -> play status $PAUSED accepted=$stopped")
            // iOS tears stream 102 down when music pauses, so this session is dead. Leaving the
            // manager pointing at it means it keeps reading an empty spectrum and the lamps go
            // dark (field 2026-11-01 12:26). Handing the switch back returns them to the car's own
            // static colour - the closest thing to "rhythm paused" this service offers.
            if (lastLoggedSession != NONE && !switchSuspended) {
                val off = call(TX_RHYTHM_ON) { it.writeInt(0) }
                switchSuspended = off
                log("Ambient: rhythm parked (media stopped) -> $off")
            }
            return
        }
        if (switchSuspended) {
            handSession(sessionId)
            val back = call(TX_RHYTHM_ON) { it.writeInt(1) }
            switchSuspended = !back
            log("Ambient: rhythm resumed after pause -> $back")
            val playing = call(TX_PLAY_STATUS) { it.writeInt(PLAYING) }
            log("Ambient: play status $PLAYING accepted=$playing")
            return
        }
        val switchOn = readSwitchState()
        if (switchOn == false) {
            handSession(sessionId)
            val on = call(TX_RHYTHM_ON) { it.writeInt(1) }
            ownsSwitch = on
            log("Ambient: rhythm on (was off) -> $on")
        }
        handSession(sessionId)
        // 2 = playing is the ONLY value that starts the spectrum loop. The manager's
        // onPlayingMusic() stops everything for any other value, and 1 is its "not playing"
        // default - which is what this client used to send (field 2026-11-01 09:57).
        val playing = call(TX_PLAY_STATUS) { it.writeInt(PLAYING) }
        log("Ambient: play status $PLAYING accepted=$playing")
        startReconcile()
    }

    /**
     * Keeps the manager in the state this client wants, instead of trusting one shot.
     *
     * The service is a system process that outlives us: it survives disconnects, reboots and its
     * own failed Visualizer inits, and every one of those leaves the switch, the session or the
     * play status out of step (field 2026-11-01 12:45 dark until a reconnect, 12:50 fine, 12:57
     * lit but not following). Re-reading its state twice a second and correcting only what is
     * wrong costs two binder calls and removes the timing question entirely.
     */
    private fun startReconcile() {
        handler.removeCallbacks(reconcile)
        handler.postDelayed(reconcile, RECONCILE_MS)
    }

    private val reconcile = object : Runnable {
        override fun run() {
            if (!AirPlayPersistence.loadAmbientRhythmEnabled(context)) {
                stop("switch off")
                return
            }
            val session = lastSessionId
            if (session <= 0 || service == null) {
                handler.postDelayed(this, RECONCILE_MS)
                return
            }
            ticks += 1
            if (readSwitchState() == false) {
                val on = call(TX_RHYTHM_ON) { it.writeInt(1) }
                ownsSwitch = on
                log("Ambient: manager switch was off, turned on -> $on")
            }
            // The manager only re-inits its Visualizer when the id it holds differs from the one
            // it was given, so the same id is re-sent now and then to retry a failed init.
            if (ticks % 5 == 0) {
                val handed = call(TX_AUDIO_SESSION) { it.writeInt(session) }
                log("Ambient: re-handed session $session -> $handed")
            }
            val playing = call(TX_PLAY_STATUS) { it.writeInt(PLAYING) }
            if (ticks % 5 == 0) log("Ambient: play status $PLAYING accepted=$playing")
            handler.postDelayed(this, RECONCILE_MS)
        }
    }

    /** Sends the session id once per change; the manager ignores an id it already holds. */
    private fun handSession(sessionId: Int) {
        if (sessionId == lastLoggedSession) return
        val handed = call(TX_AUDIO_SESSION) { it.writeInt(sessionId) }
        log("Ambient: audio session $sessionId -> $handed")
        lastLoggedSession = sessionId
    }

    /**
     * The manager's own switch state, or null when the read itself failed. A read is the only call
     * whose answer cannot be a hollow "true" from a stub, so it doubles as the probe that proves
     * which object this client is bound to.
     */
    private fun readSwitchState(): Boolean? {
        val binder = service ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            if (!binder.transact(TX_GET_SWITCH, data, reply, 0)) {
                null
            } else {
                reply.readException()
                reply.readInt() != 0
            }
        } catch (error: Exception) {
            log(
                "Ambient: switch state read failed " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
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
