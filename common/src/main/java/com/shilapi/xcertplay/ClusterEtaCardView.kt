package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * ETA card drawn by DiPlay at the bottom-left of the dashboard, mirroring the stock
 * navigation arrival strip ("21:40到达 · 11分钟 · 8.4公里").
 *
 * Shown only in nav-only mode (the host controls visibility via the presentation's
 * mode). The data is the same iAP2 route-guidance payload that feeds the turn card:
 * arrivalEpochSeconds / remainingSeconds / remainingMeters from 0x5201.
 *
 * Visual language follows the turn card: dark glass capsule, hairline stroke, white
 * arrival line on top, dimmer remaining-time/remaining-distance line underneath.
 */
internal class ClusterEtaCardView(context: Context) : View(context) {
    private var guidance: ClusterTurnGuidance? = null

    private val arrivalPaint = GlassCardStyle.labelPaint(0f, 255, true)
    private val subPaint = GlassCardStyle.labelPaint(0f, 200, false)
    private val rect = RectF()
    private val textBackstop = RectF()

    // Per-frame cost killers: the ETA card is redrawn once per second (remaining time ticks),
    // so the position is cached and text is only re-measured when the string actually changed.
    // SharedPreferences is never touched on the hot draw path.
    private var posX = Float.NaN
    private var posY = Float.NaN
    private var lastArrivalLine = ""
    private var lastArrivalSize = 0f
    private var lastSubLine = ""
    private var lastSubSize = 0f
    private var wArr = 0f
    private var wSub = 0f
    private val clockFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** External position change (settings Apply) — drop the cached position so the next draw reloads it. */
    fun notifyPositionChanged() {
        posX = Float.NaN
        posY = Float.NaN
        invalidate()
    }

    fun setGuidance(next: ClusterTurnGuidance?) {
        guidance = next
        val visible = next != null && hasEta(next)
        val wasVisible = visibility == VISIBLE
        visibility = if (visible) VISIBLE else GONE
        // bringToFront() triggers a layout pass, so only raise the layer on the
        // first show — the ETA card ticks every second and must not requestLayout each time.
        if (visible && !wasVisible) bringToFront()
        invalidate()
    }

    private fun hasEta(next: ClusterTurnGuidance): Boolean =
        next.arrivalEpochSeconds != null || next.remainingSeconds != null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val next = guidance ?: return
        val arrival = next.arrivalEpochSeconds
        val remainingSec = next.remainingSeconds
        val remainingM = next.remainingMeters
        if (arrival == null && remainingSec == null) return

        val unit = minOf(width, height).coerceAtLeast(1)
        val padX = unit * 0.030f
        val padY = unit * 0.026f
        arrivalPaint.textSize = unit * 0.050f
        subPaint.textSize = unit * 0.034f

        val arrivalLine: String = if (arrival != null) {
            context.getString(R.string.cluster_eta_arrival, clockTime(arrival))
        } else {
            context.getString(R.string.cluster_eta_on_the_way)
        }
        val subLine = buildString {
            if (remainingSec != null) append(remainingLabel(remainingSec))
            if (remainingM != null) {
                if (isNotEmpty()) append(context.getString(R.string.cluster_eta_sep))
                append(distanceLabel(remainingM))
            }
        }

        // Cached measurement: the strings change every second (remaining time ticks), but the
        // width of a given string is stable, so re-measure only on an actual string change.
        if (arrivalLine != lastArrivalLine || arrivalPaint.textSize != lastArrivalSize) {
            lastArrivalLine = arrivalLine
            lastArrivalSize = arrivalPaint.textSize
            wArr = arrivalPaint.measureText(arrivalLine)
        }
        if (subLine.isNotEmpty() && (subLine != lastSubLine || subPaint.textSize != lastSubSize)) {
            lastSubLine = subLine
            lastSubSize = subPaint.textSize
            wSub = subPaint.measureText(subLine)
        }
        // A sub-line that disappeared must not keep its stale measured width (card would stay too wide).
        if (subLine.isEmpty()) wSub = 0f
        val cardW = maxOf(wArr, wSub) + padX * 2f
        val lineGap = unit * 0.012f
        val cardH = padY * 2f + arrivalPaint.textSize + lineGap + subPaint.textSize
        // Top-left anchored: absolute (x, y) relative to the full dashboard panel
        // (1920x720 canvas == display). Y is measured from the very top of the screen.
        // Position is cached — refreshed by notifyPositionChanged() on settings Apply.
        var left = posX
        var top = posY
        if (left.isNaN()) {
            left = AirPlayPersistence.loadClusterCardX(context, "eta").toFloat()
            top = AirPlayPersistence.loadClusterCardY(context, "eta").toFloat()
            posX = left
            posY = top
        }
        // Overflow correction: shift back inside and persist the corrected position. The
        // cache is updated too, so the correction persists exactly once per change.
        val clampedLeft = left.coerceIn(0f, (width - cardW).coerceAtLeast(0f))
        val clampedTop = top.coerceIn(0f, (height - cardH).coerceAtLeast(0f))
        if (clampedLeft != left || clampedTop != top) {
            AirPlayPersistence.saveClusterCardXSilent(context, "eta", clampedLeft.toInt())
            AirPlayPersistence.saveClusterCardYSilent(context, "eta", clampedTop.toInt())
            left = clampedLeft
            top = clampedTop
            posX = left
            posY = top
        }
        rect.set(left, top, left + cardW, top + cardH)
        val radius = cardH * 0.28f
        GlassCardStyle.drawCapsule(canvas, rect, radius)
        textBackstop.set(left, top, left + cardW, top + cardH)
        GlassCardStyle.drawTextBackstop(canvas, textBackstop, radius)

        // Left-aligned, clamped so a small measure-vs-render font difference can never
        // push the line past the glass background.
        val textLeft = (left + padX).coerceAtMost(left + cardW - padX * 0.5f - wArr)
        canvas.drawText(arrivalLine, textLeft, top + padY - arrivalPaint.ascent(), arrivalPaint)
        if (subLine.isNotEmpty()) {
            val subLeft = (left + padX).coerceAtMost(left + cardW - padX * 0.5f - wSub)
            canvas.drawText(subLine, subLeft, top + padY + arrivalPaint.textSize + lineGap - subPaint.ascent(), subPaint)
        }
    }

    private fun clockTime(epochSeconds: Long): String =
        clockFormatter.format(epochSeconds * 1000L)

    private fun remainingLabel(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) {
            if (m > 0) context.getString(R.string.cluster_eta_hours_minutes, h, m)
            else context.getString(R.string.cluster_eta_hours, h)
        } else {
            context.getString(R.string.cluster_eta_minutes, m.coerceAtLeast(1))
        }
    }

    private fun distanceLabel(meters: Long): String =
        if (meters >= 1000) {
            context.getString(R.string.cluster_eta_km, meters / 100 / 10f)
        } else {
            context.getString(R.string.cluster_eta_m, meters)
        }
}
