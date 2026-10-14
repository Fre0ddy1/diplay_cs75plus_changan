package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.shilapi.xcertplay.hud.BydLaneGuidance

/**
 * Lane-guidance card drawn by DiPlay at the bottom of the dashboard, nav-only mode.
 *
 * One capsule per lane (road order, left to right), each showing the permitted directions
 * as small arrows; the driver's current lane is highlighted with the system accent. The
 * data comes from iAP2 0x5204 (Apple CPLaneGuidance), decoded by BydHudRouteState.
 */
internal class ClusterLaneGuidanceView(context: Context) : View(context) {
    private var lanes: BydLaneGuidance? = null

    private val accentStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(10, 132, 255); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val arrowActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        setShadowLayer(3f, 0f, 1f, Color.argb(120, 0, 0, 0))
    }
    private val arrowIdlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        setShadowLayer(3f, 0f, 1f, Color.argb(120, 0, 0, 0))
    }
    private val rect = RectF()

    // Per-frame cost killer: cached position — the hot draw path never touches
    // SharedPreferences; refreshed by notifyPositionChanged() on settings Apply.
    private var posX = Float.NaN
    private var posY = Float.NaN

    /** External position change (settings Apply) — drop the cached position so the next draw reloads it. */
    fun notifyPositionChanged() {
        posX = Float.NaN
        posY = Float.NaN
        invalidate()
    }

    fun setLaneGuidance(next: BydLaneGuidance?) {
        lanes = next
        val visible = next != null && next.lanes.isNotEmpty()
        val wasVisible = visibility == VISIBLE
        visibility = if (visible) VISIBLE else GONE
        // bringToFront() triggers a layout pass, so only raise the layer on the
        // first show — repeated guidance updates must not requestLayout every frame.
        if (visible && !wasVisible) bringToFront()
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
        val guidance = lanes ?: return
        val laneMasks = guidance.lanes
        if (laneMasks.isEmpty()) return

        val unit = minOf(width, height).coerceAtLeast(1)
        val margin = unit * 0.045f
        val cardH = unit * 0.12f
        val laneW = cardH * 1.35f
        val gap = unit * 0.008f
        val radius = cardH * 0.24f
        // Top-left anchored: absolute (x, y) relative to the full dashboard panel
        // (1920x720 canvas == display). Y is measured from the very top of the screen.
        val totalW = laneMasks.size * laneW + (laneMasks.size - 1) * gap
        // Top-left anchored: absolute (x, y) relative to the full dashboard panel
        // (1920x720 canvas == display). Y is measured from the very top of the screen.
        // Position is cached — refreshed by notifyPositionChanged() on settings Apply.
        var left = posX
        var top = posY
        if (left.isNaN()) {
            left = AirPlayPersistence.loadClusterCardX(context, "lane").toFloat()
            top = AirPlayPersistence.loadClusterCardY(context, "lane").toFloat()
            posX = left
            posY = top
        }
        // Overflow correction: shift back inside and persist the corrected position. The
        // cache is updated too, so the correction persists exactly once per change.
        val clampedLeft = left.coerceIn(0f, (width - totalW).coerceAtLeast(0f))
        val clampedTop = top.coerceIn(0f, (height - cardH).coerceAtLeast(0f))
        if (clampedLeft != left || clampedTop != top) {
            AirPlayPersistence.saveClusterCardXSilent(context, "lane", clampedLeft.toInt())
            AirPlayPersistence.saveClusterCardYSilent(context, "lane", clampedTop.toInt())
            left = clampedLeft
            top = clampedTop
            posX = left
            posY = top
        }

        laneMasks.forEachIndexed { index, mask ->
            val x = left + index * (laneW + gap)
            val current = index == guidance.currentLane
            rect.set(x, top, x + laneW, top + cardH)
            // Current lane = blue-highlighted glass chip; other lanes = neutral glass chip.
            GlassCardStyle.drawChip(canvas, rect, radius, highlight = current)
            drawDirections(canvas, mask, x, top, laneW, cardH, if (current) arrowActivePaint else arrowIdlePaint)
        }
    }

    /** Draws up to two direction arrows (primary + secondary) inside a lane capsule. */
    private fun drawDirections(
        canvas: Canvas,
        mask: Int,
        laneLeft: Float,
        laneTop: Float,
        laneW: Float,
        laneH: Float,
        paint: Paint,
    ) {
        val angles = directionAngles(mask)
        if (angles.isEmpty()) return
        paint.strokeWidth = laneH * 0.16f
        val cx = laneLeft + laneW / 2f
        val cy = laneTop + laneH / 2f
        val arrowSize = laneH * 0.42f
        when {
            angles.size == 1 -> drawArrow(canvas, cx, cy, arrowSize, angles[0], paint)
            else -> {
                val spacing = laneW * 0.28f
                drawArrow(canvas, cx - spacing, cy, arrowSize, angles[0], paint)
                drawArrow(canvas, cx + spacing, cy, arrowSize, angles[1], paint)
            }
        }
    }

    /** Lane bitmask -> arrow angles (degrees, 0 = pointing right, 90 = down). */
    private fun directionAngles(mask: Int): List<Float> {
        val result = mutableListOf<Float>()
        if (mask and 0x01 != 0) result += -90f          // straight (up)
        if (mask and 0x02 != 0) result += 180f          // left
        if (mask and 0x04 != 0) result += 0f            // right
        if (mask and 0x08 != 0) result += -135f         // slight left
        if (mask and 0x10 != 0) result += -45f          // slight right
        if (mask and 0x20 != 0) result += -160f         // sharp left
        if (mask and 0x40 != 0) result += -20f          // sharp right
        if (mask and 0x80 != 0) result += 90f           // U-turn (down)
        return result.take(2)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, size: Float, angleDeg: Float, paint: Paint) {
        canvas.save()
        canvas.rotate(angleDeg, cx, cy)
        val shaft = size * 0.55f
        val head = size * 0.45f
        canvas.drawLine(cx, cy + shaft, cx, cy - head, paint)
        val wing = size * 0.38f
        canvas.drawLine(cx, cy - head, cx - wing, cy - head + wing * 0.9f, paint)
        canvas.drawLine(cx, cy - head, cx + wing, cy - head + wing * 0.9f, paint)
        canvas.restore()
    }
}
