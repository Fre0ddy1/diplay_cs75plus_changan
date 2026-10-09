package com.shilapi.xcertplay.airplay

/**
 * Where DiPlay draws the instruction card on top of the dashboard map.
 *
 * Placement is a percent of the full 1920×720 panel so Left/Right can reach the
 * cluster edges. Size still follows the measured centre navi window so Large
 * does not cover half the cluster.
 */
object ClusterTurnCardOverlay {
    data class CardRect(val left: Int, val top: Int, val width: Int, val height: Int)

    const val STEP_PERCENT = 2
    // Default placement: top-centre (user asked for the turn guidance at the top middle;
    // the lane card owns the top-left, the exit badge the top-right).
    const val DEFAULT_X_PERCENT = 50
    const val DEFAULT_Y_PERCENT = 12
    val xPercents = listOf(
        8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 32, 34, 36, 38, 40, 42, 44, 46, 48,
        50, 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76, 78, 80, 82, 84, 86, 88, 90, 92,
    )
    val yPercents = listOf(
        0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 32, 34, 36, 38, 40, 42, 44,
        46, 48, 50, 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76, 78, 80, 82, 84, 86, 88,
        90, 92, 94, 96, 98, 100,
    )

    fun card(
        panelWidth: Int,
        panelHeight: Int,
        xPercent: Int,
        yPercent: Int,
        size: CarPlayClusterDisplay.OverlaySize,
        @Suppress("UNUSED_PARAMETER") contentTopPx: Int = 0,
    ): CardRect {
        require(panelWidth > 0 && panelHeight > 0)
        val window = visibleWindow(panelWidth, panelHeight)
        val widthFraction = when (size) {
            CarPlayClusterDisplay.OverlaySize.SMALL -> 0.42f
            CarPlayClusterDisplay.OverlaySize.MEDIUM -> 0.56f
            CarPlayClusterDisplay.OverlaySize.LARGE -> 0.70f
        }
        val heightFraction = when (size) {
            CarPlayClusterDisplay.OverlaySize.SMALL -> 0.22f
            CarPlayClusterDisplay.OverlaySize.MEDIUM -> 0.28f
            CarPlayClusterDisplay.OverlaySize.LARGE -> 0.34f
        }
        val width = (window.width * widthFraction).toInt().coerceAtLeast(140).coerceAtMost(panelWidth)
        val height = (window.height * heightFraction).toInt().coerceAtLeast(72).coerceAtMost(panelHeight)
        val x = snap(xPercent, xPercents)
        val y = snap(yPercent, yPercents)
        val left = (panelWidth * x / 100 - width / 2).coerceIn(0, panelWidth - width)
        // The dashboard projection fills the whole 1920x720 panel (canvas == display, verified
        // on the UNI-V), so percent y is percent of the FULL panel height and y=0 reaches the
        // very top. The old contentTopPx offset (a leftover from a 1440px-panel assumption)
        // is deliberately ignored: it pushed every card down ~300px and made the Y controls
        // unable to move cards upward (field report 10-09).
        val top = (panelHeight * y / 100 - height / 2).coerceIn(0, panelHeight - height)
        return CardRect(left, top, width, height)
    }

    fun snap(value: Int, choices: List<Int>): Int =
        choices.minByOrNull { kotlin.math.abs(it - value) } ?: choices.first()

    internal fun visibleWindow(panelWidth: Int, panelHeight: Int): CardRect {
        val area = CarPlayClusterDisplay.SAFE_AREA_PERCENT
        val left = panelWidth * area.left / 100
        val top = panelHeight * area.top / 100
        val width = panelWidth * (100 - area.left - area.right) / 100
        val height = panelHeight * (100 - area.top - area.bottom) / 100
        return CardRect(left, top, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }
}
