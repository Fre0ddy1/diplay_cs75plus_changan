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
         * The 风清速连 layout as TWO streams (WWDC19 session 252: "multiple streams can be set up
         * to display different types of content from CarPlay within the instrument cluster"):
         * the map display requests the plain map URL (no iOS glass card inside it), and a second,
         * small cluster display requests [CARD_URL], which DiPlay renders in a small window at
         * the panel's upper-left. The map and the car marker keep the centred safe area, so the
         * card window and the map content sit independently - unlike the single-stream attempts,
         * where moving the card also moved the whole map.
         *
         * Uses the map URL so the map stream itself stays card-free; the turn card comes only
         * from the instruction-card stream.
         */
        FLY_CARD(MAP_URL),
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
     * no input and no features, exactly like the map cluster display. The safe area stays at the
     * full canvas - iOS lays the card out for the whole screen it is given.
     */
    fun cardConfig(
        widthPixels: Int = CARD_WIDTH_PIXELS,
        heightPixels: Int = CARD_HEIGHT_PIXELS,
        fps: Int = FPS,
    ): AirPlayDisplayConfig {
        val width = roundTo8(widthPixels.toDouble())
        val height = roundTo8(heightPixels.toDouble())
        return AirPlayDisplayConfig(
            widthPixels = width,
            heightPixels = height,
            // A reported physical size keeps the aspect maths in displayEntry consistent; Apple
            // ignores it on the cluster (see the map display).
            widthPhysicalMm = CARD_WIDTH_PHYSICAL_MM,
            heightPhysicalMm = Math.round(CARD_WIDTH_PHYSICAL_MM * height.toDouble() / width).toInt(),
            fps = fps,
            primaryInputDevice = 0,
            features = 0,
            initialUrl = CARD_URL,
        )
    }

    /**
     * Panel size of the instruction-card stream, decoded 1:1 into the window at the dashboard's
     * upper-left.
     *
     * The canvas is card-SHAPED on purpose (2.4:1, not the near-square 480x400 it was at first):
     * iOS lays the card out for the whole screen it is given, so a square canvas left a large
     * empty area under the card, which showed up as a black slab on the dashboard. With a canvas
     * that matches the card's own proportions the picture the phone sends IS the card.
     */
    const val CARD_WIDTH_PIXELS = 480
    const val CARD_HEIGHT_PIXELS = 200

    private const val CARD_WIDTH_PHYSICAL_MM = 96 // a small card panel; Apple Maps ignores it here

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
