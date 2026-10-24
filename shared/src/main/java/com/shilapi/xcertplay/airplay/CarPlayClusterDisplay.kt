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
     * What the dashboard shows: one of the cluster contents the iPhone lists in `altScreenURLs`.
     * On the tested car the iPhone glass turn card needed 0–5 kbit/s against 0.3–4 Mbit/s for the map.
     * MAP_WITH_CUSTOM_CARD asks for the map stream so DiPlay can draw a movable card on top.
     */
    enum class Content(val url: String) {
        /** Apple's own instrument-cluster view: map, car model and its turn box. */
        INSTRUMENTS("maps:/car/instrumentcluster"),
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
         * The fly-in card ("飞屏" style, 2026-10-24): the dashboard keeps the stock cluster UI and
         * we show the Apple instrument-cluster stream (map + Apple's own glass turn card with lane
         * guidance, junction view etc.) inside a small card docked at the driver's side of the
         * panel. The window root is fully transparent, so the gauge cluster shows around the card.
         *
         * Uses the same URL as INSTRUMENTS so that iOS paints the turn card, lane guidance and
         * junction view into the video stream; the card then crops the stream to its bounds.
         */
        FLY_CARD("maps:/car/instrumentcluster"),
    }


    fun usesCustomTurnCard(content: Content): Boolean = content == Content.MAP_WITH_CUSTOM_CARD

    /** The fly-in card mode: transparent dashboard with one small map card. */
    fun isFlyCard(content: Content): Boolean = content == Content.FLY_CARD

    /**
     * True for every cluster view drawn by the phone rather than by us: those already contain the
     * turn instruction, so no card of ours belongs on top of them.
     */
    fun usesOfficialTurnCard(content: Content): Boolean =
        content == Content.INSTRUMENTS || content == Content.FLY_CARD

    /**
     * Where the car marker goes, as percent of the panel (left, top, right, bottom). Measured on a
     * DiLink 5.0 cluster with a calibration grid. In "Full screen navi" BYD draws a status row,
     * turn/ADAS icons, the ADAS lane view (from x 68 %), speed and power readouts and a bottom band
     * over the map; "Small screen navi" crops the panel to a centre window of about x 31–68 %,
     * y 16–89 %. This centre area is clear in both modes, so no per-mode setting is needed.
     */
    val SAFE_AREA_PERCENT = AirPlayInsets(top = 16, bottom = 25, left = 35, right = 36)

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
