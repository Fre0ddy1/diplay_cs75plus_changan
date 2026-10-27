package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.CarPlayAudioOwner
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executors
import java.util.concurrent.Executor

/**
 * Steering-wheel and other hardware media buttons for CarPlay.
 *
 * Android delivers media keys to a media session; BYD picks the session of the audio-focus
 * owner. Once CarPlay plays music, DiPlay holds audio focus and an active session until the
 * CarPlay session ends, so play also works after a pause. Keys go to the iPhone as CarPlay media
 * HID presses ([CarPlayMediaButton]).
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"
    private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-now-playing-artwork").apply { isDaemon = true }
        },
        main = Executor { mainHandler.post(it) },
        decode = ::decodeArtwork,
        publish = ::onArtworkDecoded,
        discard = Bitmap::recycle,
    )
    private var artworkOwner: Any? = null
    private var controller: CarPlayController? = null
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    /** Some car firmware never sends a gain callback after a call or a prompt ends. */
    private var transientFocusInterrupted = false
    private var appContext: Context? = null
    private var mediaAudioActive = false
    private var mainAudioOwner = CarPlayAudioOwner.UNKNOWN
    private var mediaIntentSerial = 0L
    private var mediaPreparationGeneration = 0L
    private var userRecoveryFocus = false
    private var audioCallbackGeneration = 0L
    private var nowPlaying = CarPlayNowPlaying()
    private var elapsedUpdatedAt = 0L
    private val wheelRouter = WheelMediaKeyRouter(::report)
    private var artwork: Bitmap? = null
    private var artworkListener: ((Bitmap?) -> Unit)? = null
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) {
            releaseLocked()
            artworkOwner = artworkQueue.newSession()
        }
        appContext = context.applicationContext
        controller = next
        next.playbackListener = { playing -> onIphonePlaying(next, playing) }
        next.nowPlayingListener = { update -> onNowPlayingChanged(next, update) }
        next.artworkListener = { id, bytes -> onArtworkChanged(next, id, bytes) }
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.playbackListener = null
        expected.nowPlayingListener = null
        expected.artworkListener = null
        controller = null
        releaseLocked()
    }

    /** Called when CarPlay music starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) {
        val generation = synchronized(this) { audioCallbackGeneration }
        mainHandler.post { synchronized(this) {
            if (generation == audioCallbackGeneration) updateLocked(active)
        } }
    }

    /** A wireless controller may survive its phone session; release its audio source meanwhile. */
    @Synchronized
    fun onPhoneSessionEnded() {
        releaseLocked()
        if (controller != null) artworkOwner = artworkQueue.newSession()
    }

    @Synchronized
    fun onPhoneSessionStarted() {
        mainAudioOwner = CarPlayAudioOwner.UNKNOWN
        val context = appContext ?: return
        if (controller != null && session == null) start(context)
    }

    /** Protocol ownership is separate from the AudioManager focus grant. */
    @Synchronized
    fun onMainAudioOwner(owner: CarPlayAudioOwner) {
        val previous = mainAudioOwner
        if (owner == previous) {
            report("mainAudio unchanged=$owner; Android focus left unchanged")
            return
        }
        mainAudioOwner = owner
        session?.isActive = owner.allowsMedia
        if (!owner.allowsMedia) {
            mediaPreparationGeneration++
            userRecoveryFocus = false
            focusRequest?.let { appContext?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }
            focusHeld = false
            transientFocusInterrupted = false
        } else if (mediaAudioActive && !previous.allowsMedia) regainFocusLocked()
        report("mainAudio owner=$owner streamActive=$mediaAudioActive focusHeld=$focusHeld")
    }

    /** The iPhone started or stopped playing; may run on any thread. */
    private fun onIphonePlaying(expected: CarPlayController, playing: Boolean) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                // Even an active CarPlay media stream can carry another phone app's voice.
                // Metadata is not an explicit request to displace the car's current audio source.
                report("iPhone playback metadata playing=$playing; Android focus left unchanged")
            }
        }
    }

    /** Publishes the iPhone's retained metadata through Android's system media session. */
    private fun onNowPlayingChanged(expected: CarPlayController, update: CarPlayNowPlaying) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                val previousArtwork = artwork
                if (nowPlaying.artworkTransferId != update.artworkTransferId) {
                    artwork = update.artworkTransferId?.let { id ->
                        if (artworkCache.containsKey(id)) artworkCache[id] else null
                    }
                }
                if (nowPlaying.elapsedMillis != update.elapsedMillis) elapsedUpdatedAt = SystemClock.elapsedRealtime()
                val metadataChanged = metadataChanged(nowPlaying, update) || artwork !== previousArtwork
                nowPlaying = update
                // The iPhone repeats NowPlayingUpdate about twice a second for the position alone.
                // Republishing the metadata each time sent a copy of the artwork through system_server
                // to every media listener, and on a DiLink 5.0 Tang that exhausted memory within
                // minutes. The position goes in the playback state.
                if (metadataChanged) session?.setMetadata(androidMetadata(update, artwork))
                publishPlaybackStateLocked()
            }
        }
    }

    @Synchronized
    private fun onArtworkChanged(expected: CarPlayController, id: Int, bytes: ByteArray) {
        if (controller !== expected) return
        artworkOwner?.let { artworkQueue.submit(it, id, bytes) }
    }

    @Synchronized
    private fun onArtworkDecoded(expected: Any, id: Int, decoded: Bitmap?) {
        if (artworkOwner !== expected) {
            decoded?.recycle()
            return
        }
        artworkCache.remove(id)
        artworkCache[id] = decoded
        while (artworkCache.size > MAX_CACHED_ARTWORK) artworkCache.remove(artworkCache.keys.first())
        if (nowPlaying.artworkTransferId == id) {
            artwork = decoded
            session?.setMetadata(androidMetadata(nowPlaying, artwork))
            val notify = artworkListener
            if (notify != null) mainHandler.post { notify(decoded) } else Unit
        }
    }

    /**
     * The album art CarPlay sent for the current item, for the dashboard song card.
     *
     * Owned here because this is where CarPlay's artwork transfers already land; the card only ever
     * draws it, never recycles it.
     */
    @Synchronized
    fun currentArtwork(): Bitmap? = artwork

    /** Called on the main thread whenever the current artwork changes (null included). */
    @Synchronized
    fun setArtworkListener(listener: ((Bitmap?) -> Unit)?) {
        artworkListener = listener
    }

    // Another car app (its own Spotify, the radio) took audio focus and with it the steering-wheel
    // keys. When CarPlay starts playing again it becomes the car's media source again, as any player
    // would; only the start counts, so a car source picked while the iPhone plays on is not undone.
    private fun regainFocusLocked(userRequested: Boolean = false) {
        if ((!mediaAudioActive && !userRequested) || !mainAudioOwner.allowsMedia) return
        val request = focusRequest ?: return
        // Only an explicit media-control action can override an unresolved transient loss.
        // Repeated resource/metadata notifications must not trigger the native Bluetooth PAUSE.
        if (transientFocusInterrupted && !userRequested) return
        if (focusHeld && !transientFocusInterrupted) return
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return
        if (audio.mode != AudioManager.MODE_NORMAL) return
        focusHeld = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (focusHeld) transientFocusInterrupted = false
        report("Android media focus regained=$focusHeld mainAudio=$mainAudioOwner streamActive=$mediaAudioActive")
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        mediaAudioActive = active
        if (active) userRecoveryFocus = false
        if (active && session == null) start(context) else if (active) regainFocusLocked()
        publishPlaybackStateLocked()
    }

    private fun start(context: Context) {
        // Audio always comes from CarPlay. Keep its source/session/focus alive independently of
        // the wheel-key switch; otherwise AVRCP may select Bluetooth while CarPlay still renders.
        // Hardware duplication is suppressed at send(), never by abandoning the audio source.
        val audio = context.getSystemService(AudioManager::class.java)
        val generation = audioCallbackGeneration
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change ->
                report("Android media focus change=$change mainAudio=$mainAudioOwner streamActive=$mediaAudioActive")
                // Only a permanent loss moves the car's media keys elsewhere; a transient one is
                // remembered so the next iPhone play can retry it (some units never send GAIN).
                synchronized(this) {
                    if (generation != audioCallbackGeneration || focusRequest == null) return@synchronized
                    if (change == AudioManager.AUDIOFOCUS_GAIN && !mainAudioOwner.allowsMedia) {
                        report("ignored Android focus gain while mainAudio=$mainAudioOwner")
                        return@synchronized
                    }
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            focusHeld = false
                            transientFocusInterrupted = false
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> transientFocusInterrupted = true
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            focusHeld = true
                            transientFocusInterrupted = false
                        }
                    }
                }
            }, mainHandler)
            .build()
        val granted = mediaAudioActive && mainAudioOwner.allowsMedia &&
            audio?.mode == AudioManager.MODE_NORMAL &&
            audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequest = request
        focusHeld = granted
        transientFocusInterrupted = false
        session = MediaSession(context, "DiPlay CarPlay").apply {
            setCallback(callback, mainHandler)
            setMetadata(androidMetadata(nowPlaying, artwork))
            isActive = mainAudioOwner.allowsMedia
        }
        report("media session active focusGranted=$granted mainAudio=$mainAudioOwner streamActive=$mediaAudioActive")
    }

    private fun releaseLocked() {
        audioCallbackGeneration++
        mediaIntentSerial++
        mediaPreparationGeneration++
        userRecoveryFocus = false
        mainAudioOwner = CarPlayAudioOwner.UNKNOWN
        wheelRouter.cancel()
        artworkOwner = null
        artworkQueue.clear()
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        mediaAudioActive = false
        nowPlaying = CarPlayNowPlaying()
        artwork = null
        artworkCache.clear()
        focusRequest?.let { request -> appContext?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
        focusRequest = null
        focusHeld = false
        transientFocusInterrupted = false
    }

    private fun publishPlaybackStateLocked() {
        val playing = if (nowPlaying.elapsedMillis != null || nowPlaying.title != null) {
            nowPlaying.playing
        } else {
            mediaAudioActive
        }
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    nowPlaying.elapsedMillis ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                    // The iPhone sends elapsed time only on play, pause or seek, so Android must
                    // extrapolate from when it arrived, not from this republish.
                    elapsedUpdatedAt,
                )
                .build(),
        )
    }

    private fun send(index: Int, source: String, fromWheel: Boolean) {
        // While the car's video player is on screen the wheel drives it: a CarPlay play/pause would
        // make the iPhone end the video session.
        if (CarPlayVideo.onMediaKey(index)) {
            Log.i(TAG, "media key $source -> car video player $index")
            return
        }
        // Window and MediaSession deliveries share one gate. The preference selects key
        // arbitration only; both modes keep the same audio session and channel configuration.
        if (appContext?.getSystemService(AudioManager::class.java)?.mode != AudioManager.MODE_NORMAL) return
        val expected = controller ?: return
        val phoneIdentity = expected.mediaSessionIdentity()
        val external = fromWheel && appContext?.let { AirPlayPersistence.loadMediaViaCarBluetooth(it) } == true
        wheelRouter.route(index, external) sendCarPlay@{
            if (appContext?.getSystemService(AudioManager::class.java)?.mode != AudioManager.MODE_NORMAL || CarPlayVideo.activity != null) return@sendCarPlay
            val expectStream = index == CarPlayMediaButton.PLAY ||
                (index == CarPlayMediaButton.PLAY_PAUSE && !nowPlaying.playing) ||
                (index in setOf(CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS) && (nowPlaying.playing || mediaAudioActive))
            if (!expectStream) synchronized(this) {
                mediaPreparationGeneration++ // A pause cancels an older pending PLAY, not vice versa.
                releaseRecoveryFocusLocked()
            }
            val generation = audioCallbackGeneration
            val preparation = mediaPreparationGeneration
            val deliver = deliver@{
                synchronized(this) {
                    if (controller !== expected || expected.mediaSessionIdentity() !== phoneIdentity ||
                        generation != audioCallbackGeneration ||
                        preparation != mediaPreparationGeneration ||
                        (fromWheel && appContext?.let { AirPlayPersistence.loadMediaViaCarBluetooth(it) } == true) ||
                        appContext?.getSystemService(AudioManager::class.java)?.mode != AudioManager.MODE_NORMAL ||
                        CarPlayVideo.activity != null) return@deliver
                    if (expectStream) {
                        val hadFocus = focusHeld && !transientFocusInterrupted
                        regainFocusLocked(userRequested = true)
                        if (!mediaAudioActive && focusHeld && !hadFocus) userRecoveryFocus = true
                    }
                    val sent = expected.sendMediaButton(index)
                    report("media key $source wheel=$fromWheel -> CarPlay $index queued=$sent")
                    val intent = ++mediaIntentSerial
                    if (sent && expectStream) mainHandler.postDelayed({ synchronized(this) {
                        if (controller === expected && generation == audioCallbackGeneration &&
                            intent == mediaIntentSerial && !mediaAudioActive) {
                            releaseRecoveryFocusLocked()
                            report("CarPlay media not restored after user key; mainAudio=$mainAudioOwner; phone output route unverified; no automatic replay")
                        }
                    } }, 5_000L)
                    if (!sent) releaseRecoveryFocusLocked()
                }
            }
            val context = appContext ?: return@sendCarPlay
            if (expectStream && !mediaAudioActive) {
                val once = java.util.concurrent.atomic.AtomicBoolean()
                val complete: (Boolean) -> Unit = { verified ->
                    if (once.compareAndSet(false, true)) mainHandler.post {
                        report("user audio recovery profilesVerified=$verified; one media command only")
                        deliver()
                    }
                }
                if (com.freddyq.diplay.customization.VehicleCustomizations.prepareMediaPlayback(context, complete)) {
                    mainHandler.postDelayed({ complete(false) }, 2_500L)
                } else deliver()
            } else deliver()
        }
    }

    private fun releaseRecoveryFocusLocked() {
        if (!userRecoveryFocus || mediaAudioActive) return
        focusRequest?.let { appContext?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }
        userRecoveryFocus = false
        focusHeld = false
        transientFocusInterrupted = false
    }

    fun fromWindow(index: Int) = send(index, "window", fromWheel = true)

    private fun report(message: String) {
        if (!com.freddyq.diplay.customization.VehicleCustomizations.shouldRecordDiagnostic(message)) return
        Log.i(TAG, message)
        com.freddyq.diplay.customization.VehicleCustomizations.diagnostic("CarPlayAudio", message)
    }

    private val callback = CarPlayMediaCallback(::send)

    /** Whether [next] changes what the media session's metadata shows; position and play state do not. */
    internal fun metadataChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        previous.copy(elapsedMillis = null, playing = false) != next.copy(elapsedMillis = null, playing = false)

    internal fun androidMetadata(info: CarPlayNowPlaying, artwork: Bitmap? = null): MediaMetadata =
        MediaMetadata.Builder().apply {
            info.title?.let {
                putString(MediaMetadata.METADATA_KEY_TITLE, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
            }
            info.artist?.let {
                putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
            }
            info.album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
            info.durationMillis?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            info.sourceApp?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
            artwork?.let {
                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            }
        }.build()

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ARTWORK_SOURCE_DIMENSION ||
            bounds.outHeight !in 1..MAX_ARTWORK_SOURCE_DIMENSION
        ) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ARTWORK_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val largest = maxOf(decoded.width, decoded.height)
        if (largest <= MAX_ARTWORK_DIMENSION) return decoded
        val scale = MAX_ARTWORK_DIMENSION.toFloat() / largest
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { scaled -> if (scaled !== decoded) decoded.recycle() }
    }

    private const val MAX_ARTWORK_DIMENSION = 384
    private const val MAX_ARTWORK_SOURCE_DIMENSION = 8_192
    private const val MAX_CACHED_ARTWORK = 4
}

/**
 * Media-session input → CarPlay presses. Hardware keys arrive as button events and keep the toggle;
 * media controllers (not hardware keys) call [onPlay] and [onPause] with an explicit intent.
 */
internal class CarPlayMediaCallback(
    private val send: (index: Int, source: String, fromWheel: Boolean) -> Unit,
) : MediaSession.Callback() {
    // Some head-unit ROMs deliver the same hardware press twice: once through the media-button
    // event and once through the skip callbacks, so a single steering-wheel press skipped two
    // tracks. Debounce per index; the debounce is short enough to leave deliberate rapid presses
    // intact.
    private val lastSentAt = LongArray(6) { -1L } // indices 1..5 (PLAY..PREVIOUS); -1 = never sent
    private val debounceMillis = 250L

    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        val index = CarPlayMediaButton.forKeyCode(event.keyCode) ?: return super.onMediaButtonEvent(mediaButtonIntent)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            deliver(index, KeyEvent.keyCodeToString(event.keyCode), fromWheel = true)
        }
        return true
    }

    override fun onPlay() = deliver(CarPlayMediaButton.PLAY, "play", fromWheel = false)
    override fun onPause() = deliver(CarPlayMediaButton.PAUSE, "pause", fromWheel = false)
    override fun onSkipToNext() = deliver(CarPlayMediaButton.NEXT, "next", fromWheel = false)
    override fun onSkipToPrevious() = deliver(CarPlayMediaButton.PREVIOUS, "previous", fromWheel = false)

    private fun deliver(index: Int, source: String, fromWheel: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val slot = if (index in 1..5) index else 0
        val last = lastSentAt[slot]
        lastSentAt[slot] = now
        if (last >= 0 && now - last < debounceMillis) return
        send(index, source, fromWheel)
    }
}
