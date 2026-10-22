package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** AudioTrack's attributes getter is only available from Android 10. */
internal fun audioTrackAttributesForFocus(track: AudioTrack, configured: AudioAttributes): AudioAttributes =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) track.audioAttributes else configured

/** Owns one focus request for all eligible tracks in a CarPlay sink. */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    // Read per duck so the slider takes effect on the next prompt, not the next connection.
    private val duckPercent: () -> Int = { DEFAULT_DUCK_PERCENT },
    private val report: (String) -> Unit = {},
) {
    private data class Entry(val channel: AudioChannel, val attributes: AudioAttributes)

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: AudioFocusRequestCompat? = null
    private var requestedChannel: AudioChannel? = null
    // Volume applied to every active track, so a duck is never stacked on a duck: the head unit's
    // own policy re-evaluates focus every few seconds during a call and would otherwise pull the
    // media level down once per evaluation (field report: "media volume drops a notch every few
    // seconds while on a call").
    private var appliedVolume = FULL_VOLUME
    private val volumeHandler = Handler(Looper.getMainLooper())
    /** Music level while ducked; 100 % means the setting is effectively off. */
    private fun duckVolume(): Float =
        duckPercent().coerceIn(DUCK_PERCENT_MIN, DUCK_PERCENT_MAX) / 100f
    /** A duck request with an expiry: the head unit's CAN_DUCK or the phone's duckAudio. */
    private var timedDucking = false
    /** Set for as long as a navigation prompt is playing; we observe this exactly. */
    private var guidanceDucking = false
    // Our own duck is only ever meant to bridge a short prompt, so it undoes itself. The head unit
    // does not always send AUDIOFOCUS_GAIN when the interrupting audio ends, and without this the
    // music stayed at 20 % for the rest of the drive - field report: "it turned the media volume
    // down once and never brought it back". The phone's own duck/unduck commands measure 9-10 s in
    // the field, so 12 s sits just past them and only fires when the restore really never comes.
    private val unduckRunnable = Runnable {
        synchronized(this@AudioFocusCoordinator) {
            timedDucking = false
            applyDuckVolume()
        }
    }
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    timedDucking = true
                    applyDuckVolume()
                    volumeHandler.removeCallbacks(unduckRunnable)
                    volumeHandler.postDelayed(unduckRunnable, DUCK_HOLD_MS)
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    volumeHandler.removeCallbacks(unduckRunnable)
                    timedDucking = false
                    applyDuckVolume()
                }
                // Keep CarPlay audio running on permanent or transient loss. Some head units
                // do not send a later gain callback after taking focus back.
            }
        }
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (channel == AudioChannel.NAVIGATION) return
        active[track] = Entry(channel, attributes)
        // Ducking is independent of holding focus: that is a separate switch, and a driver who
        // turned it off still expects a prompt to lower the music.
        if (enabled) refreshRequest()
        // A track that appears while the music is ducked starts at that same level, so it cannot
        // play loud under a prompt. Prompts and calls are never attenuated this way.
        if (channel == AudioChannel.MEDIA) {
            runCatching { track.setStereoVolume(appliedVolume, appliedVolume) }
        }
    }

    @Synchronized
    fun release(track: AudioTrack) {
        if (active.remove(track) != null && enabled) refreshRequest()
    }

    /**
     * The focus request follows the highest-priority active channel, but the MEDIA request is
     * deliberately NOT abandoned when a call appears. Abandoning it makes the head unit's audio
     * policy re-evaluate from scratch, which is what re-ducked media every few seconds during a
     * call. The call keeps its own GAIN_TRANSIENT request; media simply stays held underneath it.
     */
    private fun refreshRequest() {
        val primary = active.values.maxByOrNull { it.channel.focusPriority() }
        if (primary == null) {
            manager?.let { request?.abandon(it) }
            request = null
            requestedChannel = null
            return
        }
        val callActive = active.values.any { it.channel == AudioChannel.PHONE }
        if (request != null && (requestedChannel == primary.channel || (callActive && requestedChannel == AudioChannel.MEDIA))) {
            return
        }
        // Never abandon a MEDIA request to install a call request: the call gets its own, and the
        // media one keeps holding its place so the head unit has nothing to re-evaluate.
        val replacingMedia = requestedChannel == AudioChannel.MEDIA && primary.channel != AudioChannel.MEDIA
        if (!replacingMedia) {
            manager?.let { request?.abandon(it) }
        }
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.NAVIGATION -> return
        }
        val next = AudioFocusRequestCompat(gain, primary.attributes, listener, Handler(Looper.getMainLooper()))
        request = next
        requestedChannel = primary.channel
        val result = manager?.let(next::request)
        val line = "Audio: focus requested channel=${primary.channel} gain=$gain granted=$result activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun setVolume(volume: Float) {
        // Only ever move toward the target once: repeated duck callbacks must not multiply.
        if (volume == appliedVolume) return
        appliedVolume = volume
        // Music only. Turning down the prompt that caused the duck would defeat its purpose, and a
        // call's audio must never be attenuated.
        active.entries.filter { it.value.channel == AudioChannel.MEDIA }
            .forEach { (track, _) -> runCatching { track.setStereoVolume(volume, volume) } }
    }

    /**
     * Forces the music tracks back to full level, whatever the duck bookkeeping believes.
     *
     * Used when a call ends. [setVolume] deliberately does nothing when the target equals
     * [appliedVolume], which assumes the head unit never touched our tracks - and that assumption
     * is exactly what fails here: the car lowers the media path for a call, our own flag still says
     * "full", and the driver is left with quiet music after hanging up (field report 2026-10-20).
     * A reset writes the level to every media track unconditionally.
     */
    fun resetPlaybackVolume(reason: String) {
        synchronized(this) {
            volumeHandler.removeCallbacks(unduckRunnable)
            timedDucking = false
            guidanceDucking = false
            appliedVolume = FULL_VOLUME
            runCatching { report("Audio: music level forced to full ($reason)") }
            active.entries.filter { it.value.channel == AudioChannel.MEDIA }
                .forEach { (track, _) -> runCatching { track.setStereoVolume(FULL_VOLUME, FULL_VOLUME) } }
        }
    }

    /**
     * The phone's own duckAudio command: lower the music for [durationMs] and then bring it back.
     *
     * CarPlay sends this when one of iOS's own sounds (a camera shutter, a notification, Siri)
     * should be heard over ours, and the accessory is expected to honour it. Ignoring it left the
     * head unit's focus callback as the only thing that ever lowered our music - and when that
     * callback's matching restore never arrived, the music stayed quiet for the rest of the drive
     * (field report: taking a photo ducked the volume and it did not come back).
     */
    fun duckFor(durationMs: Long) {
        synchronized(this) {
            timedDucking = true
            applyDuckVolume()
            volumeHandler.removeCallbacks(unduckRunnable)
            volumeHandler.postDelayed(unduckRunnable, durationMs.coerceIn(200L, DUCK_HOLD_MS))
        }
    }

    /** The phone's unduckAudio command, or the end of the window we were given. */
    fun unduck() {
        synchronized(this) {
            volumeHandler.removeCallbacks(unduckRunnable)
            timedDucking = false
            applyDuckVolume()
        }
    }

    /**
     * A navigation prompt is playing. Guidance is the one duck we observe exactly, so the music
     * comes back the moment the prompt ends even if nobody sent us a duck request. Only MEDIA
     * tracks move: call audio is never attenuated.
     */
    fun setGuidanceDucking(active: Boolean) {
        synchronized(this) {
            if (guidanceDucking == active) return
            guidanceDucking = active
            applyDuckVolume()
        }
    }

    private fun applyDuckVolume() {
        val target = if (guidanceDucking || timedDucking) duckVolume() else FULL_VOLUME
        runCatching { report("Audio: duck guidance=$guidanceDucking timed=$timedDucking target=$target") }
        setVolume(target)
    }

    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.MEDIA -> 3
        AudioChannel.PHONE -> 2
        AudioChannel.ASSISTANT -> 1
        AudioChannel.NAVIGATION -> 0
    }

    companion object {
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCK_PERCENT_MIN = 10
        const val DUCK_PERCENT_MAX = 100
        const val DEFAULT_DUCK_PERCENT = 60
        /** How long our own duck survives without a restore callback from the head unit. */
        const val DUCK_HOLD_MS = 12_000L
    }
}

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Each audio stream keeps its own track and usage so
 * media and navigation guidance stay independently routable. Call [close]
 * when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val audioFocusEnabled: Boolean = false,
    private val mediaChannel: Int = 0,
    private val navigationChannel: Int = 0,
    context: Context? = null,
    private val navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
    /** True while any navigation-guidance stream is playing; called from media threads. */
    private val onNavigationGuidanceChanged: (Boolean) -> Unit = {},
    /** Music level (percent) while a prompt plays or the phone asks us to duck. */
    private val navDuckPercent: () -> Int = { AudioFocusCoordinator.DEFAULT_DUCK_PERCENT },
) : MediaSink {
    private val appContext = context?.applicationContext
    private val audioManager = appContext?.getSystemService(AudioManager::class.java)
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled,
        navDuckPercent,
        onAudioDiagnostic,
    )
    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val navigationIds = mutableSetOf<AudioStreamId>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()
    private val audioModeLock = Any()
    private var communicationModeStream: AudioStreamId? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    /**
     * The head unit's music level just before a call started.
     *
     * This car's audio policy lowers the music stream while a call runs (that is what makes the
     * prompt/duck handling audible at all), and does not always put it back when the call ends:
     * the driver hears quiet music afterwards, disconnecting does not help, and only a reconnect
     * restores it (field report 2026-10-20). The level is not ours to guess, so it is snapshotted
     * here and re-applied once the call is really over.
     */
    private var savedMusicVolume: Int? = null

    /** Whether the head unit had the music stream muted before the call (it can mute without changing the level). */
    private var savedMusicMuted = false

    /** When that snapshot was taken; an old one must never overwrite a level the driver set since. */
    private var savedMusicVolumeAtMs = 0L
    // Re-asserts the audio mode after a call; separate from AudioFocusCoordinator's handler,
    // which only drives the media duck.
    private val audioModeHandler = Handler(Looper.getMainLooper())

    /** How long to wait before re-asserting the audio mode after a call (see restoreAudioMode). */
    private val AUDIO_MODE_REASSERT_MS = 750L

    /** A later pass that restores the music level even when the head unit kept its call mode. */
    private val AUDIO_MODE_LATE_CHECK_MS = 5_000L

    /** How long a pre-call music snapshot stays usable. */
    private val MUSIC_SNAPSHOT_TTL_MS = 120_000L

    /** How often the head unit's audio mode is sampled to catch calls that never reach our streams. */
    private val AUDIO_MODE_POLL_MS = 1_500L

    /**
     * Last audio mode seen by the poll.
     *
     * Calls the head unit handles itself (its own phone app over Bluetooth HFP) never open a
     * telephony stream of ours, so the snapshot/restore that hangs off `onMicrophoneStarted` never
     * ran for them - and those are the calls where the driver reports the music level dropping and
     * staying down. The mode is the only channel that covers both kinds.
     */
    private var polledAudioMode = AudioManager.MODE_NORMAL
    private val audioModePoll = object : Runnable {
        override fun run() {
            runCatching { pollAudioMode() }
            if (!closedForPoll) audioModeHandler.postDelayed(this, AUDIO_MODE_POLL_MS)
        }
    }
    @Volatile private var closedForPoll = false

    /**
     * Last moment real guidance audio was handed to the navigation track.
     *
     * The guidance stream's start/stop edges are not proof that a prompt is audible: on this head
     * unit the stream stays open far longer, and the volume keys were then routed to guidance for
     * as long as it lived (driver could not change the music; the guidance level itself drifted
     * down as he pressed the keys, 2026-10-20). Actual PCM is the honest signal.
     */
    @Volatile private var lastNavigationPcmAtMs = 0L
    /** Last time a microphone stream was open; also set when it closes to keep a short window. */
    @Volatile private var lastMicrophoneAtMs = 0L

    fun onNavigationAudioFrame() {
        lastNavigationPcmAtMs = SystemClock.elapsedRealtime()
    }

    /** True while guidance audio is really flowing, not merely while its stream is open. */
    fun navigationAudioActive(windowMs: Long = 2_500L): Boolean {
        val last = lastNavigationPcmAtMs
        return last != 0L && SystemClock.elapsedRealtime() - last <= windowMs
    }

    /**
     * True while a CarPlay microphone stream is open or just closed.
     *
     * Siri listens through this stream, and while the assistant UI is up the navigation video can
     * legitimately sit on one static picture. The video-freeze watchdog uses this to avoid
     * treating a Siri screen as a frozen CarPlay session.
     */
    fun microphoneActive(windowMs: Long = 3_000L): Boolean {
        val last = lastMicrophoneAtMs
        return last != 0L && SystemClock.elapsedRealtime() - last <= windowMs
    }

    init {
        // Sampled from the very first frame of the session, so a call that started before the
        // session (or that the head unit handles itself) still has its "before" level recorded.
        if (audioManager != null) {
            audioModeHandler.post(audioModePoll)
            // One line at start so a report proves the poll is alive even when no call happens.
            onAudioDiagnostic(
                "Audio: mode poll started, mode=${runCatching { audioManager?.mode }.getOrNull()} " +
                    "music=${runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()}",
            )
        }
    }
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    // Extra decoders draw the same stream on other surfaces, such as the centre card.
    private val mirrorLock = Any()
    private val mirrorSurfaces = HashMap<Pair<Int, String>, Surface>()
    private val mirrorDecoders = HashMap<Pair<Int, String>, VideoDecoder>()
    private val lastVideoConfig = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    fun setSurface(type: Int, surface: Surface) {
        surfaces[type] = surface
        videoDecoders[type]?.setSurface(surface)
    }

    fun clearSurface(type: Int, surface: Surface) {
        if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
    }

    /**
     * Also decodes stream [type] onto [surface] with its own decoder, one per [key], which
     * starts at the next keyframe it asks for; null stops it. The stream's own surface is not affected.
     */
    fun setMirrorSurface(type: Int, key: String, surface: Surface?) {
        val id = type to key
        synchronized(mirrorLock) {
            mirrorDecoders.remove(id)?.close()
            if (surface == null) {
                mirrorSurfaces.remove(id)
                return
            }
            mirrorSurfaces[id] = surface
        }
        lastVideoConfig[type]?.let { (codec, data) -> mirrorDecoders(type).forEach { it.configure(codec, data) } }
    }

    private fun mirrorDecoders(type: Int): List<VideoDecoder> = synchronized(mirrorLock) {
        if (mirrorSurfaces.isEmpty()) return emptyList()
        mirrorSurfaces.filterKeys { it.first == type }.map { (id, surface) ->
            mirrorDecoders.getOrPut(id) { newVideoDecoder(type, surface, " stream=$type mirror=${id.second}") }
        }
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        lastVideoConfig[type] = codec to codecData
        videoDecoder(type).configure(codec, codecData)
        mirrorDecoders(type).forEach { it.configure(codec, codecData) }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        videoDecoder(type).submit(naluBytes)
        mirrorDecoders(type).forEach { it.submit(naluBytes) }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) {
            videoRecoveryHandlers.remove(type)
            videoDiagnosticHandlers.remove(type)
            videoDecoders.remove(type)?.close()
            synchronized(mirrorLock) {
                mirrorDecoders.keys.filter { it.first == type }.forEach { mirrorDecoders.remove(it)?.close() }
            }
            lastVideoConfig.remove(type)
            pendingVideoCodec.remove(type)
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    /**
     * Newest measured bitrate of a video stream (110 main screen, 111 cluster), -1 when unknown.
     *
     * The freeze detector reads this: frames can keep arriving at full rate while the iPhone has
     * stopped repainting, and the only visible symptom is the bitrate collapsing.
     */
    fun videoKbps(streamType: Int): Long = VideoStats.latestKbps(streamType)

    /** Newest measured frame rate of a video stream, -1 when unknown. */
    fun videoFps(streamType: Int): Double = VideoStats.latestFps(streamType)

    /** How long ago the newest window for [streamType] closed, in milliseconds. */
    fun videoWindowAgeMs(streamType: Int): Long {
        val at = VideoStats.latestWindowAtMs(streamType)
        if (at == 0L) return Long.MAX_VALUE
        return (System.nanoTime() / 1_000_000 - at).coerceAtLeast(0L)
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        // NOTE (2026-10-18, do NOT reintroduce): the iPhone's media stream used to be dropped
        // outright when "music via car Bluetooth" was on, on the theory that iOS would then play
        // the music over the head unit's own A2DP. It never does: the phone keeps media inside
        // CarPlay while the accessory advertises audio, so the cabin went silent with the head
        // unit's volume already at maximum (field: streamMusic=15/15, musicActive=false, stream 102
        // opened and dropped). Refusing the stream in the SETUP is worse still - iOS re-requests it
        // in a loop and the media remote churns through skip commands. Media audio is therefore
        // always rendered; the switch now only governs who owns the media keys and focus.
        audioRenderer(id, format).start()
        if (format.audioType == "media") updateMediaAudio(id, true)
        updateNavigationAudio(id, format, true)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        if (format.audioType == "media" && !mediaAudioTypes.contains(id)) updateMediaAudio(id, true)
        audioRenderer(id, format).submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        val wasMedia = mediaAudioTypes.contains(id)
        audioRenderers.remove(id)?.close()
        updateMediaAudio(id, false)
        updateNavigationAudio(id, null, false)
        // A call or announcement stream ending is the moment the music has to be back at full
        // level. The mode poll cannot be trusted for this head unit: it never reports a call at all
        // (no "call detected" line in a whole day of logs), so a level the call lowered used to
        // survive until the next reconnect.
        if (!wasMedia) audioFocusCoordinator.resetPlaybackVolume("非媒体流结束 $id")
    }

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) onMediaAudioChanged(after)
    }

    private fun isNavigationGuidance(format: AudioFormat): Boolean {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).channel == AudioChannel.NAVIGATION
    }

    /** Tracks any playing navigation-guidance stream; edge transitions fire the callback. */
    private fun updateNavigationAudio(id: AudioStreamId, format: AudioFormat?, active: Boolean) {
        val (before, after) = synchronized(navigationIds) {
            val before = navigationIds.isNotEmpty()
            if (active) {
                if (format != null && isNavigationGuidance(format)) navigationIds.add(id)
            } else {
                navigationIds.remove(id)
            }
            before to navigationIds.isNotEmpty()
        }
        if (before != after) {
            if (after) requestNavigationFocus() else abandonNavigationFocus()
            audioFocusCoordinator.setGuidanceDucking(after)
            onNavigationGuidanceChanged(after)
        }
    }

    /**
     * Guidance prompts briefly hold transient (ducking) focus so the head unit treats the
     * navigation stream as the active volume target. On units that route steering-wheel
     * keys by audio focus this makes them adjust guidance volume during prompts.
     */
    private var navigationFocusRequest: Any? = null

    private fun requestNavigationFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (navigationFocusRequest != null) return
        // Pure navigation usage, matching how the stock car Amap routes guidance with its
        // "high-level audio API". A legacy stream type here would reclassify the focus as
        // a media-like stream, which head units then fail to treat as guidance volume.
        val attributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = android.media.AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
        )
            .setAudioAttributes(attributes)
            .build()
        val result = manager.requestAudioFocus(request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            navigationFocusRequest = request
            Log.i("DiPlay-NavFocus", "navigation focus granted (usage routing)")
        } else {
            Log.w("DiPlay-NavFocus", "navigation focus denied result=$result")
        }
    }

    private fun abandonNavigationFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        navigationFocusRequest?.let { manager.abandonAudioFocusRequest(it as android.media.AudioFocusRequest) }
        navigationFocusRequest = null
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        // This callback runs on the downlink thread; microphone failures must not stop playback.
        try {
            lastMicrophoneAtMs = SystemClock.elapsedRealtime()
            // Snapshot the media level before the car lowers it for the call. The mode poll cannot
            // do this on this head unit - it never reports a call at all - so the snapshot never
            // existed and a lowered stream level survived the hang-up. CarPlay mirrors that level
            // onto the iPhone's own volume, which is why the driver saw "手机音量被调到 50%".
            snapshotMusicVolume("microphone started")
            if (config.audioType == "telephony") enterCommunicationMode(id)
            val uplink = microphoneUplinks.computeIfAbsent(id) { MicrophoneUplink(config, onAudioDiagnostic) }
            if (!uplink.start()) {
                microphoneUplinks.remove(id, uplink)
                restoreAudioMode(id)
            }
        } catch (error: Exception) {
            Log.e("xcertplay-usb", "microphone start failed stream=$id", error)
            MicrophoneCaptureStats.reportStartFailure(config, error, onAudioDiagnostic)
            onMicrophoneStopped(id)
        }
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        try {
            microphoneUplinks.remove(id)?.close()
        } finally {
            lastMicrophoneAtMs = SystemClock.elapsedRealtime()
            restoreAudioMode(id)
            // The uplink ending means the call or voice-clip capture is over, and the music must be
            // back at full level. restoreAudioMode() cannot cover this: it returns immediately when
            // this session never entered communication mode, which is the case for a WeChat clip
            // and for a call the head unit handles itself - the exact "the music stayed quiet after
            // a call, only a reconnect brought it back" report.
            restoreMusicVolume("microphone stopped", ignoreTtl = true)
            audioFocusCoordinator.resetPlaybackVolume("microphone stopped")
        }
    }

    private fun enterCommunicationMode(id: AudioStreamId) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            if (communicationModeStream != null) return
            // Select the HAL communication path before AudioRecord is created.
            savedAudioMode = manager.mode
            savedMusicVolume = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            savedMusicMuted = runCatching { manager.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)
            savedMusicVolumeAtMs = SystemClock.elapsedRealtime()
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            communicationModeStream = id
            Log.i("xcertplay-usb", "audio mode $savedAudioMode -> ${manager.mode} for telephony stream=$id")
            onAudioDiagnostic("Audio: call started, music level snapshot=$savedMusicVolume muted=$savedMusicMuted")
        }
    }

    /**
     * Puts the music level back to what it was before the call. Only the head unit's own stream
     * volume is touched; our per-track duck rides on top of it and is restored by its own logic.
     */
    /**
     * Remembers the media stream level before a call can lower it.
     *
     * Taken when the uplink opens rather than when a mode change is polled: this head unit never
     * moves AudioManager.mode for a call, so the mode-driven snapshot in pollAudioMode() stays
     * null here and nothing could ever be restored.
     */
    private fun snapshotMusicVolume(reason: String) {
        val manager = audioManager ?: return
        val music = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            ?: return
        val muted = runCatching { manager.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrNull() ?: false
        synchronized(audioModeLock) {
            if (savedMusicVolume != null) return
            savedMusicVolume = music
            savedMusicMuted = muted
            savedMusicVolumeAtMs = SystemClock.elapsedRealtime()
            onAudioDiagnostic("Audio: music snapshot=$music ($reason)")
        }
    }

    private fun restoreMusicVolume(reason: String, ignoreTtl: Boolean = false) {
        val manager = audioManager ?: return
        val saved = savedMusicVolume ?: return
        // A mute does not change the level at all, so it has to be checked on its own: a car that
        // mutes the music for a call and forgets to unmute afterwards looks like "the volume never
        // came back" while getStreamVolume reports the old number.
        val muted = runCatching { manager.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrNull()
        if (muted == true && !savedMusicMuted) {
            val unmuted = runCatching {
                manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }.isSuccess
            onAudioDiagnostic("Audio: music stream was muted after the call, unmuted=$unmuted ($reason)")
        }
        savedMusicMuted = false
        val current = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        if (current == null) {
            savedMusicVolume = null
            return
        }
        // Only ever correct a level the head unit lowered. A higher level than the snapshot is the
        // driver's own doing (he raised it during the call), and replaying an old snapshot over it
        // is what made "I turn the volume up and it comes back down" (field report 2026-10-20).
        val stale = !ignoreTtl &&
            SystemClock.elapsedRealtime() - savedMusicVolumeAtMs > MUSIC_SNAPSHOT_TTL_MS
        if (stale || current >= saved) {
            savedMusicVolume = null
            onAudioDiagnostic(
                "Audio: music level kept at $current (snapshot $saved, stale=$stale, $reason)",
            )
            return
        }
        if (current == saved) {
            savedMusicVolume = null
            onAudioDiagnostic("Audio: music level unchanged after the call ($current, $reason)")
            return
        }
        val restored = runCatching {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
        }.isSuccess
        savedMusicVolume = null
        onAudioDiagnostic("Audio: music level $current -> $saved restored=$restored ($reason)")
    }

    /**
     * Watches the head unit's audio mode: any non-normal mode is a call, whichever app runs it.
     *
     * The snapshot is taken when the mode leaves normal and the restore runs when it returns, which
     * covers the calls that never touch our streams. Logs on every change, so a report shows what
     * the head unit did to the music level during that call.
     */
    private fun pollAudioMode() {
        val manager = audioManager ?: return
        val mode = runCatching { manager.mode }.getOrNull() ?: return
        if (mode == polledAudioMode) return
        val music = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        val muted = runCatching { manager.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrNull() == true
        val previous = polledAudioMode
        polledAudioMode = mode
        onAudioDiagnostic("Audio: mode $previous -> $mode, music=$music muted=$muted")
        val callStarting = previous == AudioManager.MODE_NORMAL && mode != AudioManager.MODE_NORMAL
        val callEnding = previous != AudioManager.MODE_NORMAL && mode == AudioManager.MODE_NORMAL
        if (callStarting) {
            synchronized(audioModeLock) {
                if (savedMusicVolume == null) {
                    savedMusicVolume = music
                    savedMusicMuted = muted
                    savedMusicVolumeAtMs = SystemClock.elapsedRealtime()
                    onAudioDiagnostic("Audio: call detected (mode=$mode), music snapshot=$music")
                }
            }
        } else if (callEnding) {
            synchronized(audioModeLock) {
                if (communicationModeStream == null) {
                    audioFocusCoordinator.resetPlaybackVolume("call ended (mode poll)")
                    restoreMusicVolume("mode returned to normal")
                }
            }
        }
    }

    private fun restoreAudioMode(id: AudioStreamId?) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            val active = communicationModeStream ?: return
            if (id != null && id != active) return
            communicationModeStream = null
            val target = savedAudioMode
            try {
                manager.mode = target
                Log.i("xcertplay-usb", "audio mode restored to ${manager.mode}")
            } catch (error: RuntimeException) {
                Log.w("xcertplay-usb", "could not restore audio mode $target", error)
                // Some head-unit ROMs refuse the saved mode outright. MODE_NORMAL is always
                // accepted and is what every other path expects; a call that is somehow
                // still up keeps its own mode through the re-assert below.
                runCatching { manager.mode = AudioManager.MODE_NORMAL }
                    .onFailure { Log.w("xcertplay-usb", "could not force MODE_NORMAL either", it) }
            }
            // A head unit can flip the mode back asynchronously: its own call-state callback
            // may land after ours, leaving MODE_IN_COMMUNICATION set with the car's audio
            // routed to the Bluetooth channel after the call ended (field report 2026-10-18).
            // Re-assert once shortly after, but never over a mode that means a call is still
            // active - that would cut the driver's call. The music level is checked in the same
            // pass, because the head unit lowers it on its own schedule as well.
            audioModeHandler.postDelayed({
                synchronized(audioModeLock) {
                    if (communicationModeStream != null) return@synchronized
                    val current = manager.mode
                    if (current == AudioManager.MODE_NORMAL) {
                        audioFocusCoordinator.resetPlaybackVolume("call ended")
                        restoreMusicVolume("after-call")
                        return@synchronized
                    }
                    if (current == AudioManager.MODE_IN_CALL ||
                        current == AudioManager.MODE_IN_COMMUNICATION ||
                        current == AudioManager.MODE_RINGTONE
                    ) {
                        // A call is (still) up: the saved mode predates it, so hands off.
                        return@synchronized
                    }
                    Log.w("xcertplay-usb", "audio mode drifted to $current after a call; re-asserting")
                    runCatching { manager.mode = AudioManager.MODE_NORMAL }
                    audioFocusCoordinator.resetPlaybackVolume("call ended, mode re-asserted")
                    restoreMusicVolume("after-call")
                }
            }, AUDIO_MODE_REASSERT_MS)
            // Second, later pass. Some head units keep MODE_IN_COMMUNICATION for a while after the
            // stream is gone (the driver's "the volume never came back" case) and the first pass
            // deliberately refuses to touch anything while a call may still be up. By this point
            // no stream of ours is running, so the music level is safe to put back whatever the
            // mode says.
            audioModeHandler.postDelayed({
                synchronized(audioModeLock) {
                    if (communicationModeStream != null) return@synchronized
                    audioFocusCoordinator.resetPlaybackVolume("call ended, late pass")
                    restoreMusicVolume("after-call-late")
                }
            }, AUDIO_MODE_LATE_CHECK_MS)
        }
    }

    /**
     * The phone's CarPlay audio-interruption commands, forwarded from the session.
     *
     * iOS sends duckAudio/unduckAudio when one of its own sounds (camera shutter, notification,
     * Siri) should be heard over the accessory's playback, and the accessory is expected to lower
     * its audio for the requested window. Both were ignored, which left the head unit's focus
     * callback as the only thing that ever lowered the music - and when its matching restore never
     * came, the music stayed quiet (field report: a photo ducked the volume and it never returned).
     */
    fun duckMediaFor(durationMs: Long) {
        // Music only. iOS ducks when one of ITS sounds wants to be heard, so the guidance stream
        // must not follow: ducking it as well left the prompts very quiet and the volume keys
        // useless (field 2026-10-21: "the guidance got very low, silent and unadjustable").
        mediaRenderers().forEach { runCatching { it.duckAudioFor(durationMs) } }
    }

    fun unduckMedia() {
        mediaRenderers().forEach { runCatching { it.unduckAudio() } }
    }

    /** Renderers whose channel is music; an unclassified one is left alone on purpose. */
    private fun mediaRenderers(): List<AudioRenderer> =
        audioRenderers.values.filter { it.channel() == AudioChannel.MEDIA }

    fun close() {
        closedForPoll = true
        audioModeHandler.removeCallbacks(audioModePoll)
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        synchronized(mirrorLock) {
            mirrorDecoders.values.forEach(VideoDecoder::close)
            mirrorDecoders.clear()
            mirrorSurfaces.clear()
        }
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) onMediaAudioChanged(false)
        val hadNavigation = synchronized(navigationIds) { navigationIds.isNotEmpty().also { navigationIds.clear() } }
        if (hadNavigation) {
            abandonNavigationFocus()
            onNavigationGuidanceChanged(false)
        }
        try {
            microphoneUplinks.values.forEach(MicrophoneUplink::close)
        } finally {
            microphoneUplinks.clear()
            restoreAudioMode(null)
        }
        // Leaving the session must not leave the car quiet: the head unit may have lowered the
        // music for a call and would keep that level after we are gone.
        audioFocusCoordinator.resetPlaybackVolume("sink closed")
    }

    private fun videoDecoder(type: Int): VideoDecoder =
        videoDecoders.computeIfAbsent(type) { newVideoDecoder(type, surfaces[type] ?: defaultSurface) }

    private fun newVideoDecoder(type: Int, surface: Surface?, statsLabel: String? = null) = VideoDecoder(
        type,
        surface,
        videoWidth,
        videoHeight,
        preferSoftwareHevcDecoder,
        requestKeyFrame = { requestVideoRecovery(type) },
        report = { videoDiagnosticHandlers[type]?.invoke(it) },
        statsLabel = statsLabel,
    )

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[id]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            audioFocusEnabled,
            mediaChannel,
            navigationChannel,
            audioFocusCoordinator,
            navigationStreamType,
            mediaBufferMillis,
            onAudioDiagnostic,
            onNavigationFrame = { onNavigationAudioFrame() },
        ).also { audioRenderers[id] = it }
    }
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    statsLabel: String? = null,
) : Closeable {
    private val queue = VideoDecodeQueue()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    /**
     * Set while the codec is behind on its own queue, which is not the same as having lost frames.
     *
     * The reference chain is intact in that case, so the picture is brought current by feeding
     * every frame and skipping the *display* of the stale ones. The alternative that was used
     * before - discarding the queue and rebuilding the codec - froze the picture for a codec
     * teardown plus a keyframe wait (field-reported as 1-2 s, intermittently) to remove a 250 ms
     * backlog, which left the picture far staler than the backlog it was meant to fix.
     *
     * Only the decoder thread reads or writes this, so no synchronization is needed.
     */
    private var catchingUp = false
    private var skippedWhileCatchingUp = 0
    private var duplicateConfigLogged = false
    private var failureReports = 0
    private val referenceChain = VideoReferenceChain()
    private var lastKeyFrameRequestNs = 0L
    private val stats = VideoStats(
        statsLabel ?: if (streamType == 110) "" else " stream=$streamType",
        streamType = streamType,
    )
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray) {
        stats.onReceived(nalus.size)
        queue.offer(VideoJob.Frame(nalus))
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            val ageNs = System.nanoTime() - job.receivedNs
                            // Behind, not broken: every frame is still here and the reference chain
                            // is intact, so feed all of them and let drainOutput skip the stale
                            // displays. See beginCatchUp for why the old discard-and-rebuild path
                            // was replaced. Genuine frame loss still arrives as VideoJob.Resync
                            // below and keeps its rebuild.
                            if (ageNs > MAX_FRAME_AGE_NS) beginCatchUp(ageNs / 1_000_000)
                            feed(job.nalus)
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    decoder?.let(::drainOutput)
                    // The backlog is drained once every queued frame has been fed; only then is the
                    // display current again. This cannot live inside drainOutput: feed() drains
                    // through VideoInputPump while acquiring an input buffer, so an end-of-pass
                    // check there would end the catch-up before the backlog had been fed.
                    if (catchingUp && queue.isEmpty()) finishCatchUp()
                    stats.logIfDue()?.let(report)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    releaseDecoder()
                    referenceChain.reset()
                    requestKeyFrameIfDue()
                } catch (error: LinkageError) {
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    throw error
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            releaseDecoder()
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        // Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
        // parameters with BAD_VALUE. Fall back to a minimal format, then to software.
        val attempts = listOf(
            DecoderAttempt(codecName = null, tuned = true),
            DecoderAttempt(codecName = null, tuned = false),
        ) + softwareDecoderName(mime)?.let { listOf(DecoderAttempt(it, tuned = false)) }.orEmpty()
        var next: MediaCodec? = null
        for (attempt in attempts) {
            next = tryConfigure(mime, csd, surface, attempt)
            if (next != null) break
        }
        if (next == null) {
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        decoder = next
        renderedFrameLogged = false
        submittedFrameLogged = false
        if (next != null) {
            report("decoder=${next.name} mime=$mime size=${width}x$height")
            Log.i(
                TAG,
                "video decoder configured name=${next.name} mime=$mime size=${width}x$height",
            )
        }
    }

    private data class DecoderAttempt(val codecName: String?, val tuned: Boolean)

    private fun buildFormat(mime: String, csd: List<ByteArray>, tuned: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
    ): MediaCodec? {
        var candidate: MediaCodec? = null
        return try {
            val format = buildFormat(mime, csd, attempt.tuned)
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            if (attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            codec
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            reportFailure("stage=configure tuned=${attempt.tuned} mime=$mime", error)
            Log.w(
                TAG,
                "video decoder configure failed name=${attempt.codecName ?: "default"} " +
                    "tuned=${attempt.tuned} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    private fun softwareDecoderName(mime: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
        }?.name
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(nalus: ByteArray) {
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (annexB.isEmpty()) { recover("invalid video access unit"); return }
        if (!referenceChain.accepts(annexB, config.codec)) {
            requestKeyFrameIfDue()
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size}",
            )
        }
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) { recover("video decoder input stalled"); return }
        val input = checkNotNull(codec.getInputBuffer(index)) { "Decoder input buffer unavailable" }
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            codec.queueInputBuffer(index, 0, annexB.size, System.nanoTime() / 1000, 0)
            referenceChain.onQueued()
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        drainOutput(codec)
    }

    private fun recover(reason: String) {
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        releaseDecoder()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    // While the codec is catching up its stale frames are released unseen, which is
                    // what makes the picture current again. Identical to the original when not
                    // catching up: render then equals outputSurface != null.
                    val render = outputSurface != null && !catchingUp
                    codec.releaseOutputBuffer(index, render)
                    if (render) stats.onRendered()
                    if (catchingUp) skippedWhileCatchingUp++
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        report("first frame rendered")
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    /**
     * Starts a catch-up episode: the codec is behind, so the display of frames decoded while the
     * backlog drains is skipped. The codec itself keeps running and no keyframe is requested.
     */
    private fun beginCatchUp(ageMillis: Long) {
        if (catchingUp) return
        catchingUp = true
        // Counted with the recoveries the video stats line already reports, so a field log still
        // shows how often the decoder fell behind; the report text tells this apart from a real
        // recovery (which says "waiting for keyframe").
        stats.onRecovery()
        val line = "video catch-up: frame waited ${ageMillis}ms " +
            "(limit ${MAX_FRAME_AGE_NS / 1_000_000}ms); skipping stale displays, codec kept"
        Log.i(TAG, line)
        report(line)
    }

    private fun finishCatchUp() {
        catchingUp = false
        val skipped = skippedWhileCatchingUp
        skippedWhileCatchingUp = 0
        report("video catch-up done: $skipped frame(s) not displayed, no keyframe wait")
    }

    private fun logOutputFormat(format: MediaFormat) {
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    private fun reportFailure(context: String, error: Throwable) {
        // A bad decoder can fail again on every frame; exported detail stays bounded per worker.
        if (failureReports >= 8) return
        failureReports++
        runCatching { report("decoder failed api=${Build.VERSION.SDK_INT} $context " +
            MediaFailureSummary.describe(error)) }
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_FRAME_AGE_NS = 250_000_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    private val navigationChannel: Int,
    private val audioFocusCoordinator: AudioFocusCoordinator,    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    private val onNavigationFrame: () -> Unit = {},
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private var trackAttributes: AudioAttributes? = null
    private var mappedChannel: AudioChannel? = null

    /** The channel this renderer plays, for callers that must treat the streams differently. */
    fun channel(): AudioChannel? = mappedChannel

    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    /** Music with sustained decoder backpressure must not keep playing minutes-old packets. */
    private val mediaLagGuard = if (format.audioType == "media") MediaPacketLagGuard(format.rtpClockRate) else null
    @Volatile private var newestRtpSample = 0
    private var lagRecoveryReports = 0
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var shortOpusPackets = 0
    private var decoderUnavailablePackets = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private val frameBytes = if (format.channels >= 2) 4 else 2
    private var totalWrittenFrames = 0L
    private var writtenFramesThisWindow = 0L
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var clockTrim: MediaClockTrim? = null
    private var lastTrimResets = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private var blockedWritesThisWindow = 0
    private var longestBlockedWriteMs = 0L
    private var unsentPcmBytesThisWindow = 0L
    private var unsentPcmBytesTotal = 0L
    private var diagnosticStage = "starting"
    private var lastDecoderOutputMetadata: String? = null
    private var decoderOutputReports = 0
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, ::maxOf)
            newestRtpSample = sample
            // Hold short Wi-Fi bursts intact. If compressed audio remains >1.5 s behind for
            // another 1.5 s, skip only stale undecoded packets to catch up to the live song.
            val removed = mediaLagGuard?.recover(queue, sample, AudioPacket::sample, now) ?: 0
            if (removed > 0) {
                packetsDropped.addAndGet(removed)
                if (lagRecoveryReports < MAX_REBUFFER_REPORTS) {
                    lagRecoveryReports++
                    report("Audio: stale media packet backlog recovered audioType=${format.audioType} " +
                        "packetsDiscarded=$removed remaining=${queue.size}")
                }
            }
        }
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started) packetsDropped.incrementAndGet()
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            runCatching { report("Audio: starting api=${Build.VERSION.SDK_INT} " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} " +
                "mapping=${if (advancedAudioChannelMapping) "automotive" else "mobile"} " +
                "mediaChannel=$mediaChannel navigationChannel=$navigationChannel focus=$audioFocusEnabled") }
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            diagnosticStage = "focus"
            requestAudioFocus()
            while (running) {
                diagnosticStage = "packet"
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                diagnosticStage = "decoder-output"
                codec?.let(::drainCodec)
                diagnosticStage = "buffer-maintenance"
                maintainPlaybackBuffer()
                diagnosticStage = "stats"
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                reportFailure(error)
            }
        } catch (error: LinkageError) {
            // Record an unsupported platform API without changing the existing crash semantics.
            if (running) reportFailure(error)
            throw error
        } finally {
            runCatching { logStatsIfDue(force = true) }
            release()
        }
    }

    private fun configureCodec(mime: String) {
        diagnosticStage = "decoder-format"
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodecStartup.create(
                create = { diagnosticStage = "decoder-create"; MediaCodec.createDecoderByType(mime) },
                configure = { diagnosticStage = "decoder-configure"; it.configure(mediaFormat, null, null, 0) },
                start = { diagnosticStage = "decoder-start"; it.start() },
                release = { it.release() },
            ).also {
                // A vendor name getter or diagnostic callback must not discard a started codec.
                runCatching {
                    val name = it.name
                    Log.i(TAG, "audio decoder configured mime=$mime name=$name")
                    report("Audio: decoder ready audioType=${format.audioType} codec=${format.codec} name=$name")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            reportFailure(error)
            null
        }
    }

    private fun createTrack() {
        diagnosticStage = "track-buffer-size"
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            runCatching { report("Audio: track unavailable api=${Build.VERSION.SDK_INT} stage=$diagnosticStage " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} " +
                "channels=${format.channels} minBufferResult=$minBuffer") }
            return
        }
        val selection = mappedSelection()
        mappedChannel = selection.channel
        val streamOverride = channelOverride(selection.channel)
        var attributes = audioAttributesFor(selection, streamOverride)
        trackAttributes = attributes
        val plan = MediaAudioBuffer.plan(
            selection.channel == AudioChannel.MEDIA,
            format.sampleRate, format.channels, minBuffer, mediaBufferMillis,
            prompt = selection.channel == AudioChannel.NAVIGATION ||
                selection.channel == AudioChannel.ASSISTANT,
        )
        bytesPerSecond = format.sampleRate * frameBytes
        val built: AudioTrack
        var routeLabel: String
        diagnosticStage = "track-build"
        if (streamOverride == 0) {
            attributes = audioAttributesFor(selection)
            routeLabel = "usage"
            built = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(pcmFormat(encoding, channelMask))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(plan.trackBufferBytes)
                .build()
        } else {
            val streamType = streamOverride
            routeLabel = "streamType=$streamType"
            built = LegacyAudioFallback.build(
                createLegacy = {
                    AudioTrack(streamType, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    diagnosticStage = "track-fallback-build"
                    routeLabel = "streamType=$streamType(fallback=usage)"
                    Log.w(TAG, "streamType=$streamType rejected by this ROM; falling back to usage-based track")
                    attributes = audioAttributesFor(selection)
                    AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(pcmFormat(encoding, channelMask))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(plan.trackBufferBytes)
                        .build()
                },
            )
        }
        track = built
        diagnosticStage = "track-attributes"
        trackAttributes = audioTrackAttributesForFocus(built, attributes)
        diagnosticStage = "track-capacity"
        val capacityBytes = built.bufferSizeInFrames * frameBytes
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        // Only music plays long enough for the clocks to drift apart; calls keep their echo reference exact.
        clockTrim = if (mappedChannel == AudioChannel.MEDIA) {
            MediaClockTrim(
                sampleRate = format.sampleRate,
                targetFrames = (startThresholdBytes / frameBytes).toLong(),
                capacityFrames = (capacityBytes / frameBytes).toLong(),
            )
        } else {
            null
        }
        val trackMetadata = runCatching {
            "api=${Build.VERSION.SDK_INT} trackState=${built.state} trackRate=${built.sampleRate} " +
                "usage=${trackAttributes?.usage} contentType=${trackAttributes?.contentType} " +
                "attributesSource=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "track" else "configured"}"
        }.getOrDefault("api=${Build.VERSION.SDK_INT} trackMetadata=unavailable")
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond} " +
            trackMetadata)
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
        Log.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=${if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE} " +
                "channel=${selection.channel} usage=${usageFor(selection.channel)} " +
                "contentType=${contentTypeFor(selection.contentType)} " +
                "streamOverride=$streamOverride " +
                "focus=${if (audioFocusEnabled) "on" else "off"}",
        )
    }

    /** 0 uses usage-based routing; 1–20 attempt legacy stream types supported by the head unit. */
    private fun channelOverride(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> mediaChannel
        AudioChannel.NAVIGATION -> navigationChannel
        else -> 0
    }

    private fun audioAttributesFor(
        selection: AudioChannelSelection,
        streamOverride: Int,
    ): AudioAttributes {
        if (streamOverride in AudioManager.STREAM_SYSTEM..AudioManager.STREAM_ACCESSIBILITY) {
            // Android accepts only its defined legacy stream IDs here. BYD audio policy can
            // map these standard streams to vehicle outputs; arbitrary channel numbers are
            // not valid AudioAttributes legacy stream types.
            try {
                return AudioAttributes.Builder().setLegacyStreamType(streamOverride).build()
            } catch (error: Exception) {
                Log.w(TAG, "legacy audio stream $streamOverride rejected; keeping usage routing", error)
            }
        }
        return AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()
    }

    private fun mappedSelection(): AudioChannelSelection {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
    }

    private fun audioAttributesFor(selection: AudioChannelSelection): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()

    /**
     * Shares a sink-level focus request across all active non-navigation renderers.
     * Navigation guidance intentionally takes no focus: it overlays media without ducking it.
     */
    private fun requestAudioFocus() {
        val channel = mappedChannel ?: return
        val attributes = trackAttributes ?: return
        if (channel == AudioChannel.NAVIGATION) {
            Log.i(TAG, "audio focus skipped channel=NAVIGATION; overlays without ducking")
            return
        }
        track?.let { audioFocusCoordinator.acquire(it, channel, attributes) }
    }

    private fun abandonAudioFocus() {
        track?.let(audioFocusCoordinator::release)
    }

    /** Forwarded from AndroidMediaSink when the phone sends its duckAudio/unduckAudio commands. */
    fun duckAudioFor(durationMs: Long) = audioFocusCoordinator.duckFor(durationMs)
    fun unduckAudio() = audioFocusCoordinator.unduck()

    private fun pcmFormat(encoding: Int, channelMask: Int) = AndroidAudioFormat.Builder()
        .setSampleRate(format.sampleRate)
        .setChannelMask(channelMask)
        .setEncoding(encoding)
        .build()

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).streamType
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    shortOpusPackets++
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: run { decoderUnavailablePackets++; return }
        diagnosticStage = "decoder-input"
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    /**
     * Moves every decoded PCM buffer the codec has ready into the track.
     *
     * Called from the loop and inline from [feedCodec], so it must always make progress and never
     * hold an output buffer: a hold here starves the decoder's input side and freezes audio (a
     * back-pressure experiment did exactly that: `outputBuffers` stayed at 0 while `inputDropped`
     * climbed).
     */
    private fun drainCodec(codec: MediaCodec) {
        diagnosticStage = "decoder-output"
        val info = MediaCodec.BufferInfo()
        while (running) {
            diagnosticStage = "decoder-output"
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Vendor metadata inspection must not interrupt otherwise working playback.
                    if (decoderOutputReports < 4) runCatching {
                        val outputFormat = codec.outputFormat
                        val metadata = "rate=${outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)} " +
                            "channels=${outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)} " +
                            "pcmEncoding=${outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)}"
                        if (metadata != lastDecoderOutputMetadata) {
                            lastDecoderOutputMetadata = metadata
                            decoderOutputReports++
                            report("Audio: decoded format audioType=${format.audioType} codec=${format.codec} $metadata")
                        }
                    }
                }
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    /**
     * One place for "these bytes will never reach the player": counts them for the report and takes
     * them out of the queued estimate, so the estimate reflects what the track actually holds.
     */
    private fun recordLostPcm(bytes: Long) {
        if (bytes <= 0) return
        unsentPcmBytesThisWindow += bytes
        unsentPcmBytesTotal += bytes
        bufferProgress.discarded(bytes)
    }

    /**
     * Writes one decoded frame without blocking the decode loop.
     *
     * `WRITE_BLOCKING` parked the `carplay-audio` thread for 320-360 ms whenever the track was
     * full (field measurement on a 2026 OPPO, Android 15). While it was parked, decoded packets
     * piled up behind it and the track drained to its rebuffer floor. A non-blocking write keeps
     * the loop turning, and a short bounded retry still lets a frame land as soon as the track has
     * room instead of dropping it: an A/B on the same phone showed every dropped frame costing a
     * rebuffer, so dropping eagerly is the worse trade. A frame that finds no room within
     * [BLOCKING_WRITE_DEADLINE_NS] is dropped and counted in `unsentPcmBytes`.
     */
    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        diagnosticStage = "track-write"
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        // A guidance prompt is short and carries meaning, so losing frames from it is audible as a
        // clipped word. It therefore waits much longer than music before giving up on a frame, and
        // since it has its own track and thread this never delays the music path. Without this the
        // 20 ms music window would clip navigation prompts that the old blocking write used to
        // deliver in full.
        val deadlineNs = if (mappedChannel == AudioChannel.NAVIGATION) {
            NAVIGATION_WRITE_DEADLINE_NS
        } else {
            BLOCKING_WRITE_DEADLINE_NS
        }
        val deadline = System.nanoTime() + deadlineNs
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            diagnosticStage = "track-write"
            val count = track.write(data, offset + written, writeLength, AudioTrack.WRITE_NON_BLOCKING)
            val writeMs = (System.nanoTime() - writeStarted) / 1_000_000L
            maxWriteMs = maxOf(maxWriteMs, writeMs)
            if (writeMs >= BLOCKED_WRITE_MS) {
                blockedWritesThisWindow++
                if (writeMs > longestBlockedWriteMs) longestBlockedWriteMs = writeMs
            }
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                break
            }
            if (count == 0) {
                // Full track: nothing was consumed. Retry briefly while the frame is still worth
                // playing, so the decode loop never sleeps for hundreds of ms inside one write.
                zeroWritesThisWindow++
                if (System.nanoTime() >= deadline) {
                    recordLostPcm((length - written).toLong())
                    break
                }
                // Park instead of yield: yielding here spun up to 715k times per 5 s window on a
                // full track (measured 2026-10-21), and that CPU burn starved the network receive
                // threads - it showed up as 1.2-1.4 s "Wi-Fi gaps" in the same windows. One
                // millisecond still gives ~20 attempts inside the deadline for the track to drain.
                LockSupport.parkNanos(MICRO_WAIT_NS)
                continue
            }
            if (count < writeLength) partialWritesThisWindow++
            written += count
            val framesWritten = count / frameBytes
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (mappedChannel == AudioChannel.NAVIGATION) runCatching { onNavigationFrame() }
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
            if (playbackStarted) trimClock(track)
        }
    }

    /**
     * Keeps the head unit's music clock in step with the iPhone's (#610): a head unit that plays
     * fast drains the start buffer and pauses for a rebuffer every minute or two. Only the music
     * channel is trimmed - a call's echo reference must stay exact.
     */
    private fun trimClock(track: AudioTrack) {
        val trim = clockTrim ?: return
        val now = System.nanoTime()
        val compressedRtpFrames = mediaLagGuard?.pendingFrames(queue, newestRtpSample, AudioPacket::sample)
            ?: 0L
        val compressedPcmFrames = compressedRtpFrames * format.sampleRate /
            format.rtpClockRate.coerceAtLeast(1)
        trim.sample(bufferProgress.queuedBytes(track.playbackHeadPosition) / frameBytes,
            now, compressedPcmFrames)
        val resets = trim.resetCount
        if (resets != lastTrimResets) {
            lastTrimResets = resets
            report("Audio: clock trim reset audioType=${format.audioType} count=$resets " +
                "rate=${trim.rate} levelFrames=${bufferProgress.queuedBytes(track.playbackHeadPosition) / frameBytes}")
        }
        val rate = trim.rateDue(now) ?: return
        val result = runCatching { track.setPlaybackRate(rate) }.getOrDefault(AudioTrack.ERROR)
        if (result != AudioTrack.SUCCESS) {
            // The track keeps its rate; music then plays as it did before the trim.
            clockTrim = null
            report("Audio: clock trim off audioType=${format.audioType} rate=$rate result=$result")
        }
    }

    private fun startPlayback(track: AudioTrack) {
        diagnosticStage = "track-play"
        underrunsAtPlaybackStart = track.underrunCount
        track.play()
        playbackStarted = true
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        // Lost frames are already out of the queued estimate (see recordLostPcm), so there is
        // nothing to correct here before asking whether a rebuffer is due.
        if (bufferProgress.shouldRebuffer(mappedChannel == AudioChannel.MEDIA, playbackStarted,
                track.underrunCount > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition,
                // Field data: bursts reached 450-740 ms while the old floor was half the 500 ms
                // start threshold, so one burst drained straight through it. Recover to a full
                // start threshold instead of twice as often.
                startThresholdBytes.toLong())) {
            // The hardware buffer has starved below the recovery floor. Pause without flushing
            // or discarding PCM, then use the configured start threshold again when music resumes.
            track.pause()
            playbackStarted = false
            // Pausing retains queued PCM. Count it toward the restart threshold so a
            // blocking write cannot fill the paused track before we call play().
            prebufferBytes = bufferProgress.queuedBytes(track.playbackHeadPosition)
                .coerceAtMost(startThresholdBytes.toLong()).toInt()
            lastPcmWriteNs = System.nanoTime()
            rebufferCount++
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = track?.underrunCount ?: 0
        val lastRx = lastArrivalNs.get()
        val currentTrack = track
        val playbackHeadFrames = currentTrack?.playbackHeadPosition
            ?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        val line = "audio stats audioType=${format.audioType} channel=$mappedChannel " +
            "routeType=${currentTrack?.routedDevice?.type ?: -1} codec=${format.codec} " +
            "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
            "sampleRate=${currentTrack?.sampleRate ?: format.sampleRate} " +
            "trackBufferFrames=${currentTrack?.bufferSizeInFrames ?: -1} " +
            "rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "blockedWrites=$blockedWritesThisWindow longestBlockedWriteMs=$longestBlockedWriteMs " +
            "unsentPcmBytes=$unsentPcmBytesThisWindow unsentPcmBytesTotal=$unsentPcmBytesTotal " +
            "writtenFrames=$writtenFramesThisWindow totalWrittenFrames=$totalWrittenFrames " +
            "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
            "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
            "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
            "partialWrites=$partialWritesThisWindow " +
            "playbackRate=${clockTrim?.rate ?: (currentTrack?.sampleRate ?: format.sampleRate)} " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount ended=$force"
        Log.i(STATS_TAG, line)
        report(line)
        if (format.codec != AudioCodecKind.LPCM) {
            // Keep this separate: the exported diagnostic recorder caps each line at 700 characters.
            val decoderLine = "Audio: decoder stats audioType=${format.audioType} codec=${format.codec} " +
                "inputQueuedTotal=$inputQueued inputDroppedTotal=$inputDropped " +
                "shortOpusPacketsTotal=$shortOpusPackets decoderUnavailablePacketsTotal=$decoderUnavailablePackets " +
                "outputBuffersTotal=$outputBuffers ended=$force"
            Log.i(STATS_TAG, decoderLine)
            runCatching { report(decoderLine) }
        }
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        blockedWritesThisWindow = 0
        longestBlockedWriteMs = 0L
        unsentPcmBytesThisWindow = 0L
        writtenFramesThisWindow = 0L
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
    }

    private fun reportFailure(error: Throwable) {
        runCatching { report("Audio: renderer failed api=${Build.VERSION.SDK_INT} " +
            "audioType=${format.audioType} codec=${format.codec} stage=$diagnosticStage " +
            MediaFailureSummary.describe(error)) }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        abandonAudioFocus()
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val MAX_REBUFFER_REPORTS = 5
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        /** A blocking write that stalls this long would starve the decode loop. */
        const val BLOCKED_WRITE_MS = 100L
        /**
         * How long one decoded frame may wait for room in a full track before it is dropped.
         *
         * An AAC frame is 1024 samples, 21.3 ms at 48 kHz; the old 20 ms deadline was shorter
         * than the time the track needs to make room for exactly one frame, so a full track
         * dropped the tail of every frame (field log 2026-10-22: unsentPcmBytes ~12 kB per 5 s,
         * heard as crackle). 30 ms covers one AAC frame plus the retry jitter; it is still far
         * below the 320-360 ms blocking write that used to starve the decode loop.
         */
        const val BLOCKING_WRITE_DEADLINE_NS = 30_000_000L
        /**
         * How long a guidance frame may wait. Prompts are short, so a dropped frame is heard as a
         * missing word; waiting this long costs nothing because navigation has its own track.
         */
        const val NAVIGATION_WRITE_DEADLINE_NS = 200_000_000L
        /** Wait between retries while a full track has no room; keeps the loop off the CPU. */
        const val MICRO_WAIT_NS = 1_000_000L
    }
}
