package com.shilapi.xcertplay

import android.app.Presentation
import com.shilapi.xcertplay.hud.ClusterSong
import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydLaneGuidance
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * Shows CarPlay's instrument-cluster stream on a BYD cluster projection display.
 *
 * BYD exposes the cluster's projection area as public presentation displays owned by
 * com.byd.containerservice; the stock map (com.byd.launchermap) draws there the same way. The
 * cluster only shows this display while its projection mode is on, which DiPlay cannot switch.
 */
internal class ClusterMapPresentation(
    context: Context,
    display: Display,
    private val theme: DiLink51ClusterLayout.Theme = DiLink51ClusterLayout.theme(context),
    private val onMirrorSurface: (Surface?) -> Unit,
    private val onNavSurface: (Surface?) -> Unit,
    private val navOnlyCards: Boolean = false,
) : Presentation(context, display) {
    private var waitingLabel: TextView? = null
    private var turnCardView: ClusterTurnCardView? = null
    private var etaCardView: ClusterEtaCardView? = null
    private var roadNameCardView: ClusterRoadNameView? = null
    /** Song now playing, top centre. Title/artist only - the protocol carries no lyrics. */
    private var songView: ClusterSongView? = null
    private var genericTextureView: TextureView? = null
    private var navTextureView: TextureView? = null
    private val projectionSize: Point = sizeOf(display)
    var mirrorOutputSurface: Surface? = null
        private set
    var navOutputSurface: Surface? = null
        private set

    // Nav-only card group state. When navOnlyCards is true (Chang'an generic path) the turn,
    // ETA and lane cards are visible ONLY in nav-only mode: whole-screen mirror shows the raw
    // cropped mirror, no cards. DiLink 5.1 keeps the upstream behavior (turn card follows the
    // "dashboard shows" content setting, no ETA/lane cards).
    private var mirrorMode = false
    /** Last mode requested by the host, kept even while the TextureViews are still being created. */
    private var desiredMirror = AirPlayPersistence.loadClusterMirrorMain(context)
    private var lastTurnGuidance: ClusterTurnGuidance? = null
    private var lastEtaGuidance: ClusterTurnGuidance? = null
    private var lastRoadNameGuidance: ClusterTurnGuidance? = null

    // Per-frame cost killers: card show/hide switches and the fronted-card set are cached.
    // applyCardsVisibility runs on every guidance tick (per second); reading SharedPreferences
    // or calling bringToFront() there would requestLayout/re-read the disk every frame.
    // Load them at construction: on a cold start the first guidance tick can arrive before any
    // settings change, and the old defaults (true) showed cards the driver had switched off
    // until a reconnect re-read them.
    private var cardVisibleCache: Map<String, Boolean> = mapOf(
        "eta" to AirPlayPersistence.loadClusterCardVisible(context, "eta"),
        "road" to AirPlayPersistence.loadClusterCardVisible(context, "road"),
    )
    private var turnCardVisibleCache = AirPlayPersistence.loadClusterTurnCardVisible(context)
    private var frontedCards: List<View> = emptyList()
    /** The window's root, where guidance cards are added and removed as the mode changes. */
    private var cardsRoot: FrameLayout? = null

    /**
     * Re-applies the saved dashboard target rectangle to the generic TextureView without
     * destroying/recreating the window or its decoder surface. Called by the host when the
     * "dashboard picture adjust" panel closes; a no-op on the DiLink 5.1 layout path.
     *
     * Order of operations (driver requirement, 10-08):
     * 1. CROP the whole-mirror source first: the left rail (CarPlay dock, ~14%) is removed
     *    from the SOURCE coordinates, so the rail is gone from the picture entirely.
     * 2. Then APPLY the dashboard picture adjust rect: the cropped picture is scaled
     *    (aspect-preserving) to FIT inside the target rect, centred on it. Fit - not fill -
     *    so every slider move shrinks/grows the picture around its centre instead of sliding
     *    it or cropping its edges (field report 10-08 18:2x: fill made the height slider read
     *    as "the picture is dragged upward"). The leftover margin shows the backdrop.
     */
    fun refreshCrop() {
        val textureView = genericTextureView ?: return
        val baseW = projectionSize.x
        val baseH = projectionSize.y
        // The auto navigation switch changes the live mode without writing the saved setting;
        // use this window's actual mode, not the persisted one, or crop adjustments are ignored
        // while the dashboard is showing the mirror.
        if (!mirrorMode) {
            // Nav-only stream: full-screen, untouched. No target frame and no left-rail crop
            // - the driver wants the raw navigation stream edge to edge.
            textureView.setTransform(Matrix())
            textureView.invalidate()
            Log.i(TAG, "nav-only stream: full screen, no crop on ${baseW}x${baseH}")
            return
        }
        // Step 1 - source crop: remove the left rail from the source picture (fixed pixels,
        // set once by the driver; the old percent-based crop default cropped too much).
        val cropLeftPx = AirPlayPersistence.loadClusterMirrorLeftCropPx(context)
        val availW = baseW - cropLeftPx
        // Step 2 - dashboard picture adjust target rect (percent of the dashboard panel). The values
        // may be negative or above 100: the driver uses them to push the picture off an edge or
        // enlarge it past the panel, so no clamping to 0..100 happens here - only a floor of one
        // pixel keeps the matrix invertible.
        val xPct = AirPlayPersistence.loadClusterCropLeftPercent(context)
        val yPct = AirPlayPersistence.loadClusterCropTopPercent(context)
        val wPct = AirPlayPersistence.loadClusterCropWidthPercent(context)
        val hPct = AirPlayPersistence.loadClusterCropHeightPercent(context)
        val w = (wPct * baseW / 100).coerceAtLeast(1)
        val h = (hPct * baseH / 100).coerceAtLeast(1)
        val x = xPct * baseW / 100
        val y = yPct * baseH / 100
        // Pipeline (10-08 field requirements): FIRST crop the left rail out of the SOURCE
        // picture, THEN fit the remaining picture into the target rect. Fit - not cover - so
        // every adjustment to the frame is immediately visible: making the frame smaller
        // visibly shrinks the dashboard picture.
        //
        // A single TextureView matrix cannot express both cleanly: the source crop is a shift
        // of the whole buffer by -cropLeftPx*scale, while centering the cropped picture in the
        // rect would otherwise let the left rail leak back into the left margin. Two steps:
        //   1. Matrix maps source (cropLeftPx,0) to the picture's top-left (picLeft,picTop),
        //      which pushes the rail out of the visible area entirely.
        //   2. Clip the TextureView's left edge so any rail pixels that would leak into the
        //      left margin (fit margins) are never drawn - the margin shows the black backdrop
        //      instead, symmetric with the right margin.
        // FIT (contain), not cover: scale is the MINIMUM of the two axes so the cropped
        // picture always fits inside the target rect, aspect-preserving and centred. Cover
        // (maxOf) kept the scale pinned to the wider axis and only translated the frame when
        // the height slider moved, which read as "the whole picture is dragged upward"
        // (field report 10-08 18:2x). With fit, shrinking the frame shrinks the picture
        // symmetrically around its centre; the leftover margin shows the backdrop.
        val scale = minOf(w.toFloat() / availW, h.toFloat() / baseH)
        val drawnW = availW * scale
        val drawnH = baseH * scale
        val picLeft = x + (w - drawnW) / 2f
        val picTop = y + (h - drawnH) / 2f
        val offsetX = picLeft - cropLeftPx * scale
        val offsetY = picTop
        textureView.setTransform(Matrix().apply {
            setScale(scale, scale)
            postTranslate(offsetX, offsetY)
        })
        // Only the left edge needs clipping (the rail). When picLeft <= 0 the buffer's left
        // edge already maps to a source x >= cropLeftPx, so nothing can leak. Fall back to
        // baseW/baseH when the view is not laid out yet (width == 0 would clip everything).
        val clipLeft = if (picLeft > 0f) picLeft.toInt() else 0
        val clipRight = if (textureView.width > 0) textureView.width else baseW
        val clipBottom = if (textureView.height > 0) textureView.height else baseH
        textureView.setClipBounds(Rect(clipLeft, 0, clipRight, clipBottom))
        // The head-unit TextureView keeps rendering the LAST decoded frame when the stream is
        // idle; a new transform alone is not re-applied until the next frame arrives (the
        // driver sees "no change until the console moves"). Force a redraw of the current
        // buffer with the new matrix now, both this frame and on the next vsync.
        textureView.invalidate()
        textureView.postInvalidateOnAnimation()
        Log.i(
            TAG,
            "mirror rect x=$x y=$y w=$w h=$h fit=$scale drawn=${drawnW}x${drawnH} " +
                "placed=($offsetX,$offsetY) leftCrop=${cropLeftPx}px clipLeft=$clipLeft on ${baseW}x${baseH}",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start transparent on the generic path.
        //
        // incall lays an ext-screen window out as its small preview box at the right of the panel
        // until the projection channel is open, and our window is created several seconds before
        // the open sequence. The activity calls setMapVisible(false) right after show(), but the
        // window is already on screen for the frames in between - that is the driver's "冷启动时
        // 仪表右边中间自动投一个小方块". Being transparent from the moment the window exists means
        // the box is never seen; setMapVisible(true) only runs once the channel reports open.
        if (!DiLink51ClusterLayout.supported()) {
            window?.attributes = window?.attributes?.apply { alpha = 0f }
        }
        val size = projectionSize
        val adaptive = DiLink51ClusterLayout.supported()
        val plan = if (adaptive) DiLink51ClusterLayout.plan(size.x, size.y, theme) else null
        val dark = DiLink51ClusterLayout.dark(context, theme)
        val backdrop = if (dark) Color.rgb(15, 22, 30) else Color.rgb(207, 218, 229)
        val root = FrameLayout(context).apply {
            setBackgroundColor(if (plan == null) Color.BLACK else if (plan.fullMap) backdrop else Color.TRANSPARENT)
        }
        if (plan != null) {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        val videoParams = if (plan == null) FrameLayout.LayoutParams(-1, -1) else
            FrameLayout.LayoutParams(plan.width, plan.height, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = plan.left; topMargin = plan.top
            }
        if (plan != null) {
            val textureView = TextureView(context)
            fun transform() {
                textureView.setTransform(Matrix().apply {
                    setScale(DiLink51ClusterLayout.STREAM_WIDTH.toFloat() / plan.width,
                        DiLink51ClusterLayout.STREAM_HEIGHT.toFloat() / plan.height)
                    postTranslate(-plan.sourceLeft.toFloat(), -plan.sourceTop.toFloat())
                })
            }
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    texture.setDefaultBufferSize(DiLink51ClusterLayout.STREAM_WIDTH, DiLink51ClusterLayout.STREAM_HEIGHT)
                    transform()
                    mirrorOutputSurface = Surface(texture).also(onMirrorSurface)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = transform()
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    onMirrorSurface(null)
                    mirrorOutputSurface?.release()
                    mirrorOutputSurface = null
                    return true
                }
            }
            root.addView(textureView, videoParams)
            if (plan.fullMap) root.addView(InstrumentContrastView(context, plan, backdrop), FrameLayout.LayoutParams(-1, -1))
            Log.i(TAG, "layout=$theme viewport=$plan source=${plan.sourceLeft},${plan.sourceTop} dark=$dark")
        } else {
            // Generic path (Changan Feiyu OS and other OEMs). TWO TextureViews keep BOTH CarPlay
            // streams (110 whole-screen mirror + 111 navigation) permanently bound to the incall
            // sink from first projection; mode switches only flip view visibility. Keeping the 111
            // stream continuously active is what stops the head-unit from announcing "全屏导航已开启"
            // every time the driver swipes from whole-screen mirror to nav-only (incall announces on
            // 111 stream (re-)activation events; the OEM box keeps the stream resident for the same
            // reason). A TextureView lets the driver crop/zoom via the "dashboard picture adjust"
            // sliders; the transform maps the saved crop window onto the whole panel, so smaller
            // width/height zooms in and left/top moves the visible window. Only the mirror view
            // carries the crop - the nav view is full-screen untouched.
            val mirrorTextureView = TextureView(context)
            genericTextureView = mirrorTextureView
            mirrorTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    Log.i(TAG, "cluster mirror surface created")
                    texture.setDefaultBufferSize(size.x, size.y)
                    refreshCrop()
                    mirrorOutputSurface = Surface(texture).also(onMirrorSurface)
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = refreshCrop()

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    Log.i(TAG, "cluster mirror surface destroyed")
                    mirrorOutputSurface?.release()
                    mirrorOutputSurface = null
                    onMirrorSurface(null)
                    return true
                }
            }
            root.addView(mirrorTextureView, videoParams)
            // Second TextureView for stream 111. Kept invisible until nav-only mode is selected;
            // its Surface is bound to sink.setSurface(SCREEN_TYPE_ALT, ...) as soon as it exists so
            // the 111 stream stays resident in incall (no re-activation announcement on switches).
            val navView = TextureView(context)
            navTextureView = navView
            navView.visibility = View.GONE
            navView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    Log.i(TAG, "cluster nav surface created")
                    texture.setDefaultBufferSize(size.x, size.y)
                    navOutputSurface = Surface(texture).also(onNavSurface)
                    // The TextureViews are created asynchronously, so a setMode() issued while
                    // they were still null would have been lost (e.g. cold-start auto-project to
                    // nav-only would show the mirror view). Re-assert the last requested mode;
                    // never the saved setting, which the navigation auto-switch bypasses.
                    setMode(desiredMirror)
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    Log.i(TAG, "cluster nav surface destroyed")
                    navOutputSurface?.release()
                    navOutputSurface = null
                    onNavSurface(null)
                    return true
                }
            }
            root.addView(navView, videoParams)
        }
        waitingLabel = TextView(context).apply {
            text = context.getString(R.string.cluster_waiting_for_map)
            setTextColor(if (plan != null && !dark) Color.DKGRAY else Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
        }
        root.addView(waitingLabel, FrameLayout.LayoutParams(videoParams))
        // The guidance cards are NOT created here.
        //
        // They belong to the navigation stream only, and the driver's rule is that a dashboard
        // which is not showing that stream must have none of them at all - not hidden, not
        // created - so nothing can flash over CarPlay's own picture before it arrives, and no
        // leftover card can appear when the mode changes. setMode() creates them from the saved
        // switches once the window is known to be nav-only, which happens before the stream is
        // shown.
        cardsRoot = root
        // Added last so it draws above the guidance cards.
        songView = ClusterSongView(context).apply { visibility = View.GONE }
        root.addView(songView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    /**
     * Selects which resident stream is displayed on the dashboard: whole-screen mirror (110) or
     * navigation stream only (111). Both decoder surfaces stay bound to the sink - only the view
     * visibility changes, so the incall service never sees a 111 (re-)activation and does not
     * announce "全屏导航已开启". On the DiLink 5.1 layout (single TextureView) the mode is a no-op.
     */
    /**
     * Creates the guidance cards the driver's switches ask for.
     *
     * Only ever called in nav-only mode. Everything here is idempotent: an existing card is left
     * alone, a missing one is created with the guidance data already known, and the music card is
     * never touched because it belongs to every mode.
     */
    private fun materializeGuidanceCards() {
        val root = cardsRoot ?: return
        if (mirrorMode) return
        if (turnCardView == null && turnCardVisibleCache) {
            turnCardView = ClusterTurnCardView(context).apply { visibility = View.GONE }
            root.addView(turnCardView, FrameLayout.LayoutParams(-1, -1))
            // A rebuilt card starts at its own defaults, so restore the driver's saved geometry
            // before it can draw.
            turnCardView?.setLayout(
                AirPlayPersistence.loadClusterTurnCardX(context),
                AirPlayPersistence.loadClusterTurnCardY(context),
                AirPlayPersistence.loadClusterCardScalePercent(context, "turn"),
            )
            turnCardView?.setGuidance(lastTurnGuidance)
        }
        if (etaCardView == null && (cardVisibleCache["eta"] ?: true)) {
            etaCardView = ClusterEtaCardView(context).apply { visibility = View.GONE }
            root.addView(etaCardView, FrameLayout.LayoutParams(-1, -1))
            etaCardView?.setGuidance(lastEtaGuidance)
        }
        if (roadNameCardView == null && (cardVisibleCache["road"] ?: true)) {
            roadNameCardView = ClusterRoadNameView(context).apply { visibility = View.GONE }
            root.addView(roadNameCardView, FrameLayout.LayoutParams(-1, -1))
            roadNameCardView?.setGuidance(lastRoadNameGuidance)
        }
        // The cards are inserted after the video surfaces, so they must be raised again.
        listOfNotNull(turnCardView, etaCardView, roadNameCardView).forEach { it.bringToFront() }
        // The music card was added after the guidance cards in onCreate; keep it on top.
        songView?.bringToFront()
    }

    /**
     * Removes every guidance card from the window.
     *
     * Destroyed rather than hidden: a hidden view can still be brought back by a stray
     * setGuidance(), and the driver's complaint was exactly that a card stayed on screen after the
     * dashboard left nav-only mode. The music card stays - it belongs to every mode.
     */
    private fun destroyGuidanceCards() {
        val root = cardsRoot ?: return
        listOfNotNull(turnCardView, etaCardView, roadNameCardView).forEach { root.removeView(it) }
        turnCardView = null
        etaCardView = null
        roadNameCardView = null
        frontedCards = emptyList()
    }

    fun setMode(mirror: Boolean) {
        // Remember the request before checking the views: on a cold start the host can ask for
        // nav-only while the TextureViews are still null, and the surface callback below must
        // re-apply this mode rather than the saved setting (which the auto-switch intentionally
        // does not update).
        desiredMirror = mirror
        mirrorMode = mirror
        // The cards follow the mode, not the TextureViews: both surfaces are created
        // asynchronously and either may report first, so card lifecycle must not be behind that
        // check. This is what guarantees the decision is made before the stream is shown.
        if (navOnlyCards) {
            if (mirror) destroyGuidanceCards() else materializeGuidanceCards()
            applyCardsVisibility()
        }
        val nav = navTextureView ?: return
        val mirrorView = genericTextureView ?: return
        mirrorView.visibility = if (mirror) View.VISIBLE else View.GONE
        nav.visibility = if (mirror) View.GONE else View.VISIBLE
        Log.i(TAG, "cluster display mode: ${if (mirror) "mirror(110)" else "nav-only(111)"}")
    }

    /** Hides the placeholder once the phone streams the cluster screen. */
    fun setStreamActive(active: Boolean) {
        waitingLabel?.visibility = if (active) View.GONE else View.VISIBLE
    }

    /** Current display mode (true = whole-screen mirror, false = nav-only), for diagnostics. */
    fun isMirrorMode(): Boolean = mirrorMode

    /** Window alpha hides the pixels without destroying the TextureView/decoder surface. */
    fun setMapVisible(visible: Boolean) {
        window?.let { window ->
            val alpha = if (visible) 1f else 0f
            if (window.attributes.alpha != alpha) window.attributes = window.attributes.apply { this.alpha = alpha }
        }
    }

    /**
     * The window's current frame, in pixels. incall can lay an ext-screen window out as its small
     * preview box at the right of the panel instead of over the whole panel, and the driver's rule
     * is that a small box must never be visible - a blank dashboard is acceptable, a square is not.
     */
    fun windowFrame(): Point {
        val decor = window?.decorView ?: return Point(0, 0)
        return Point(decor.width, decor.height)
    }

    fun setTurnCardOverlay(xPx: Int, yPx: Int, scalePercent: Int) {
        turnCardView?.setLayout(xPx, yPx, scalePercent)
    }

    /**
     * Live refresh of every nav card after a settings change: the turn card re-applies
     * its layout, the four absolute-position cards just invalidate and re-read the new
     * (x, y) from persistence in onDraw. Called from the overlay-settings listener so a
     * saved position change is visible on the dashboard immediately.
     */
    fun refreshCardOverlays() {
        turnCardView?.setLayout(
            AirPlayPersistence.loadClusterTurnCardX(context),
            AirPlayPersistence.loadClusterTurnCardY(context),
            AirPlayPersistence.loadClusterCardScalePercent(context, "turn"),
        )
        // Drop each card's cached position so the next draw reloads the saved coordinates.
        etaCardView?.notifyPositionChanged()
        roadNameCardView?.notifyPositionChanged()
        // Deliberately outside the nav-only group: the mirror mode shows it as well.
        songView?.notifyPositionChanged()
        // Reload the show/hide switches, then a saved switch change re-applies visibility right away.
        reloadCardVisibilityCache()
        if (navOnlyCards) applyCardsVisibility()
    }

    /** The song now playing. Null clears the card. */
    fun setSong(song: ClusterSong?) {
        songView?.setSong(song)
    }

    /** Album art for the current item; the lyrics card lays it out beside or above the text. */
    fun setSongArtwork(artwork: android.graphics.Bitmap?) {
        songView?.setArtwork(artwork)
    }

    /** A song-card setting changed; the view drops its caches on the next draw. */
    fun notifySongSettingsChanged() {
        songView?.notifyPositionChanged()
    }

    fun setTurnCardGuidance(guidance: ClusterTurnGuidance?) {
        lastTurnGuidance = guidance
        turnCardView?.setGuidance(guidance)
        if (navOnlyCards) applyCardsVisibility()
    }

    fun setEtaGuidance(guidance: ClusterTurnGuidance?) {
        lastEtaGuidance = guidance
        etaCardView?.setGuidance(guidance)
        if (navOnlyCards) applyCardsVisibility()
    }

    fun setRoadNameGuidance(guidance: ClusterTurnGuidance?) {
        lastRoadNameGuidance = guidance
        roadNameCardView?.setGuidance(guidance)
        if (navOnlyCards) applyCardsVisibility()
    }

    /**
     * Nav-only card group visibility. Whole-screen mirror shows the raw cropped mirror with
     * NO cards; nav-only mode shows the turn card and the ETA card whenever their guidance
     * data is present. DiLink 5.1 (navOnlyCards=false) never reaches this branch.
     *
     * Each view's setGuidance() is the authority on its own visibility (turn needs guidance,
     * ETA needs arrival/remaining, road needs a road name), so here we only hide everything in
     * mirror mode, honour the user's per-card show/hide switch, and raise the visible cards above
     * the video layer in nav-only mode. Never force a card VISIBLE here without the same data
     * check - that would show an empty glass block.
     */
    private fun applyCardsVisibility() {
        if (!navOnlyCards) return
        // Not the navigation stream: no guidance card may exist, whatever the switches say.
        if (mirrorMode) {
            destroyGuidanceCards()
            return
        }
        // Nav-only: the driver's switches alone decide which cards exist. A card whose switch was
        // turned off while it was on screen is destroyed here, and one whose switch was turned on
        // is created - so a settings change is reflected without waiting for the next mode change.
        // The music card is not in this group: it is drawn in every mode.
        if (!turnCardVisibleCache) {
            turnCardView?.let { cardsRoot?.removeView(it) }
            turnCardView = null
        }
        if (cardVisibleCache["eta"] == false) {
            etaCardView?.let { cardsRoot?.removeView(it) }
            etaCardView = null
        }
        if (cardVisibleCache["road"] == false) {
            roadNameCardView?.let { cardsRoot?.removeView(it) }
            roadNameCardView = null
        }
        materializeGuidanceCards()
        // bringToFront() triggers a layout pass, so only re-front when the visible set
        // actually changed — guidance ticks every second and must not requestLayout each time.
        val visibleNow = listOfNotNull(turnCardView, etaCardView, roadNameCardView)
            .filter { it.visibility == View.VISIBLE }
        if (visibleNow != frontedCards) {
            frontedCards = visibleNow
            visibleNow.forEach { it.bringToFront() }
        }
    }

    /** Reload the cached show/hide switches after a settings change. */
    private fun reloadCardVisibilityCache() {
        cardVisibleCache = mapOf(
            "eta" to AirPlayPersistence.loadClusterCardVisible(context, "eta"),
            "road" to AirPlayPersistence.loadClusterCardVisible(context, "road"),
        )
        turnCardVisibleCache = AirPlayPersistence.loadClusterTurnCardVisible(context)
    }

    companion object {
        const val TAG = "DiPlay-Cluster"

        /** A verified 5.1 profile chooses its layer explicitly; other firmware keeps PR #5 behavior. */
        fun findDisplay(context: Context, theme: DiLink51ClusterLayout.Theme = DiLink51ClusterLayout.theme(context)): Display? {
            val displayManager = context.getSystemService(DisplayManager::class.java) ?: return null
            if (!DiLink51ClusterLayout.supported()) {
                // Generic path (Changan Feiyu OS, etc.): accept any non-default display, exactly like
                // AMap Companion's findClusterDisplay(). Prefer the presentation category first.
                displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                    .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }?.let { return it }
                displayManager.displays
                    .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }?.let { return it }
                return null
            }
            val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            val name = DiLink51ClusterLayout.displayName(
                displays.map { it.name }, android.os.Build.FINGERPRINT, theme,
            ) ?: return null
            return displays.firstOrNull { it.name == name }?.takeIf {
                val size = sizeOf(it)
                DiLink51ClusterLayout.plan(size.x, size.y, theme) != null
            }
        }

        fun describeDisplays(context: Context): String =
            context.getSystemService(DisplayManager::class.java)?.displays
                ?.joinToString { "${it.displayId}:${it.name}" }.orEmpty()

        fun sizeOf(display: Display): Point = Point().also {
            @Suppress("DEPRECATION")
            display.getRealSize(it)
        }
    }
}

/** Shading belongs to DiPlay's map only; no stock cluster window is changed or covered outside the side card. */
private class InstrumentContrastView(
    context: Context,
    private val plan: DiLink51ClusterLayout.Plan,
    private val background: Int,
) : View(context) {
    private val paint = Paint()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = plan.left.toFloat(); val top = plan.top.toFloat()
        val right = left + plan.width; val bottom = top + plan.height
        fun fade(x0: Float, y0: Float, x1: Float, y1: Float, start: Int, end: Int,
                 l: Float, t: Float, r: Float, b: Float) {
            paint.shader = LinearGradient(x0, y0, x1, y1, start, end, Shader.TileMode.CLAMP)
            canvas.drawRect(l, t, r, b, paint)
        }
        val clear = background and 0x00ffffff
        val soft = (background and 0x00ffffff) or (210 shl 24)
        if (plan.fullMap) {
            fade(left, top, left, top + 64, background, clear, left, top, right, top + 64)
            fade(left, bottom - 48, left, bottom, clear, background, left, bottom - 48, right, bottom)
            // The trip/menu readouts occupy the outer panels; keep the route and marker in the clear center.
            fade(left, top, left + 460, top, soft, clear, left, top, left + 460, bottom)
            fade(right - 460, top, right, top, clear, soft, right - 460, top, right, bottom)
        }
        paint.shader = null
    }
}
