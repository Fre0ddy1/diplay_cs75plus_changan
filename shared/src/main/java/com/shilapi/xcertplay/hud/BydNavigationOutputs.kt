package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Nonblocking boundary between phone control messages and vendor services. */
object BydNavigationOutputs {
    /** Recover a journaled interrupted output when the app opens, even before a phone reconnects. */
    fun onAppOpened(context: Context) {
        if (BydStandaloneHudOutput.available(context)) start(context)
        // Read the battery early, so a reading is ready when CarPlay identifies (see batteryStatus).
        if (BydOutputSettings.batteryToIphoneActive(context)) BydBatteryStatus.start(context)
    }
    fun setDiagnosticHold(hold: Boolean) { BydStandaloneHudOutput.syntheticHold = hold }
    @Volatile private var useStandalone = false
    @Volatile private var overlayListener: ((ClusterTurnGuidance?) -> Unit)? = null
    @Volatile private var laneOverlayListener: ((BydLaneGuidance?) -> Unit)? = null
    @Volatile private var songListener: ((ClusterSong?) -> Unit)? = null
    /** One raw dump per process is enough to see whether the phone ever sends lyric text. */
    private var songDumped = false
    private val songState = ClusterSongState()
    @Volatile private var publishedSong: ClusterSong? = null
    @Volatile var routeTraceListener: ((String) -> Unit)? = null
    private val overlayLock = Any()
    private var publishedLane: BydLaneGuidance? = null
    private var publishedOverlay: ClusterTurnGuidance? = null
    private val overlayRoute = BydHudRouteState(
        staleRouteNs = 120_000_000_000L,
        emptyListHideNs = 8_000_000_000L,
    )
    private val standalone = NavigationOutputWorker("diplay-standalone-output", BydStandaloneNavigationBridge::clear)
    private val hud = NavigationOutputWorker("diplay-hud-output", BydHudBridge::clear)
    private val cluster = NavigationOutputWorker("diplay-cluster-output", BydClusterBridge::clear)

    /** The host reports whether its CarPlay map window is on the cluster (see [BydClusterMapPause]). */
    fun setClusterMapShown(shown: Boolean) { BydClusterMapPause.clusterMapShown = shown }

    /** The running CarPlay session: told every second whether the cluster currently shows the map. */
    fun setClusterStreamControl(control: (Boolean) -> Unit) { BydClusterMapPause.streamControl = control }

    fun clearClusterStreamControl(control: (Boolean) -> Unit) {
        if (BydClusterMapPause.streamControl == control) BydClusterMapPause.streamControl = null
    }

    /**
     * The car's battery for the iPhone's vehicle status; starts reading it over adb. The electric
     * vehicle is declared only once a reading is there (see withVehicleStatusFrom).
     */
    fun batteryStatus(context: Context): com.shilapi.xcertplay.transport.VehicleStatusProvider =
        BydBatteryStatus.also { it.start(context) }

    /** The car's wheel speed and gear for the iPhone's dead reckoning; read over adb while asked for. */
    fun wheelSpeed(context: Context): com.shilapi.xcertplay.transport.VehicleSpeedSource =
        BydWheelSpeedSource.attach(context)
    /** Whether the car is in P (read over adb), or null when it cannot tell. Blocking. */
    fun parked(context: Context): Boolean? = BydParkedState.parked(context.applicationContext)

    fun start(context: Context) {
        val app = context.applicationContext
        useStandalone = BydStandaloneHudOutput.available(app)
        if (useStandalone) standalone.start { BydStandaloneNavigationBridge.initialize(app) }
        else {
            hud.start { BydHudBridge.initialize(app) }
            cluster.start { BydClusterBridge.initialize(app) }
        }
        BydClusterMapPause.initialize(app)
        BydClusterSong.attach(app)
    }

    internal fun onFrame(frame: Iap2Frame) {
        if (frame.messageId == ClusterSongState.NOW_PLAYING_UPDATE) {
            BydClusterSong.onFrame(frame)
            // The dashboard card reads the same frames: the BYD writer above only targets the
            // BYD HUD, so nothing else published the title/artist for our own cluster overlay.
            if (!songDumped) {
                songDumped = true
                routeTraceListener?.invoke(
                    "NowPlaying raw ${frame.payload.size}B: " + hexPreview(frame.payload),
                )
            }
            val next = synchronized(songState) {
                songState.accept(frame)
                songState.current()
            }
            if (next != publishedSong) {
                publishedSong = next
                songListener?.invoke(next)
            }
            return
        }
        // Route-guidance family (0x5201/0x5202/0x5204): forward a raw TLV trace to the host
        // diagnostics. The full iAP2 layout is undocumented, so the trace lets us discover any
        // hidden fields (traffic status, speed limits…) from a real capture before writing
        // parsers for them.
        if (frame.messageId == BydHudRouteState.ROUTE_GUIDANCE_UPDATE ||
            frame.messageId == BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE ||
            frame.messageId == BydHudRouteState.LANE_GUIDANCE_INFO_UPDATE
        ) {
            val trace = synchronized(overlayLock) { overlayRoute.tlvTrace(frame.messageId, frame.payload) }
            routeTraceListener?.invoke(trace)
        }
        if (frame.messageId == BydHudRouteState.LANE_GUIDANCE_INFO_UPDATE) {
            updateLaneOverlay(frame)
            return
        }
        if (frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) return
        val owned = frame // Iap2Frame is immutable and defensively copies its payload.
        updateOverlay(owned)
        if (useStandalone) standalone.submit { BydStandaloneNavigationBridge.onFrame(owned) }
        else {
            hud.submit { BydHudBridge.onFrame(owned) }
            cluster.submit { BydClusterBridge.onFrame(owned) }
        }
    }

    /** Live next-turn state for the dashboard overlay. Called from the iAP2 thread. */
    fun setTurnOverlayListener(listener: ((ClusterTurnGuidance?) -> Unit)?) {
        overlayListener = listener
        val next = currentOverlay()
        synchronized(overlayLock) { publishedOverlay = next }
        listener?.invoke(next)
    }

    /** The song now playing, for the dashboard card. Called from the iAP2 thread. */
    fun setSongListener(listener: ((ClusterSong?) -> Unit)?) {
        songListener = listener
        listener?.invoke(publishedSong)
    }

    /** Live lane guidance for the dashboard overlay. Called from the iAP2 thread. */
    fun setLaneOverlayListener(listener: ((BydLaneGuidance?) -> Unit)?) {
        laneOverlayListener = listener
        val next = currentLane()
        synchronized(overlayLock) { publishedLane = next }
        listener?.invoke(next)
    }

    /** Hex preview so a UTF-8 title in the dump can be decoded offline; ASCII would lose it. */
    private fun hexPreview(bytes: ByteArray): String {
        val n = minOf(bytes.size, 400)
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) sb.append(String.format("%02x", bytes[i]))
        return sb.toString()
    }

    private fun updateLaneOverlay(frame: Iap2Frame) {
        val change = synchronized(overlayLock) { overlayRoute.accept(frame.messageId, frame.payload) }
        if (change != BydHudRouteChange.NONE) refreshLaneOverlay()
    }

    /** Called every second while the presentation owner lives, even without incoming frames. */
    fun refreshLaneOverlay() {
        val next = synchronized(overlayLock) {
            val current = currentLane()
            if (current == publishedLane) return
            publishedLane = current
            current
        }
        laneOverlayListener?.invoke(next)
    }

    private fun currentLane(): BydLaneGuidance? = synchronized(overlayLock) {
        overlayRoute.currentLanes()
    }

    private fun updateOverlay(frame: Iap2Frame) {
        val change = synchronized(overlayLock) { overlayRoute.accept(frame.messageId, frame.payload) }
        if (change != BydHudRouteChange.NONE) refreshTurnOverlay()
    }

    /** Called every second while the presentation owner lives, even without incoming frames. */
    fun refreshTurnOverlay() {
        val next = synchronized(overlayLock) {
            val current = currentOverlay()
            if (current == publishedOverlay) return
            publishedOverlay = current
            current
        }
        overlayListener?.invoke(next)
    }

    private fun currentOverlay(): ClusterTurnGuidance? = synchronized(overlayLock) {
        // cardContext() (not currentApple()) so the exit badge and the road-name card's
        // secondary "current road" line get their data; currentApple() drops both fields.
        overlayRoute.cardContext()?.let { ClusterTurnGuidance.from(BydClusterFrame.from(it)) }
    }

    /** True while the iPhone reports an active route (iAP2 0x5201 state is not NoRouteSet/Arrived). */
    fun navigationActive(): Boolean = synchronized(overlayLock) { overlayRoute.isNavigating() }

    /** The dashboard song setting changed; applies at once. */
    fun clusterSongChanged(enabled: Boolean) = BydClusterSong.settingChanged(enabled)

    /** Best effort while alive; Android does not guarantee callbacks before force-stop. */
    fun endNow() {
        standalone.clear(); hud.clear(); cluster.clear(); BydClusterSong.end()
        synchronized(overlayLock) { overlayRoute.clear() }
        refreshTurnOverlay()
        refreshLaneOverlay()
        // The song belongs to the session that just ended. Keeping it would make the next
        // session's card show the last track until the iPhone's StartNowPlayingUpdates response
        // arrives - the field report after a settings round trip: new audio, old lyrics.
        synchronized(songState) {
            songState.clear()
            publishedSong = null
        }
        songListener?.invoke(null)
    }
}
