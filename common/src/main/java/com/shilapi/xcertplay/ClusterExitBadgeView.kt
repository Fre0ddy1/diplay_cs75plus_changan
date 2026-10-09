package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * Highway exit badge drawn by DiPlay at the top-right of the dashboard, nav-only mode.
 * Shows the exit label (Apple CPManeuver.highwayExitLabel) as a small capsule. The iAP2
 * TLV for the label is undocumented, so the decoder is best-effort and the raw trace stays
 * in the log; the badge simply stays hidden while no label is present.
 */
internal class ClusterExitBadgeView(context: Context) : View(context) {
    private var guidance: ClusterTurnGuidance? = null

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(232, 28, 28, 30) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val rect = RectF()

    // Per-frame cost killers: cached position and measured label width — the hot draw path
    // never touches SharedPreferences or re-measures unchanged text.
    private var posX = Float.NaN
    private var posY = Float.NaN
    private var lastLabel = ""
    private var lastSize = 0f
    private var textW = 0f

    /** External position change (settings Apply) — drop the cached position so the next draw reloads it. */
    fun notifyPositionChanged() {
        posX = Float.NaN
        posY = Float.NaN
        invalidate()
    }

    fun setGuidance(next: ClusterTurnGuidance?) {
        guidance = next
        // The 0x5202 exit-label decode is a best-effort guess on unknown TLV fields; on the
        // UNI-V the guessed field frequently duplicates the after-road name (same 19-byte
        // string, field report 10-09). Show the badge only when the label is present AND
        // differs from what the road-name card is already showing.
        val road = next?.road?.ifEmpty { next.currentRoad }.orEmpty()
        val label = next?.exitLabel.orEmpty()
        val meaningful = label.isNotEmpty() && label != road
        val wasVisible = visibility == VISIBLE
        visibility = if (meaningful) VISIBLE else GONE
        if (meaningful) {
            // bringToFront() triggers a layout pass, so only raise the layer on the
            // first show — repeated guidance updates must not requestLayout every frame.
            if (!wasVisible) bringToFront()
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val label = guidance?.exitLabel ?: return
        if (label.isEmpty()) return
        val unit = minOf(width, height).coerceAtLeast(1)
        val cardH = unit * 0.11f
        val radius = cardH * 0.28f
        textPaint.textSize = cardH * 0.42f
        // Cached measurement: only re-measure when the label or its size changed.
        if (label != lastLabel || textPaint.textSize != lastSize) {
            lastLabel = label
            lastSize = textPaint.textSize
            textW = textPaint.measureText(label)
        }
        val padH = cardH * 0.34f
        val cardW = textW + padH * 2f
        // Top-left anchored: absolute (x, y) relative to the full dashboard panel
        // (1920x720 canvas == display). Y is measured from the very top of the screen.
        // Position is cached — refreshed by notifyPositionChanged() on settings Apply.
        var left = posX
        var top = posY
        if (left.isNaN()) {
            left = AirPlayPersistence.loadClusterCardX(context, "exit").toFloat()
            top = AirPlayPersistence.loadClusterCardY(context, "exit").toFloat()
            posX = left
            posY = top
        }
        // Overflow correction: shift back inside and persist the corrected position. The
        // cache is updated too, so the correction persists exactly once per change.
        val clampedLeft = left.coerceIn(0f, (width - cardW).coerceAtLeast(0f))
        val clampedTop = top.coerceIn(0f, (height - cardH).coerceAtLeast(0f))
        if (clampedLeft != left || clampedTop != top) {
            AirPlayPersistence.saveClusterCardXSilent(context, "exit", clampedLeft.toInt())
            AirPlayPersistence.saveClusterCardYSilent(context, "exit", clampedTop.toInt())
            left = clampedLeft
            top = clampedTop
            posX = left
            posY = top
        }

        rect.set(left, top, left + cardW, top + cardH)
        canvas.drawRoundRect(rect, radius, radius, glassPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
        // Centre the label, but clamp the text start so it can never spill past the capsule
        // background: CJK fallback fonts can render a few px wider than measureText reports,
        // which pushed "请行驶到南门" past the right edge on the UNI-V dashboard.
        val minTextX = left + padH * 0.5f
        val maxTextX = (left + cardW - padH * 0.5f - textW).coerceAtLeast(minTextX)
        val textX = (left + cardW / 2f - textW / 2f).coerceIn(minTextX, maxTextX)
        canvas.drawText(label, textX, top + cardH * 0.68f, textPaint)
    }
}
