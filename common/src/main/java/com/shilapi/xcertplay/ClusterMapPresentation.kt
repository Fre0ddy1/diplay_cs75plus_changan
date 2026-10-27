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
import android.graphics.Outline
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
import android.view.ViewOutlineProvider
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
    /**
     * The instruction-card stream surface (FLY_CARD only). Bound to SCREEN_TYPE_ALT_CARD by the
     * host; null on every other mode, where the card view is never created.
     */
    private val onCardSurface: (Surface?) -> Unit = {},
    private val navOnlyCards: Boolean = false,
    /**
     * The "pure Apple cluster" branch (2026-10-24): the 111 stream is requested with the
     * instruments URL (maps:/car/instrumentcluster), shown FULL SCREEN like the nav-only mode,
     * and nothing of ours is drawn on top - no guidance cards, no song card. The mode is also
     * pinned to navigation: mirror requests (three-finger swipe, auto switch) are ignored.
     * Selected via the "cluster picture source" setting (Content.FLY_CARD).
     */
    private val flyCard: Boolean = false,
) : Presentation(context, display) {
    private var waitingLabel: TextView? = null
    /**
     * The turn/instruction card. Nav-only mode only (A1): the whole-screen mirror must stay the
     * raw mirror picture, so this view does not exist while the dashboard shows it.
     */
    private var turnCardView: ClusterTurnCardView? = null
    /**
     * The arrival (ETA) card. Shown in BOTH modes - nav-only and whole-screen mirror (A2): the
     * driver wants the arrival estimate next to CarPlay's own picture as well.
     */
    private var etaCardView: ClusterEtaCardView? = null
    /** Song now playing, top centre. Title/artist only - the protocol carries no lyrics. */
    private var songView: ClusterSongView? = null
    private var genericTextureView: TextureView? = null
    private var navTextureView: TextureView? = null
    private var cardTextureView: TextureView? = null
    /** Root the card window is attached to while it exists (FLY_CARD only). */
    private var rootView: FrameLayout? = null
    /** The instruction-card stream is up right now (the phone is sending frames). */
    private var cardStreamActive = false
    /** Current decoded picture size of the instruction-card stream, in its own pixels. */
    private var cardVideoWidth = CarPlayClusterDisplay.CARD_WIDTH_PIXELS
    private var cardVideoHeight = CarPlayClusterDisplay.CARD_HEIGHT_PIXELS
    /** Panel coordinates the card window was last laid out at, i.e. AFTER the clamp (FLY_CARD). */
    private var cardWindowLeft = CARD_LEFT_PX
    private var cardWindowTop = CARD_TOP_PX
    /** The 0x0-panel warning is emitted once per window, not on every per-second refresh. */
    private var cardWindowSizeWarned = false
    private val projectionSize: Point = sizeOf(display)
    var mirrorOutputSurface: Surface? = null
        private set
    var navOutputSurface: Surface? = null
        private set
    var cardOutputSurface: Surface? = null
        private set

    // Nav-only card group state. When navOnlyCards is true (Chang'an generic path) the group
    // belongs to the navigation stream: the turn card exists ONLY in nav-only mode, while the
    // ETA card and the song card are shown in whole-screen mirror mode too (driver requirement
    // 2026-10-24). DiLink 5.1 keeps the upstream behavior (turn card follows the "dashboard
    // shows" content setting, no ETA card).
    private var mirrorMode = false
    /** Last mode requested by the host, kept even while the TextureViews are still being created. */
    private var desiredMirror = AirPlayPersistence.loadClusterMirrorMain(context)
    private var lastTurnGuidance: ClusterTurnGuidance? = null
    private var lastEtaGuidance: ClusterTurnGuidance? = null

    // Per-frame cost killers: card show/hide switches and the fronted-card set are cached.
    // applyCardsVisibility runs on every guidance tick (per second); reading SharedPreferences
    // or calling bringToFront() there would requestLayout/re-read the disk every frame.
    // Load them at construction: on a cold start the first guidance tick can arrive before any
    // settings change, and the old defaults (true) showed cards the driver had switched off
    // until a reconnect re-read them.
    //
    // Only the ETA card is left here: the turn card has its own switch below, the song card is
    // unconditional, and the road-name card was removed from the product (A3).
    private var cardVisibleCache: Map<String, Boolean> = mapOf(
        "eta" to AirPlayPersistence.loadClusterCardVisible(context, "eta"),
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
            setBackgroundColor(
                when {
                    plan == null -> Color.BLACK
                    plan.fullMap -> backdrop
                    else -> Color.TRANSPARENT
                },
            )
        }
        rootView = root
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
            // The pure-Apple branch (flyCard) uses the SAME full-screen view - it differs only in
            // the requested URL and in never drawing anything of ours on top (see setMode).
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
            // The instruction-card window (FLY_CARD) is NOT built here.
            //
            // It exists only while the phone is actually sending that stream AND there is
            // guidance to show: createCardWindow() builds a TextureView sized to the stream's own
            // picture and drops it at the saved position (default: the panel's upper-left
            // corner), destroyCardWindow() removes it again. Nothing of ours is ever drawn around
            // it - no background, no mask, no stroke - so the dashboard shows exactly the frames
            // the phone sends, and nothing at all when it sends none. See setCardStreamActive().
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
        // They are created by applyCardsVisibility() from the saved switches, BEFORE the stream is
        // shown: the turn card only in nav-only mode, the ETA card in both modes (A1/A2). Creating
        // them here would let a card flash over CarPlay's own picture before it arrives.
        cardsRoot = root
        // TEMPORARY EXCLUSION TEST (driver's request, 2026-10-24).
        //
        // Every self-drawn card is switched off so a screenshot can only show what the phone
        // itself sends. If the glass turn box in the middle of the dashboard survives this build,
        // it is Apple's own instrument-cluster view; if it disappears, it was one of ours.
        // Set to false to restore the cards.
        if (!CARDS_DISABLED) {
            // Added last so it draws above the guidance cards.
            //
            // The song card - like the ETA card - is shown in EVERY mode (A4): it is created once
            // here and lives until the window dies. Nothing in the mode or guidance paths ever
            // hides or destroys it, and ClusterSongView decides its own visibility purely from
            // whether a song is playing.
            songView = ClusterSongView(context).apply { visibility = View.GONE }
            root.addView(songView, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
    }

    /**
     * Creates the guidance cards the driver's switches ask for.
     *
     * Two different rules live here (driver's requirements, 2026-10-24):
     * - the TURN card belongs to the navigation stream only: it is never created while the
     *   dashboard shows the whole-screen mirror (A1);
     * - the ETA card is shown in both modes, mirror included (A2).
     * Everything here is idempotent: an existing card is left alone, a missing one is created
     * with the guidance data already known, and the music card is never touched because it
     * belongs to every mode.
     */
    private fun materializeGuidanceCards() {
        if (CARDS_DISABLED) return
        val root = cardsRoot ?: return
        // Turn card: nav-only stream only. iOS's own instruction box is the whole picture of the
        // mirror stream, and a second card on top of it was exactly the "glass box" confusion.
        // FLY_CARD never draws this card: Apple's instruction-card WINDOW carries the turns there.
        if (!flyCard && !mirrorMode && turnCardView == null && turnCardVisibleCache) {
            turnCardView = ClusterTurnCardView(context).apply { visibility = View.GONE }
            root.addView(turnCardView, FrameLayout.LayoutParams(-1, -1))
            // A rebuilt card starts at its own defaults, so restore the driver's saved geometry
            // before it can draw.
            turnCardView?.setLayout(
                AirPlayPersistence.loadClusterTurnCardX(context),
                AirPlayPersistence.loadClusterTurnCardY(context),
                AirPlayPersistence.loadClusterCardScalePercent(context, "turn"),
            )
            turnCardView?.setBackgroundAlpha(AirPlayPersistence.loadClusterCardAlpha(context, "turn"))
            turnCardView?.setGuidance(lastTurnGuidance)
        }
        // ETA card: both modes (A2). The host feeds setEtaGuidance() in either mode.
        if (etaCardView == null && (cardVisibleCache["eta"] ?: true)) {
            etaCardView = ClusterEtaCardView(context).apply { visibility = View.GONE }
            root.addView(etaCardView, FrameLayout.LayoutParams(-1, -1))
            etaCardView?.setBackgroundAlpha(AirPlayPersistence.loadClusterCardAlpha(context, "eta"))
            etaCardView?.setGuidance(lastEtaGuidance)
        }
        // The cards are inserted after the video surfaces, so they must be raised again.
        listOfNotNull(turnCardView, etaCardView).forEach { it.bringToFront() }
        // The music card was added after the guidance cards in onCreate; keep it on top.
        songView?.bringToFront()
        Log.i(
            TAG,
            "guidance cards created turn=${turnCardView != null} eta=${etaCardView != null} " +
                "(mode=${if (mirrorMode) "mirror" else "nav-only"})",
        )
    }

    /**
     * Removes the turn card from the window.
     *
     * Destroyed rather than hidden: a hidden view can still be brought back by a stray
     * setGuidance(), and the driver's complaint was exactly that a card stayed on screen after
     * the dashboard left nav-only mode. ONLY the turn card goes away with the navigation stream:
     * the ETA card and the music card are shown in both modes, so they are deliberately left
     * untouched here (A2/A4).
     */
    private fun destroyTurnCard() {
        val root = cardsRoot ?: return
        val view = turnCardView ?: return
        root.removeView(view)
        turnCardView = null
        // Force the next visibility pass to re-front the remaining cards.
        frontedCards = emptyList()
        Log.i(TAG, "turn card destroyed")
    }

    /**
     * Selects which resident stream is displayed on the dashboard: whole-screen mirror (110) or
     * navigation stream only (111). Both decoder surfaces stay bound to the sink - only the view
     * visibility changes, so the incall service never sees a 111 (re-)activation and does not
     * announce "全屏导航已开启". On the DiLink 5.1 layout (single TextureView) the mode is a no-op.
     */
    fun setMode(mirror: Boolean) {
        // Every content mode switches the same two resident surfaces. FLY_CARD is no exception
        // any more (field 2026-10-25: the driver could not reach the whole-screen mirror at all,
        // by three-finger swipe or by reconnecting, because this used to pin mirrorMode to false).
        // Its instruction-card window is simply hidden while the mirror is up.
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
            // One pass decides everything: the turn card follows the mode, the ETA card is
            // created in both modes (applyCardsVisibility -> materializeGuidanceCards).
            applyCardsVisibility()
        }
        applyCardWindowVisibility()
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

    /**
     * 诊断：把导航流（111）当前这一帧存成 PNG。
     *
     * 日志只有帧率和码率，给不出画面内容——而"仪表上那行提示到底是不是 111 画的"只能看画面
     * 才知道，前面的码率推断已经错过三次。缩到一半再存，一张约 200-400 KB，失败返回 null。
     */
    fun captureNavFrame(directory: java.io.File): java.io.File? {
        val view = navTextureView ?: return null
        if (!view.isAvailable) return null
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null
        val bitmap = runCatching { view.getBitmap(width / 2, height / 2) }.getOrNull() ?: return null
        val file = java.io.File(directory, "nav-frame-${System.currentTimeMillis()}.png")
        val saved = runCatching {
            java.io.FileOutputStream(file).use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            file
        }.getOrNull()
        bitmap.recycle()
        return saved?.takeIf { it.length() > 0 }
    }

    /**
     * The instruction-card stream came up or went away (FLY_CARD only).
     *
     * The card window is not a fixed part of the dashboard: it exists while the phone is really
     * sending that stream and there is guidance to show, and is destroyed otherwise. That is what
     * keeps an idle dashboard free of a black slab - no stream (or no guidance), no window.
     */
    fun setCardStreamActive(active: Boolean) {
        if (!flyCard) return
        if (cardStreamActive == active) return
        cardStreamActive = active
        updateCardWindow()
    }

    /** Creates or destroys the card window from the stream's state and the guidance data. */
    private fun updateCardWindow() {
        if (!flyCard) return
        val wanted = cardStreamActive && lastTurnGuidance != null
        if (wanted == (cardTextureView != null)) return
        if (wanted) createCardWindow() else destroyCardWindow()
    }

    /**
     * Builds the window from the stream's own picture: no background, no rounded mask, no stroke,
     * at the driver's saved position (default: the panel's upper-left corner, see
     * cardWindowParams()). Frames arrive straight from the decoder surface, so one stream pixel is
     * one panel pixel.
     */
    private fun createCardWindow() {
        val root = rootView ?: return
        if (cardTextureView != null) return
        val cardView = TextureView(context)
        cardView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "cluster card surface created")
                texture.setDefaultBufferSize(cardVideoWidth, cardVideoHeight)
                cardOutputSurface = Surface(texture).also(onCardSurface)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                Log.i(TAG, "cluster card surface destroyed")
                cardOutputSurface?.release()
                cardOutputSurface = null
                onCardSurface(null)
                return true
            }
        }
        cardTextureView = cardView
        // Rounded corners (field request 2026-10-24): the stream itself arrives as a square
        // rectangle and the driver found that ugly, so the window clips it. Still nothing of ours
        // is drawn inside the picture - this only shapes its outer edge.
        cardView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val width = if (view.width > 0) view.width else cardVideoWidth
                val height = if (view.height > 0) view.height else cardVideoHeight
                outline.setRoundRect(0, 0, width, height, CARD_CORNER_RADIUS_PX)
            }
        }
        cardView.clipToOutline = true
        root.addView(cardView, cardWindowParams())
        applyCardWindowVisibility()
        Log.i(TAG, "cluster card window created ${cardVideoWidth}x$cardVideoHeight at $cardWindowLeft,$cardWindowTop")
    }

    /**
     * The instruction-card window belongs to the navigation picture only.
     *
     * While the whole-screen mirror is up it is HIDDEN, not destroyed: keeping the view and its
     * surface avoids re-negotiating the card stream and rebuilding its decoder when the driver
     * swipes back (field 2026-10-25: the mirror must be reachable from this mode at all).
     */
    private fun applyCardWindowVisibility() {
        if (!flyCard) return
        cardTextureView?.visibility = if (mirrorMode) View.GONE else View.VISIBLE
    }

    /** Drops the window and its surface; the host releases the decoder with it. */
    private fun destroyCardWindow() {
        val view = cardTextureView ?: return
        cardTextureView = null
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        Log.i(TAG, "cluster card window destroyed")
    }

    /**
     * The instruction-card stream reported its real decoded picture size.
     *
     * The window follows it, 1:1, with no scaling: the card is shown exactly as the phone sends
     * it. Fires once per codec from the video output format's crop rectangle
     * (AndroidMediaSink.onScreenStreamOutputSize).
     */
    fun setCardVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == cardVideoWidth && height == cardVideoHeight) return
        cardVideoWidth = width
        cardVideoHeight = height
        val view = cardTextureView ?: return
        view.surfaceTexture?.setDefaultBufferSize(width, height)
        // A different picture size moves the clamp ceiling as well, so the position is resolved
        // again from the saved (x, y) instead of reusing the previous layout.
        view.layoutParams = cardWindowParams()
        Log.i(
            TAG,
            "cluster card picture ${width}x$height window=${cardVideoWidth}x$cardVideoHeight " +
                "at $cardWindowLeft,$cardWindowTop",
        )
    }

    /** Live card window frame on the panel: left, top, width, height. Null when not created. */
    fun cardWindowRect(): IntArray? {
        val view = cardTextureView ?: return null
        return intArrayOf(view.left, view.top, view.width, view.height)
    }

    /** Panel size this presentation was built for, for diagnostics. */
    fun panelSize(): IntArray = intArrayOf(projectionSize.x, projectionSize.y)

    /**
     * Re-applies the saved card-window position to the live window (settings change, B4).
     *
     * The window only exists while the phone streams the card AND there is guidance, so a
     * position change usually arrives with no view at all: the saved coordinates then simply take
     * effect the next time createCardWindow() runs (B7). The clamp runs either way, so an
     * impossible coordinate is corrected in the saved value as soon as it is read.
     */
    fun refreshCardWindowPosition() {
        if (!flyCard) return
        // Built BEFORE the null check on purpose: cardWindowParams() also clamps and silently
        // corrects an out-of-bounds saved value, and that has to keep working while no window
        // exists (a coordinate saved before the first stream must not stay impossible).
        val params = cardWindowParams()
        val view = cardTextureView ?: return
        val applied = view.layoutParams as? FrameLayout.LayoutParams
        // The host reaches this from applyClusterTurnOverlay(), which the guidance/ETA tick drives
        // once per second. Assigning layoutParams requests a layout pass of the whole window, and
        // this codebase treats per-second relayout as a bug (see the fronted-card cache in
        // applyCardsVisibility and ClusterEtaCardView's first-show-only bringToFront), so an
        // unchanged position costs nothing here.
        //
        // The comparison is against what the VIEW carries (leftMargin/topMargin/width/height),
        // not against a second copy of those values in fields: one source of truth cannot drift
        // out of sync with the view after a window is created or destroyed. A window that
        // createCardWindow() just added already carries the right params and is skipped too; the
        // first real move is what requests the layout.
        if (applied != null &&
            applied.width == params.width &&
            applied.height == params.height &&
            applied.leftMargin == params.leftMargin &&
            applied.topMargin == params.topMargin
        ) {
            return
        }
        view.layoutParams = params
        Log.i(
            TAG,
            "cluster card window moved to $cardWindowLeft,$cardWindowTop ${params.width}x${params.height}",
        )
    }

    /**
     * The card window's layout: exactly the stream's own picture size, at the driver's saved
     * position.
     *
     * The position is clamped so the window can never leave the dashboard. The driver types plain
     * pixel coordinates relative to the panel's upper-left corner (0..1920 / 0..720) and can
     * easily push the card past an edge, which would leave half of it outside the cluster with no
     * way back. A corrected value is persisted SILENTLY: notifying the settings listener from
     * inside a layout pass would cascade into another apply/refresh round (see
     * AirPlayPersistence.saveClusterCardXSilent).
     */
    private fun cardWindowParams(): FrameLayout.LayoutParams {
        // A panel that has not reported its size yet (getRealSize can hand back 0 while the
        // display is being brought up) is NOT a 0x0 dashboard: clamping against it would collapse
        // every coordinate to 0 and the silent write-back below would permanently overwrite the
        // driver's saved position. Fall back to the panel origin and write NOTHING.
        if (projectionSize.x <= 0 || projectionSize.y <= 0) {
            // Once per window, not once per tick: this path is reached from the per-second
            // overlay refresh as well, and a repeated warning would bury the log.
            if (!cardWindowSizeWarned) {
                cardWindowSizeWarned = true
                Log.w(
                    TAG,
                    "cluster card window: panel size ${projectionSize.x}x${projectionSize.y}, " +
                        "keeping saved position",
                )
            }
            cardWindowLeft = CARD_LEFT_PX
            cardWindowTop = CARD_TOP_PX
            return FrameLayout.LayoutParams(cardVideoWidth, cardVideoHeight, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = CARD_LEFT_PX
                topMargin = CARD_TOP_PX
            }
        }
        val maxLeft = (projectionSize.x - cardVideoWidth).coerceAtLeast(0)
        val maxTop = (projectionSize.y - cardVideoHeight).coerceAtLeast(0)
        val savedLeft = AirPlayPersistence.loadClusterCardX(context, CARD_WINDOW_CARD_ID)
        val savedTop = AirPlayPersistence.loadClusterCardY(context, CARD_WINDOW_CARD_ID)
        val left = savedLeft.coerceIn(0, maxLeft)
        val top = savedTop.coerceIn(0, maxTop)
        if (left != savedLeft) AirPlayPersistence.saveClusterCardXSilent(context, CARD_WINDOW_CARD_ID, left)
        if (top != savedTop) AirPlayPersistence.saveClusterCardYSilent(context, CARD_WINDOW_CARD_ID, top)
        if (left != cardWindowLeft || top != cardWindowTop) {
            Log.i(
                TAG,
                "cluster card window placed x=$savedLeft->$left y=$savedTop->$top " +
                    "limit=$maxLeft,$maxTop on ${projectionSize.x}x${projectionSize.y}",
            )
        }
        cardWindowLeft = left
        cardWindowTop = top
        return FrameLayout.LayoutParams(cardVideoWidth, cardVideoHeight, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = left
            topMargin = top
        }
    }

    /** Current display mode (true = whole-screen mirror, false = nav-only), for diagnostics. */
    fun isMirrorMode(): Boolean = mirrorMode

    /**
     * Which guidance cards exist right now, for the dashboard diagnostic log.
     *
     * "exists" matters more than "has data": a card that was never created, or one destroyed when
     * the mode changed, cannot be the glass box the driver sees - and telling those two apart is
     * what decides whether a complaint is about our drawing or about Apple's own view.
     */
    fun cardsSummary(): String =
        "turnCard=${turnCardView != null} etaCard=${etaCardView != null} " +
            "songCard=${songView != null} cardWindow=${cardTextureView != null}"

    /**
     * Window alpha hides the pixels without destroying the TextureView/decoder surface.
     *
     * Do NOT also set the decor view to GONE. Tried on 2026-10-25: after showing the window again
     * the TextureView was re-laid out at the wrong size, the instruction-card picture filled the
     * whole dashboard and the main screen showed our dashboard text. Alpha is the field-proven
     * hand-back and the occasional "car UI only in a corner" case is on the head unit's side.
     */
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
     * its layout, the absolute-position cards just invalidate and re-read the new
     * (x, y) from persistence in onDraw. Called from the overlay-settings listener so a
     * saved position change is visible on the dashboard immediately.
     */
    fun refreshCardOverlays() {
        turnCardView?.setLayout(
            AirPlayPersistence.loadClusterTurnCardX(context),
            AirPlayPersistence.loadClusterTurnCardY(context),
            AirPlayPersistence.loadClusterCardScalePercent(context, "turn"),
        )
        turnCardView?.setBackgroundAlpha(AirPlayPersistence.loadClusterCardAlpha(context, "turn"))
        // Drop each card's cached position so the next draw reloads the saved coordinates.
        etaCardView?.notifyPositionChanged()
        etaCardView?.setBackgroundAlpha(AirPlayPersistence.loadClusterCardAlpha(context, "eta"))
        // Deliberately outside the nav-only group: the mirror mode shows these as well.
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
        // The card window lives only while there is guidance to draw (FLY_CARD).
        updateCardWindow()
    }

    fun setEtaGuidance(guidance: ClusterTurnGuidance?) {
        lastEtaGuidance = guidance
        etaCardView?.setGuidance(guidance)
        if (navOnlyCards) applyCardsVisibility()
    }

    /**
     * Nav-only card group visibility, per mode.
     *
     * Whole-screen mirror: the TURN card must not exist (A1) - the mirror picture is Apple's own,
     * with its own instruction box - but the ETA card stays (A2). This used to destroy the whole
     * guidance group, which is why the arrival card vanished the moment the driver mirrored the
     * phone.
     *
     * Nav-only: the driver's switches alone decide which cards exist. DiLink 5.1
     * (navOnlyCards=false) never reaches this branch.
     *
     * Each view's setGuidance() is the authority on its own visibility (turn needs guidance,
     * ETA needs arrival/remaining), so here we only create/destroy by mode and switch, and raise
     * the visible cards above the video layer. Never force a card VISIBLE here without the same
     * data check - that would show an empty glass block.
     */
    private fun applyCardsVisibility() {
        if (!navOnlyCards) return
        // FLY_CARD is no longer exempt: it shows the ETA and song cards too (2026-10-25). Its
        // turn card is simply never created (see materializeGuidanceCards), so the turn branch
        // below is a no-op there.
        // Not the navigation stream: only the turn card goes away, everything else stays.
        if (mirrorMode || !turnCardVisibleCache) destroyTurnCard()
        if (cardVisibleCache["eta"] == false) {
            etaCardView?.let {
                cardsRoot?.removeView(it)
                frontedCards = emptyList()
            }
            etaCardView = null
        }
        materializeGuidanceCards()
        // bringToFront() triggers a layout pass, so only re-front when the visible set
        // actually changed — guidance ticks every second and must not requestLayout each time.
        val visibleNow = listOfNotNull(turnCardView, etaCardView)
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
        )
        turnCardVisibleCache = AirPlayPersistence.loadClusterTurnCardVisible(context)
    }

    companion object {
        const val TAG = "DiPlay-Cluster"

        // —— Instruction-card window (FLY_CARD), in panel pixels ——
        // Sized to the stream's own picture, so the card arrives and is shown 1:1. No background,
        // mask or stroke of ours: an earlier build drew a translucent panel of a guessed size and
        // stretched the picture into it, which is what looked like a large black slab. The card
        // display is advertised with a card-shaped canvas (CarPlayClusterDisplay.CARD_*), so the
        // picture itself is compact.
        //
        // The POSITION is the driver's to set (settings: "Apple 完整导航卡位置", B1-B5); these two
        // constants are only the panel-origin default, kept as the initial value of the live
        // coordinates before the first layout resolves them from persistence.
        const val CARD_LEFT_PX = 0
        const val CARD_TOP_PX = 0
        /** Rounded corners of the card window, in panel pixels (≈24 % of the card height). */
        const val CARD_CORNER_RADIUS_PX = 48f
        /**
         * The card window's key in the shared per-card position store (AirPlayPersistence).
         *
         * "apple" = Apple's OWN full-navigation card, which the phone paints into the stream, as
         * opposed to the cards we draw ourselves ("turn", "eta", "song"). It only has a position -
         * no size (the stream drives it) and no show switch (there is nothing to disable when the
         * phone is sending that stream).
         */
        const val CARD_WINDOW_CARD_ID = "apple"

        /**
         * Builds a dashboard with no self-drawn card at all.
         *
         * Used once, on 2026-10-24, to settle where a dashboard element comes from: with this true
         * the log shows cards=turnCard=false etaCard=false songCard=false, and the glass turn box
         * in the middle was still on screen - so it is Apple's, from its own instrument-cluster
         * view. Kept as a switch because the same question will come up again; false is the
         * normal build.
         */
        const val CARDS_DISABLED = false

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
