package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydLane
import com.shilapi.xcertplay.hud.BydLaneGuidance
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * Instruction card drawn by DiPlay on top of the dashboard map.
 *
 * Visual language follows Apple's turn banners: a dark glass capsule with a hairline stroke,
 * the maneuver glyph in a soft chip on the left, distance and road stacked on the right.
 * Maneuver glyphs are Material Symbols (Apache 2.0), tinted the system blue; the roundabout
 * exit number sits in a small badge on the glyph.
 */
internal class ClusterTurnCardView(
    context: Context,
    private val onDebug: (String) -> Unit = {},
) : View(context) {
    private var guidance: ClusterTurnGuidance? = null
    private var xPx = AirPlayPersistence.DEFAULT_CLUSTER_TURN_CARD_X_PX
    private var yPx = AirPlayPersistence.DEFAULT_CLUSTER_TURN_CARD_Y_PX
    /** Size in percent of the designed card (100 = the old MEDIUM preset). */
    private var scalePercent = AirPlayPersistence.CLUSTER_CARD_SCALE_DEFAULT
    /** Fill opacity 0-100, same control the song card has (50 = the shared default). */
    private var backgroundAlpha = 50

    private val accent = Color.rgb(10, 132, 255)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(2f, 0f, 1f, Color.argb(140, 0, 0, 0))
    }
    // Text paints are created per-draw (labelPaint caches only typeface/shadow geometry); the two
    // labels keep their sizes, so we hold the paint references and only update textSize in onDraw.
    private val distancePaint = GlassCardStyle.labelPaint(0f, 255, true)
    private val roadPaint = GlassCardStyle.labelPaint(0f, 200, false)
    private val rect = RectF()
    private val textBackstop = RectF()
    private var glyph: Drawable? = null
    private var glyphTag: Int = -1

    // Per-frame cost killers: cached text widths — the card is redrawn on every guidance
    // change and the distance label ticks, but a given string's width is stable, so
    // measureText only reruns when the string (or its size) actually changed.
    private var lastDistance = ""
    private var lastDistanceSize = 0f
    private var lastRoad = ""
    private var lastRoadSize = 0f
    private var wDistance = 0f
    private var wRoad = 0f
    private var lastLayoutLog = ""
    /** Lane guidance from iAP2 0x5204, drawn as a strip under the turn capsule. */
    private var lanes: BydLaneGuidance? = null
    /** Optional junction-view picture; the card grows only while this is present. */
    private var junctionImage: Bitmap? = null
    private val junctionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val laneArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val laneVariableTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun setLanes(next: BydLaneGuidance?) {
        if (lanes == next) return
        lanes = next
        onDebug(
            "Turn card lanes: " +
                if (next == null || next.lanes.isEmpty()) "none" else
                    "event=${next.eventIndex} showing=${next.showing} " +
                        "preferred=${next.currentLane} " +
                        next.lanes.joinToString(" | ") {
                            "#${it.position} st=${it.status} ang=${it.angles.joinToString("/")} " +
                                "hi=${it.highlight ?: "-"} primary=${it.primaryAngle ?: "-"}"
                        },
        )
        invalidate()
    }

    /** Junction view from route guidance, if the phone provides one; null shrinks the card. */
    fun setJunctionImage(next: Bitmap?) {
        if (junctionImage === next) return
        junctionImage = next
        onDebug(
            "Turn card junction: " +
                (next?.let { "${it.width}x${it.height}" } ?: "none"),
        )
        invalidate()
    }

    fun setLayout(xPx: Int, yPx: Int, scalePercent: Int) {
        this.xPx = xPx.coerceIn(0, 1920)
        this.yPx = yPx.coerceIn(0, 720)
        this.scalePercent = scalePercent
        invalidate()
    }

    /** Background strength from the settings page (0 = glass edge only, 100 = opaque black). */
    fun setBackgroundAlpha(percent: Int) {
        val next = percent.coerceIn(0, 100)
        if (next == backgroundAlpha) return
        backgroundAlpha = next
        invalidate()
    }

    private fun backgroundFill(): Int =
        Color.argb((255 * backgroundAlpha.coerceIn(0, 100) / 100f).toInt().coerceIn(0, 255), 0, 0, 0)

    fun setGuidance(next: ClusterTurnGuidance?) {
        if (guidance == next) {
            visibility = if (next == null) GONE else VISIBLE
            return
        }
        guidance = next
        val wasVisible = visibility == VISIBLE
        visibility = if (next == null) GONE else VISIBLE
        // bringToFront() triggers a layout pass, so only raise the layer on the
        // first show — the turn card updates every second and must not requestLayout each time.
        if (visibility == VISIBLE && !wasVisible) bringToFront()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val next = guidance ?: return
        val panelW = width.toFloat()
        val panelH = height.toFloat()
        // 28 % of the panel is the designed turn row; lanes and the junction picture are
        // additional rows inside the SAME card, and each row exists only when it has data.
        val heightFraction = 0.28f * (scalePercent / 100f)
        val h = (panelH * heightFraction).coerceAtLeast(72f).coerceAtMost(panelH)
        val radius = h * 0.30f
        val chip = h * 0.76f
        val chipLeft = h * 0.12f
        val textLeft = chipLeft + chip + h * 0.14f
        val distanceText = distanceLabel(next.distanceMeters)
        val roadText = roadLabel(next)
        distancePaint.textSize = h * 0.30f
        roadPaint.textSize = h * 0.17f
        // Cached measurement: only re-measure when the string or its size changed.
        if (distanceText != lastDistance || distancePaint.textSize != lastDistanceSize) {
            lastDistance = distanceText
            lastDistanceSize = distancePaint.textSize
            wDistance = distancePaint.measureText(distanceText)
        }
        if (roadText.isNotEmpty() && (roadText != lastRoad || roadPaint.textSize != lastRoadSize)) {
            lastRoad = roadText
            lastRoadSize = roadPaint.textSize
            wRoad = roadPaint.measureText(roadText)
        }
        val textW = maxOf(
            wDistance,
            if (roadText.isNotEmpty()) wRoad else 0f,
        )
        val laneData = lanes?.takeIf { it.lanes.isNotEmpty() }
        val laneHeight = if (laneData != null) h * 0.44f else 0f
        val gap = h * 0.10f
        val junction = junctionImage
        // Width follows all three rows, not just the text: a wide junction picture or many lanes
        // widen the card, while a missing row never reserves width.
        val laneMinWidth = if (laneData != null) laneData.lanes.size * h * 0.42f else 0f
        val baseHeightNoJunction = h +
            (if (laneData != null) gap + laneHeight + h * 0.06f else 0f)
        val junctionMinWidth = if (junction != null && panelH - baseHeightNoJunction > gap) {
            (h * 1.65f * junction.width / junction.height).coerceAtMost(panelW)
        } else {
            0f
        }
        val w = maxOf(
            textLeft + textW + h * 0.10f,
            laneMinWidth + h * 0.16f,
            junctionMinWidth,
        ).coerceAtMost(panelW)
        val junctionHeight = junction?.let {
            val baseHeight = h +
                (if (laneData != null) gap + laneHeight + h * 0.06f else 0f)
            val available = (panelH - baseHeight - gap).coerceAtLeast(0f)
            (w * it.height.toFloat() / it.width)
                .coerceAtMost(panelH * 0.42f)
                .coerceAtMost(available)
        } ?: 0f
        val hasExtra = laneData != null || junctionHeight > 0f
        val totalH = h +
            (if (laneData != null) gap + laneHeight else 0f) +
            (if (junctionHeight > 0f) gap + junctionHeight else 0f) +
            (if (hasExtra) h * 0.06f else 0f)
        val layoutKey = "w=${w.toInt()} h=${totalH.toInt()} " +
            "lanes=${laneData?.lanes?.size ?: 0} " +
            "junction=${junction?.let { "${it.width}x${it.height}" } ?: "none"}"
        if (layoutKey != lastLayoutLog) {
            lastLayoutLog = layoutKey
            onDebug("Turn card layout: $layoutKey")
        }
        var left = xPx.toFloat().coerceIn(0f, (panelW - w).coerceAtLeast(0f))
        var top = yPx.toFloat().coerceIn(0f, (panelH - totalH).coerceAtLeast(0f))
        if (left != xPx.toFloat() || top != yPx.toFloat()) {
            AirPlayPersistence.saveClusterTurnCardXSilent(context, left.toInt())
            AirPlayPersistence.saveClusterTurnCardYSilent(context, top.toInt())
            xPx = left.toInt()
            yPx = top.toInt()
        }

        rect.set(left, top, left + w, top + totalH)
        // Liquid-glass capsule: one background for all rows, so no seam or empty bottom strip.
        GlassCardStyle.drawCapsule(canvas, rect, radius, backgroundFill())

        val chipTop = top + (h - chip) / 2f
        rect.set(chipLeft + left, chipTop, chipLeft + left + chip, chipTop + chip)
        GlassCardStyle.drawChip(canvas, rect, chip * 0.26f)

        val exit = next.roundaboutExit.takeIf { it in 1..9 }
        drawGlyph(canvas, next, chipLeft + left, chipTop, chip, exit)

        // Legibility backstop over the text area, then the labels.
        textBackstop.set(left, top, left + w, top + h)
        GlassCardStyle.drawTextBackstop(canvas, textBackstop, radius)

        val textX = left + textLeft
        canvas.drawText(distanceText, textX, top + h * 0.44f, distancePaint)
        if (roadText.isNotEmpty()) {
            canvas.drawText(roadText, textX, top + h * 0.72f, roadPaint)
        }

        var contentTop = top + h
        if (laneData != null) {
            contentTop += gap
            drawLanes(canvas, left, contentTop, w, laneHeight, laneData)
            contentTop += laneHeight
        }
        if (junction != null && junctionHeight > 0f) {
            contentTop += gap
            val dst = RectF(
                left + h * 0.05f,
                contentTop,
                left + w - h * 0.05f,
                contentTop + junctionHeight,
            )
            canvas.save()
            canvas.clipRect(dst)
            canvas.drawBitmap(junction, null, dst, junctionPaint)
            canvas.restore()
        }
    }

    /**
     * Lane strip inside the card: one arrow per lane from 0x5204, the current lane highlighted.
     * The strip is part of the capsule, not a second floating box below it.
     */
    private fun drawLanes(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        laneData: BydLaneGuidance,
    ) {
        val count = laneData.lanes.size
        if (count == 0) return
        val cell = width / count
        val arrow = height * 0.48f
        for (i in 0 until count) {
            val lane = laneData.lanes[i]
            val cx = left + cell * (i + 0.5f)
            val cy = top + height / 2f
            drawLaneGlyph(canvas, cx, cy, arrow, lane)
        }
    }

    /**
     * Draws every allowed direction in a lane, not just the first. A single direction uses the
     * full glyph; a composite lane (straight+left, straight+right, left+U-turn, ...) is drawn as
     * one shared shaft with a branch per direction, so no combination has to be enumerated.
     */
    private fun drawLaneGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float, lane: BydLane) {
        val codes = buildList {
            lane.primaryAngle?.let { add(angleMask(it)) }
            for (angle in lane.angles) add(angleMask(angle))
        }.filter { it != 0 }.distinct()
        val on = Color.WHITE
        val off = Color.rgb(125, 136, 148)
        if (codes.isEmpty()) {
            // No usable angle: iOS gives us no direction, so draw the two-bar variable/unknown
            // lane marker instead of leaving an invisible hole in the strip.
            drawVariableLane(canvas, cx, cy, size, if (lane.status == 2) on else off)
            return
        }
        val highlight = lane.highlight?.let(::angleMask) ?: 0
        val wholeLane = highlight == 0 && lane.status == 2
        fun selected(code: Int): Boolean = wholeLane || highlight == code
        if (codes.size == 1) {
            drawLaneArrow(canvas, cx, cy, size, codes[0], if (selected(codes[0])) on else off)
        } else {
            drawCompositeLane(canvas, cx, cy, size, codes, ::selected)
        }
    }

    /**
     * Variable / unknown lane: two vertical bars with 可 / 变 stacked between them.
     * The wire has no variable-lane flag; this is used when the lane has no usable angle.
     */
    private fun drawVariableLane(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val half = size / 2f
        val barX = size * 0.18f
        laneArrowPaint.color = color
        laneArrowPaint.strokeWidth = maxOf(2f, size * 0.065f)
        canvas.drawLine(cx - barX, cy - half, cx - barX, cy + half, laneArrowPaint)
        canvas.drawLine(cx + barX, cy - half, cx + barX, cy + half, laneArrowPaint)
        laneVariableTextPaint.color = color
        laneVariableTextPaint.textSize = size * 0.27f
        val first = cy - size * 0.06f
        canvas.drawText("\u53ef", cx, first, laneVariableTextPaint)
        canvas.drawText("\u53d8", cx, first + size * 0.29f, laneVariableTextPaint)
    }
    /**
     * One full primary arrow, then every other direction branches from the primary shaft's
     * midpoint. This is the iOS composite shape: the main lane is complete, and a turn/U-turn
     * starts halfway up the main lane instead of replacing it or forking from the bottom.
     */
    private fun drawCompositeLane(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        codes: List<Int>,
        selected: (Int) -> Boolean,
    ) {
        val on = Color.WHITE
        val off = Color.rgb(125, 136, 148)
        val hasStraight = codes.any { it and 1 != 0 }
        val hasLeft = codes.any { it and 2 != 0 }
        val hasRight = codes.any { it and 4 != 0 }

        if (hasStraight) {
            // Straight is the main lane; every turn starts at the middle of its shaft.
            drawLaneArrow(canvas, cx, cy, size, 1, if (selected(1)) on else off)
            for (code in codes) {
                if (code != 1) {
                    drawSecondaryOrUturn(
                        canvas, cx, cy, size / 2f, code,
                        if (selected(code)) on else off, 1,
                    )
                }
            }
            return
        }

        if (hasLeft && hasRight) {
            // A T-bar, not a full left arrow: the horizontal head carries both directions.
            drawLeftRightT(canvas, cx, cy, size, selected)
            for (code in codes) {
                if (code != 2 && code != 4) {
                    drawSecondaryOrUturn(
                        canvas, cx, cy, size / 2f, code,
                        if (selected(code)) on else off, 1,
                    )
                }
            }
            return
        }

        // Other pairs (left+U-turn, right+U-turn, slight-left+left, ...): one full main arrow,
        // the other direction hangs from its midpoint.
        val base = codes.first()
        drawLaneArrow(canvas, cx, cy, size, base, if (selected(base)) on else off)
        for (code in codes) {
            if (code != base) {
                drawSecondaryOrUturn(
                    canvas, cx, cy, size / 2f, code,
                    if (selected(code)) on else off, base,
                )
            }
        }
    }

    /** Left + right: vertical shaft and one horizontal bar with an arrowhead at each end. */
    private fun drawLeftRightT(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        selected: (Int) -> Boolean,
    ) {
        val r = size / 2f
        val barY = cy - r * 0.55f
        val barColor = if (selected(2) || selected(4)) Color.WHITE else Color.rgb(125, 136, 148)
        laneArrowPaint.color = barColor
        canvas.drawLine(cx, cy + r, cx, barY, laneArrowPaint)
        canvas.drawLine(cx - r * 0.55f, barY, cx + r * 0.55f, barY, laneArrowPaint)

        laneArrowPaint.color = if (selected(2)) Color.WHITE else Color.rgb(125, 136, 148)
        canvas.drawLine(
            cx - r * 0.55f, barY,
            cx - r * 0.90f, barY,
            laneArrowPaint,
        )
        val leftHead = Path().apply {
            moveTo(cx - r * 0.52f, barY - r * 0.25f)
            lineTo(cx - r * 0.95f, barY)
            lineTo(cx - r * 0.52f, barY + r * 0.25f)
        }
        canvas.drawPath(leftHead, laneArrowPaint)

        laneArrowPaint.color = if (selected(4)) Color.WHITE else Color.rgb(125, 136, 148)
        canvas.drawLine(
            cx + r * 0.55f, barY,
            cx + r * 0.90f, barY,
            laneArrowPaint,
        )
        val rightHead = Path().apply {
            moveTo(cx + r * 0.52f, barY - r * 0.25f)
            lineTo(cx + r * 0.95f, barY)
            lineTo(cx + r * 0.52f, barY + r * 0.25f)
        }
        canvas.drawPath(rightHead, laneArrowPaint)
    }

    private fun drawSecondaryOrUturn(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        code: Int,
        color: Int,
        base: Int,
    ) {
        if (code == 128 || code == 256) {
            drawUturnSecondary(canvas, cx, cy, r, code, color, base)
        } else {
            drawSecondaryLane(canvas, cx, cy, r, code, color)
        }
    }

    /**
     * Composite U-turn, half-height and sharing the main arrow's vertical shaft.
     *
     * The right leg of the U is the main shaft itself, so only the left half is drawn: from the
     * shaft midpoint, curve left and down, then end with a down arrow. This is the hand-drawn
     * reference layout; a full two-legged U here would double the shaft and look tangled.
     */
    private fun drawUturnSecondary(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        code: Int,
        color: Int,
        base: Int,
    ) {
        val left = code == 128
        val dir = if (left) -1f else 1f
        val startX = when (base) {
            2 -> cx + r * 0.20f
            4 -> cx - r * 0.20f
            else -> cx
        }
        val startY = cy + r * 0.10f
        val outerX = startX + dir * r * 0.62f
        val bottomY = cy + r * 0.92f
        laneArrowPaint.color = color
        val path = Path()
        path.moveTo(startX, startY)
        // Right leg is the existing main shaft; draw only the shoulder and left leg.
        path.cubicTo(
            startX + dir * r * 0.18f, startY - r * 0.10f,
            outerX, startY - r * 0.02f,
            outerX, startY + r * 0.30f,
        )
        path.lineTo(outerX, bottomY - r * 0.10f)
        path.moveTo(outerX - r * 0.20f, bottomY - r * 0.18f)
        path.lineTo(outerX, bottomY + r * 0.02f)
        path.lineTo(outerX + r * 0.20f, bottomY - r * 0.18f)
        canvas.drawPath(path, laneArrowPaint)
    }

    /**
     * Secondary direction: starts at the primary shaft midpoint, so the main arrow stays whole.
     * The colour is per direction: a left+right lane can highlight only the right head.
     */
    private fun drawSecondaryLane(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        code: Int,
        color: Int,
    ) {
        laneArrowPaint.color = color
        val path = Path()
        when {
            code and 1 != 0 -> { // straight branch upward
                path.moveTo(cx, cy)
                path.lineTo(cx, cy - r)
                path.moveTo(cx - r * 0.28f, cy - r * 0.68f)
                path.lineTo(cx, cy - r)
                path.lineTo(cx + r * 0.28f, cy - r * 0.68f)
            }
            code and 2 != 0 -> { // left branch from the midpoint
                path.moveTo(cx, cy)
                path.lineTo(cx - r * 0.90f, cy)
                path.moveTo(cx - r * 0.52f, cy - r * 0.26f)
                path.lineTo(cx - r * 0.95f, cy)
                path.lineTo(cx - r * 0.52f, cy + r * 0.26f)
            }
            code and 4 != 0 -> { // right branch from the midpoint
                path.moveTo(cx, cy)
                path.lineTo(cx + r * 0.90f, cy)
                path.moveTo(cx + r * 0.52f, cy - r * 0.26f)
                path.lineTo(cx + r * 0.95f, cy)
                path.lineTo(cx + r * 0.52f, cy + r * 0.26f)
            }
            code and 8 != 0 -> { // slight left branch
                path.moveTo(cx, cy)
                path.lineTo(cx - r * 0.42f, cy - r)
                path.moveTo(cx - r * 0.62f, cy - r * 0.70f)
                path.lineTo(cx - r * 0.42f, cy - r)
                path.lineTo(cx - r * 0.10f, cy - r * 0.78f)
            }
            code and 16 != 0 -> { // slight right branch
                path.moveTo(cx, cy)
                path.lineTo(cx + r * 0.42f, cy - r)
                path.moveTo(cx + r * 0.62f, cy - r * 0.70f)
                path.lineTo(cx + r * 0.42f, cy - r)
                path.lineTo(cx + r * 0.10f, cy - r * 0.78f)
            }
            code and 128 != 0 -> { // U-turn left branch, starting halfway up the main shaft
                path.moveTo(cx, cy)
                path.lineTo(cx - r * 0.52f, cy)
                path.lineTo(cx - r * 0.52f, cy - r * 0.55f)
                path.cubicTo(
                    cx - r * 0.52f, cy - r,
                    cx - r * 0.18f, cy - r,
                    cx - r * 0.18f, cy - r * 0.55f,
                )
                path.lineTo(cx - r * 0.18f, cy - r * 0.05f)
                path.moveTo(cx - r * 0.48f, cy + r * 0.10f)
                path.lineTo(cx - r * 0.18f, cy + r * 0.25f)
                path.lineTo(cx + r * 0.12f, cy + r * 0.10f)
            }
            code and 256 != 0 -> { // U-turn right branch
                path.moveTo(cx, cy)
                path.lineTo(cx + r * 0.52f, cy)
                path.lineTo(cx + r * 0.52f, cy - r * 0.55f)
                path.cubicTo(
                    cx + r * 0.52f, cy - r,
                    cx + r * 0.18f, cy - r,
                    cx + r * 0.18f, cy - r * 0.55f,
                )
                path.lineTo(cx + r * 0.18f, cy - r * 0.05f)
                path.moveTo(cx + r * 0.48f, cy + r * 0.10f)
                path.lineTo(cx + r * 0.18f, cy + r * 0.25f)
                path.lineTo(cx - r * 0.12f, cy + r * 0.10f)
            }
        }
        canvas.drawPath(path, laneArrowPaint)
    }

    /**
     * Map the verified iAP2 lane angle to the existing glyph vocabulary.
     * 0 = straight, negative = left, positive = right; 1000/absent must stay unknown.
     */
    private fun angleMask(angle: Int?): Int {
        if (angle == null || !BydLane.isValidAngle(angle)) return 0
        val a = angle
        return when {
            a <= -157 -> 128 // U-turn to the left
            a >= 157 -> 256 // U-turn to the right
            a < -112 -> 2 // sharp left, drawn as left
            a < -67 -> 2 // left
            a < -22 -> 8 // slight left
            a <= 22 -> 1 // straight
            a <= 67 -> 16 // slight right
            a <= 112 -> 4 // right
            else -> 4 // sharp right, drawn as right
        }
    }

    /** One arrow for a lane bitmask (bit0 straight, bit1 left, bit2 right, bit3/4 slight, 5/6 sharp, bit7 U-turn). */
    private fun drawLaneArrow(canvas: Canvas, cx: Float, cy: Float, size: Float, mask: Int, color: Int) {
        laneArrowPaint.color = color
        val r = size / 2f
        val path = Path()
        when {
            mask and 1 != 0 -> { // straight
                path.moveTo(cx, cy + r)
                path.lineTo(cx, cy - r)
                path.moveTo(cx - r * 0.30f, cy - r * 0.68f)
                path.lineTo(cx, cy - r)
                path.lineTo(cx + r * 0.30f, cy - r * 0.68f)
            }
            mask and 2 != 0 -> { // left
                path.moveTo(cx + r * 0.20f, cy + r)
                path.lineTo(cx + r * 0.20f, cy - r * 0.62f)
                path.lineTo(cx - r * 0.82f, cy - r * 0.62f)
                path.moveTo(cx - r * 0.25f, cy - r)
                path.lineTo(cx - r * 0.82f, cy - r * 0.62f)
                path.lineTo(cx - r * 0.25f, cy - r * 0.24f)
            }
            mask and 4 != 0 -> { // right
                path.moveTo(cx - r * 0.20f, cy + r)
                path.lineTo(cx - r * 0.20f, cy - r * 0.62f)
                path.lineTo(cx + r * 0.82f, cy - r * 0.62f)
                path.moveTo(cx + r * 0.25f, cy - r)
                path.lineTo(cx + r * 0.82f, cy - r * 0.62f)
                path.lineTo(cx + r * 0.25f, cy - r * 0.24f)
            }
            mask and 8 != 0 -> { // slight left
                path.moveTo(cx + r * 0.25f, cy + r)
                path.lineTo(cx - r * 0.45f, cy - r)
                path.moveTo(cx - r * 0.55f, cy - r * 0.65f)
                path.lineTo(cx - r * 0.45f, cy - r)
                path.lineTo(cx - r * 0.05f, cy - r * 0.80f)
            }
            mask and 16 != 0 -> { // slight right
                path.moveTo(cx - r * 0.25f, cy + r)
                path.lineTo(cx + r * 0.45f, cy - r)
                path.moveTo(cx + r * 0.55f, cy - r * 0.65f)
                path.lineTo(cx + r * 0.45f, cy - r)
                path.lineTo(cx + r * 0.05f, cy - r * 0.80f)
            }
            mask and 256 != 0 -> { // U-turn to the right
                path.moveTo(cx - r * 0.35f, cy + r)
                path.lineTo(cx - r * 0.35f, cy - r * 0.45f)
                path.cubicTo(
                    cx - r * 0.35f, cy - r,
                    cx + r * 0.35f, cy - r,
                    cx + r * 0.35f, cy - r * 0.45f,
                )
                path.lineTo(cx + r * 0.35f, cy + r * 0.40f)
                path.moveTo(cx + r * 0.65f, cy + r * 0.55f)
                path.lineTo(cx + r * 0.35f, cy + r)
                path.lineTo(cx + r * 0.05f, cy + r * 0.55f)
            }
            mask and 128 != 0 -> { // U-turn to the left
                path.moveTo(cx + r * 0.35f, cy + r)
                path.lineTo(cx + r * 0.35f, cy - r * 0.45f)
                path.cubicTo(
                    cx + r * 0.35f, cy - r,
                    cx - r * 0.35f, cy - r,
                    cx - r * 0.35f, cy - r * 0.45f,
                )
                path.lineTo(cx - r * 0.35f, cy + r * 0.40f)
                path.moveTo(cx - r * 0.65f, cy + r * 0.55f)
                path.lineTo(cx - r * 0.35f, cy + r)
                path.lineTo(cx - r * 0.05f, cy + r * 0.55f)
            }
            else -> { // sharp left/right and unknown: use the aligned left shape
                path.moveTo(cx + r * 0.20f, cy + r)
                path.lineTo(cx + r * 0.20f, cy - r * 0.62f)
                path.lineTo(cx - r * 0.82f, cy - r * 0.62f)
                path.moveTo(cx - r * 0.25f, cy - r)
                path.lineTo(cx - r * 0.82f, cy - r * 0.62f)
                path.lineTo(cx - r * 0.25f, cy - r * 0.24f)
            }
        }
        canvas.drawPath(path, laneArrowPaint)
    }

    /** Draws the tinted Material Symbols glyph; the roundabout exit number gets a corner badge. */
    private fun drawGlyph(canvas: Canvas, next: ClusterTurnGuidance, left: Float, top: Float, side: Float, exit: Int?) {
        // Preserve distance/road without inventing an instruction for an unknown maneuver.
        if (next.icon == 0) return
        val resId = glyphRes(next.icon)
        if (resId != glyphTag) {
            glyph = ContextCompat.getDrawable(context, resId)?.mutate()?.apply { setTint(accent) }
            glyphTag = resId
        }
        val inset = side * 0.10f
        glyph?.setBounds(
            (left + inset).toInt(), (top + inset).toInt(),
            (left + side - inset).toInt(), (top + side - inset).toInt(),
        )
        glyph?.draw(canvas)
        if (exit != null) {
            val d = side * 0.42f
            val cx = left + side - d / 2f
            val cy = top + side - d / 2f
            canvas.drawCircle(cx, cy, d / 2f, badgePaint)
            badgeTextPaint.textSize = d * 0.62f
            val textY = cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(exit.toString(), cx, textY, badgeTextPaint)
        }
    }

    private fun glyphRes(icon: Int): Int = when (icon) {
        2 -> R.drawable.ic_turn_card_turn_left
        3 -> R.drawable.ic_turn_card_turn_right
        4 -> R.drawable.ic_turn_card_turn_slight_left
        5 -> R.drawable.ic_turn_card_turn_slight_right
        6 -> R.drawable.ic_turn_card_turn_sharp_left
        7 -> R.drawable.ic_turn_card_turn_sharp_right
        8 -> R.drawable.ic_turn_card_u_turn_left
        19 -> R.drawable.ic_turn_card_u_turn_right
        11, 12, 17, 18 -> R.drawable.ic_turn_card_roundabout_right
        15 -> R.drawable.ic_turn_card_sports_score
        else -> R.drawable.ic_turn_card_straight
    }

    private fun distanceLabel(meters: Int): String = when {
        meters <= 20 -> context.getString(R.string.turn_card_now)
        meters < 1000 -> context.getString(R.string.turn_card_distance_m, meters)
        else -> context.getString(
            R.string.turn_card_distance_km, meters / 100 / 10f,
        )
    }

    private fun roadLabel(next: ClusterTurnGuidance): String =
        if (next.roundaboutExit in 1..9) {
            context.getString(R.string.turn_card_exit, next.roundaboutExit)
        } else {
            next.road
        }
}
