package com.shilapi.xcertplay.airplay

/**
 * CarPlay's instrument-cluster screen (stream type 111).
 *
 * The iPhone lists the cluster content it offers in its /info request (`altScreenURLs`); without
 * an initial URL it streams a black frame. The cluster has no input. Apple Maps keeps the car
 * position inside the safe area while the map still fills the panel, so the safe area is set to
 * the part of the panel the cluster leaves uncovered.
 */
object CarPlayClusterDisplay {
    const val MAP_URL = "maps:/car/instrumentcluster/map"

    /**
     * The instruction-card stream content (BYD's captured `altScreenURLs` list it). The iPhone
     * renders only its glass turn card on this screen and streams it at 0-5 kbit/s, which is why
     * a second, small cluster display is cheap.
     */
    const val CARD_URL = "maps:/car/instrumentcluster/instructioncard"

    /**
     * What the dashboard shows: one of the cluster contents the iPhone lists in `altScreenURLs`.
     * On the tested car the iPhone glass turn card needed 0–5 kbit/s against 0.3–4 Mbit/s for the map.
     * MAP_WITH_CUSTOM_CARD asks for the map stream so DiPlay can draw a movable card on top.
     */
    enum class Content(val url: String) {
        /** The map stream, with our own movable cards drawn on top of it. */
        MAP_WITH_CUSTOM_CARD(MAP_URL),
        /**
         * The map stream with no card of ours.
         *
         * Not offered in the settings page any more - it became an internal stepping stone when
         * the dashboard was reduced to two choices (field 2026-10-24) - but kept so a stored value
         * from an older build still resolves instead of silently falling back.
         */
        MAP(MAP_URL),
        /**
         * The separate map + instruction-card layout as TWO streams (WWDC19 session 252:
         * "multiple streams can be set up to display different types of content from CarPlay
         * within the instrument cluster"): the map display requests the plain map URL (no iOS
         * glass card inside it), and a second, small cluster display requests [CARD_URL], which
         * DiPlay renders in a small window at the panel's upper-left.
         *
         * Uses the map URL so the map stream itself stays card-free; the turn card comes only
         * from the instruction-card stream.
         */
        FLY_CARD(MAP_URL),
        /**
         * Apple's own combined instrument-cluster view: the map WITH iOS's glass turn card and its
         * junction zoom painted into the same full-panel stream.
         *
         * The iPhone itself lists this URL in `altScreenURLs` (log: `[..., /map,
         * .../instructioncard, maps:/car/instrumentcluster]`). Unlike [FLY_CARD]'s separate small
         * card display, this is one cluster display the size of the panel, so iOS lays the card
         * out for a full cluster screen - the 27.2 "tiny card" comes from the 480x200 canvas of the
         * separate display, not from iOS's card itself. The card is independent of what the main
         * screen shows (it is cluster content, not a mirror), and the junction zoom stays inside
         * the same picture.
         */
        APPLE_MAP_WITH_CARD("maps:/car/instrumentcluster"),
    }


    fun usesCustomTurnCard(content: Content): Boolean = content == Content.MAP_WITH_CUSTOM_CARD

    /** The pure-Apple-cluster mode: full-screen instruments stream, nothing of ours on top. */
    fun isFlyCard(content: Content): Boolean = content == Content.FLY_CARD

    /** The driver can move the marker from that centre, in steps of 10 % of the panel. */
    const val MARKER_STEP_PERCENT = 10
    val horizontalSteps = -4..4 // negative = left
    val verticalSteps = -3..3 // negative = up

    /** The default area: whole panel, minus the strip the UNI-V moulding hides. */
    fun defaultSafeInsets(panelWidth: Int, panelHeight: Int): AirPlayInsets = AirPlayInsets(
        top = 0,
        bottom = PANEL_BOTTOM_OCCLUSION_PX.coerceIn(0, (panelHeight - 1).coerceAtLeast(0)),
        left = 0,
        right = 0,
    )

    /**
     * The car marker inside [insets], in percent of the panel, moved by the driver's steps.
     *
     * Steps are 10 % of the panel each and are clamped to [MARKER_MARGIN], so the marker can never
     * be pushed against an edge.
     */
    fun markerPercentIn(
        insets: AirPlayInsets,
        panelWidth: Int,
        panelHeight: Int,
        horizontalStep: Int,
        verticalStep: Int,
    ): Pair<Double, Double> {
        if (panelWidth <= 0 || panelHeight <= 0) return 50.0 to 50.0
        val centreX = (insets.left + (panelWidth - insets.right)) / 2.0 / panelWidth * 100
        val centreY = (insets.top + (panelHeight - insets.bottom)) / 2.0 / panelHeight * 100
        val x = centreX + horizontalStep.coerceIn(horizontalSteps) * MARKER_STEP_PERCENT
        val y = centreY + verticalStep.coerceIn(verticalSteps) * MARKER_STEP_PERCENT
        return x.coerceIn(MARKER_MARGIN, 100 - MARKER_MARGIN) to y.coerceIn(MARKER_MARGIN, 100 - MARKER_MARGIN)
    }

    private const val MARKER_MARGIN = 5.0

    /**
     * Stream size in percent of the panel. On BYD DiLink 5.0 the cluster scales the stream up,
     * so 83 % looked larger and still sharp. Changan Feiyu OS (UNI-V) does not scale the stream
     * the same way, and 83 % ended up enlarged past the panel edge with the picture cropped.
     * Full panel size keeps the whole picture visible there; the scale stays adjustable in
     * settings for head units that do scale up.
     */
    const val STREAM_SCALE_PERCENT = 100

    /**
     * Panel pixels hidden behind the dashboard's moulding on the UNI-V.
     *
     * The cluster reports 1920x720, but the bottom 110 px sit under the plastic (measured by the
     * driver 2026-10-25). Declaring the full height put the car marker at y 360 - in the hidden
     * strip - so the phone is told the VISIBLE height instead and the picture is drawn from the
     * panel's top edge, 1:1. Applies to the generic (Changan) path only; the BYD layout declares
     * its own fixed stream.
     */
    const val PANEL_BOTTOM_OCCLUSION_PX = 110

    /** Height actually visible on the panel, in panel pixels. */
    fun visiblePanelHeight(panelHeight: Int): Int =
        (panelHeight - PANEL_BOTTOM_OCCLUSION_PX).coerceAtLeast(1)

    /**
     * The instruction-card display: a small, separate type-111 screen (own UUID) whose stream
     * DiPlay shows as it arrives at the panel's upper-left, over the map stream. The card needs
     * no input and no features, exactly like the map cluster display.
     *
     * One knob, two effects (driver's rule, 2026-10-30): the percentage insets the safe area AND
     * grows the whole negotiated canvas by the same amount, so the card iOS draws comes out that
     * much larger while every pixel of it stays 1:1 - see [safeInsetPct].
     */
    fun cardConfig(
        widthPixels: Int = CARD_WIDTH_PIXELS,
        heightPixels: Int = CARD_HEIGHT_PIXELS,
        fps: Int = FPS,
        physicalWidthMm: Int = DEFAULT_CARD_WIDTH_PHYSICAL_MM,
        /**
         * Card scale in percent (0-50). **0 is the native declaration**: a 480x200 canvas with the
         * whole canvas safe, exactly as it always was.
         *
         * A positive value does two things at once, with the same number:
         * 1. insets the safe area by that percentage of the canvas's SHORT edge on all four sides.
         *    On the car (2026-10-30) this is what made iOS 27.2 draw the turn card at its old size
         *    again - at 30 % it fills the 360x80 rectangle instead of the tiny corner it used.
         * 2. grows the declared canvas by the same percentage: 30 % turns the 480x200 base into
         *    624x264 (rounded to a multiple of 8), so the safe rectangle becomes ~466x106 instead
         *    of 360x80. The card window follows the stream 1:1, so the bigger card is real pixels
         *    and nothing is stretched up - enlarging the picture was what blurred it before.
         *
         * Negative values are not supported: the phone refuses the whole session when the safe
         * area leaves the canvas (field 2026-10-30, -20/-35/-50 all failed to connect).
         */
        safeInsetPct: Int = 0,
    ): AirPlayDisplayConfig {
        val pct = safeInsetPct.coerceIn(0, 50)
        // Same percentage for the canvas: 0 keeps the 480x200 base, 30 makes it 624x264.
        val grow = (100 + pct) / 100.0
        val width = roundTo8(widthPixels * grow)
        val height = roundTo8(heightPixels * grow)
        val physicalMm = physicalWidthMm.coerceIn(1, 2000)
        val shortEdge = minOf(width, height)
        // Never let the inset collapse the safe area - the phone aborts on an empty rectangle.
        val inset = (shortEdge * pct / 100).coerceAtMost((shortEdge - 16) / 2)
        val safe = if (pct == 0) null else AirPlayInsets(
            top = inset,
            bottom = inset,
            left = inset,
            right = inset,
        )
        return AirPlayDisplayConfig(
            widthPixels = width,
            heightPixels = height,
            // Driver-typed physical width (settings, default 96 mm), NOT scaled with the canvas:
            // it stays the base declaration, so what grows is the pixel count, not the panel.
            widthPhysicalMm = physicalMm,
            heightPhysicalMm = Math.round(physicalMm * height.toDouble() / width).toInt(),
            fps = fps,
            primaryInputDevice = 0,
            features = 0,
            initialUrl = CARD_URL,
            safeArea = safe,
        )
    }

    /**
     * Apple's instruction-card display, negotiated at the card's native 480x200.
     *
     * iOS 27.2 draws the turn-card type tiny inside this canvas. Enlarging the canvas ALONE
     * (960x480, then 960x400 to keep the 2.4:1 aspect) did widen the window but never fixed the
     * type: the phone keeps drawing the card at its own small layout, so the result was a large
     * window full of whitespace (driver, tested repeatedly, 2026-10-29).
     *
     * What DID move it was the safe area (field, 2026-10-30): with 30 % insets the phone fills that
     * inset rectangle with the card and it comes out the size it used to have before 27.2. The
     * percentage therefore also grows the canvas by the same amount, which makes that rectangle
     * bigger in real pixels instead of being stretched up - see cardConfig(safeInsetPct).
     *
     * The custom-card mode (MAP_WITH_CUSTOM_CARD) draws its own card independently of this AirPlay
     * display.
     */
    const val CARD_WIDTH_PIXELS = 480
    const val CARD_HEIGHT_PIXELS = 200

    /**
     * Default physical width reported for the instruction-card display: 96 mm at 480x200 is about
     * 127 ppi. The settings page lets the driver type another value; heightPhysical is derived.
     */
    const val DEFAULT_CARD_WIDTH_PHYSICAL_MM = 96

    private const val WIDTH_PHYSICAL_MM = 292 // a 12.3-inch 8:3 cluster panel; Apple Maps ignores it here
    private const val FPS = 30

    fun config(
        widthPixels: Int,
        heightPixels: Int,
        scalePercent: Int = STREAM_SCALE_PERCENT,
        horizontalStep: Int = 0,
        verticalStep: Int = 0,
        content: Content = Content.MAP,
        safeInsets: AirPlayInsets? = null,
    ): AirPlayDisplayConfig {
        // Height rounds to a multiple of 8 and width follows it, so the panel's aspect is kept
        // (100 % of 1920x720 gives exactly 1920x720). The cluster scales the stream to the panel.
        val scale = scalePercent.coerceIn(25, 100)
        val height = roundTo8(heightPixels * scale / 100.0)
        val width = roundTo8(height * widthPixels.toDouble() / heightPixels)
        // ONE safe area for every content mode.
        //
        // The driver sets its four edges in the settings page (px, default bottom 110 = the strip
        // the UNI-V moulding hides). The values go into the /info the phone reads when it
        // connects, so a change lands on the next connection.
        val safe = safeArea(
            width,
            height,
            horizontalStep,
            verticalStep,
            safeInsets ?: defaultSafeInsets(width, height),
        )
        return AirPlayDisplayConfig(
            widthPixels = width,
            heightPixels = height,
            widthPhysicalMm = WIDTH_PHYSICAL_MM,
            heightPhysicalMm = Math.round(WIDTH_PHYSICAL_MM * height.toDouble() / width).toInt(),
            fps = FPS,
            primaryInputDevice = 0,
            features = 0,
            initialUrl = content.url,
            safeArea = safe,
            safeAreaDrawOutside = true,
        )
    }

    // The safe area keeps its measured size around the marker and shrinks only where the marker
    // comes close to a panel edge, so the marker always sits at its centre.
    private fun safeArea(
        width: Int,
        height: Int,
        horizontalStep: Int,
        verticalStep: Int,
        requested: AirPlayInsets,
    ): AirPlayInsets {
        // Legalise the driver's numbers first: the edges on one axis must not overlap, so an
        // over-large pair is scaled down instead of collapsing the area to nothing.
        val (left, right) = fitInsets(requested.left, requested.right, width)
        val (top, bottom) = fitInsets(requested.top, requested.bottom, height)
        val area = AirPlayInsets(top = top, bottom = bottom, left = left, right = right)
        val (x, y) = markerPercentIn(area, width, height, horizontalStep, verticalStep)
        val halfWidth = minOf(x - left * 100.0 / width, (width - right) * 100.0 / width - x)
        val halfHeight = minOf(y - top * 100.0 / height, (height - bottom) * 100.0 / height - y)
        return AirPlayInsets(
            // Round, not truncate: 720 - 110 must come out as exactly 610, and truncation of
            // 109.99999 handed back 611 instead (seen in the unit test on 2026-10-25).
            top = Math.round(height * (y - halfHeight) / 100).toInt().coerceAtLeast(top),
            bottom = Math.round(height * (100 - y - halfHeight) / 100).toInt().coerceAtLeast(bottom),
            left = Math.round(width * (x - halfWidth) / 100).toInt().coerceAtLeast(left),
            right = Math.round(width * (100 - x - halfWidth) / 100).toInt().coerceAtLeast(right),
        )
    }

    /** Two opposite edges that together exceed [total] are scaled to fit, keeping their ratio. */
    private fun fitInsets(first: Int, second: Int, total: Int): Pair<Int, Int> {
        if (total <= 0) return 0 to 0
        val a = first.coerceAtLeast(0)
        val b = second.coerceAtLeast(0)
        if (a + b < total) return a to b
        val scale = (total - 1).toDouble() / (a + b)
        return (a * scale).toInt() to (b * scale).toInt()
    }

    private fun roundTo8(value: Double): Int = (Math.round(value / 8) * 8).toInt().coerceAtLeast(8)
}
