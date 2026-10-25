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

    /**
     * Where the car marker goes, as percent of the panel (left, top, right, bottom).
     *
     * Full panel since 2026-10-25 (Changan UNI-V field request). The old centre window
     * (35/16/36/25) was measured on a BYD DiLink 5.0 cluster whose own UI covers the edges; the
     * UNI-V panel has no such overlay, so the phone should be given the whole display. The map
     * was already painted edge to edge (safeAreaDrawOutside); this only frees the car marker and
     * any Apple view from the BYD-shaped box.
     */
    val SAFE_AREA_PERCENT = AirPlayInsets(top = 0, bottom = 0, left = 0, right = 0)

    /** The driver can move the marker from that centre, in steps of 10 % of the panel. */
    const val MARKER_STEP_PERCENT = 10
    val horizontalSteps = -4..4 // negative = left
    val verticalSteps = -3..3 // negative = up

    /** Where the car marker lands, in percent of the panel (x from the left, y from the top). */
    fun markerPercent(horizontalStep: Int, verticalStep: Int): Pair<Double, Double> {
        val area = SAFE_AREA_PERCENT
        val x = (area.left + 100 - area.right) / 2.0 + horizontalStep.coerceIn(horizontalSteps) * MARKER_STEP_PERCENT
        val y = (area.top + 100 - area.bottom) / 2.0 + verticalStep.coerceIn(verticalSteps) * MARKER_STEP_PERCENT
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
    ): AirPlayDisplayConfig {
        // Height rounds to a multiple of 8 and width follows it, so the panel's aspect is kept
        // (100 % of 1920x720 gives exactly 1920x720). The cluster scales the stream to the panel.
        val scale = scalePercent.coerceIn(25, 100)
        val height = roundTo8(heightPixels * scale / 100.0)
        val width = roundTo8(height * widthPixels.toDouble() / heightPixels)
        // ONE safe area for every content mode.
        //
        // The centred area is the only mode left (field decision 2026-10-18): with a zero inset
        // Amap renders its detailed follow map and puts the car marker left of centre, leaving the
        // panel's right column to its own info strip. The centred area makes Amap show the overview
        // with the marker dead centre; the marker steps still move it from there.
        //
        // Apple's own cluster views get the same area. It used to be assumed that they lay the
        // content out themselves and need the whole panel, but the field comparison on 2026-10-24
        // settled it the other way: the self-drawn map shows the car model fully with this area,
        // while Apple's instrument cluster showed it lower and cut by the panel edge. The two modes
        // differ only in the requested URL, so they must not differ in geometry either.
        val safe = safeArea(width, height, horizontalStep, verticalStep)
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
    private fun safeArea(width: Int, height: Int, horizontalStep: Int, verticalStep: Int): AirPlayInsets {
        val area = SAFE_AREA_PERCENT
        val (x, y) = markerPercent(horizontalStep, verticalStep)
        val halfWidth = minOf((100 - area.left - area.right) / 2.0, x, 100 - x)
        val halfHeight = minOf((100 - area.top - area.bottom) / 2.0, y, 100 - y)
        return AirPlayInsets(
            top = (height * (y - halfHeight) / 100).toInt(),
            bottom = (height * (100 - y - halfHeight) / 100).toInt(),
            left = (width * (x - halfWidth) / 100).toInt(),
            right = (width * (100 - x - halfWidth) / 100).toInt(),
        )
    }

    private fun roundTo8(value: Double): Int = (Math.round(value / 8) * 8).toInt().coerceAtLeast(8)
}
