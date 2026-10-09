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
 * Road-name card drawn by DiPlay at the bottom centre of the dashboard, nav-only mode.
 * Shows the road the driver turns onto (primary) and the current road (secondary).
 * Data comes from 0x5201 current-road + 0x5202 after-road (Apple CPManeuver).
 */
internal class ClusterRoadNameView(context: Context) : View(context) {
    private var guidance: ClusterTurnGuidance? = null

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(232, 28, 28, 30) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val primaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val secondaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(179, 199, 199, 204); typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val rect = RectF()

    // Per-frame cost killers: the card is redrawn on every guidance/frame change, so the
    // position and measured text widths are cached and only recomputed when the underlying
    // data changes. SharedPreferences is never touched on the hot draw path.
    private var posX = Float.NaN
    private var posY = Float.NaN
    private var lastPrimary = ""
    private var lastPrimarySize = 0f
    private var lastSecondary = ""
    private var lastSecondarySize = 0f
    private var wPrimary = 0f
    private var wSecondary = 0f

    /** External position change (settings Apply) — drop the cached position so the next draw reloads it. */
    fun notifyPositionChanged() {
        posX = Float.NaN
        posY = Float.NaN
        invalidate()
    }

    fun setGuidance(next: ClusterTurnGuidance?) {
        guidance = next
        val visible = next?.let { it.road.isNotEmpty() || it.currentRoad.isNotEmpty() } ?: false
        val wasVisible = visibility == VISIBLE
        visibility = if (visible) VISIBLE else GONE
        if (visible) {
            // Invalidate the text-width cache: measureText must rerun once the text changes.
            lastPrimary = ""
            lastSecondary = ""
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
        val next = guidance ?: return
        val unit = minOf(width, height).coerceAtLeast(1)
        val margin = unit * 0.030f
        val radius = unit * 0.039f
        // Card size adapts to its text: width follows the longer of the two road names,
        // so the glass background always wraps the text ("文字自适应卡片背景").
        val hasCurrent = next.currentRoad.isNotEmpty()
        val primary = next.road
        primaryPaint.textSize = if (hasCurrent) unit * 0.052f else unit * 0.062f
        secondaryPaint.textSize = unit * 0.034f
        val padH = unit * 0.14f
        // Cached measurement: only re-measure when the string or its size changed.
        if (primary.isNotEmpty() && (primary != lastPrimary || primaryPaint.textSize != lastPrimarySize)) {
            lastPrimary = primary
            lastPrimarySize = primaryPaint.textSize
            wPrimary = primaryPaint.measureText(primary)
        }
        if (hasCurrent && (next.currentRoad != lastSecondary || secondaryPaint.textSize != lastSecondarySize)) {
            lastSecondary = next.currentRoad
            lastSecondarySize = secondaryPaint.textSize
            wSecondary = secondaryPaint.measureText(next.currentRoad)
        }
        // A line that disappeared must not keep its stale measured width (card would stay too wide).
        if (!hasCurrent) wSecondary = 0f
        if (primary.isEmpty()) wPrimary = 0f
        val cardW = (maxOf(wPrimary, wSecondary) + padH * 2f).coerceAtLeast(unit * 0.24f)
        val cardH = if (hasCurrent) unit * 0.15f else unit * 0.12f
        // Top-left anchored: absolute (x, y) relative to the full dashboard panel
        // (1920x720 canvas == display). Y is measured from the very top of the screen.
        // Position is cached — the hot path never reads SharedPreferences. It is refreshed
        // by notifyPositionChanged() when the settings page applies new coordinates.
        var left = posX
        var top = posY
        if (left.isNaN()) {
            left = AirPlayPersistence.loadClusterCardX(context, "road").toFloat()
            top = AirPlayPersistence.loadClusterCardY(context, "road").toFloat()
            posX = left
            posY = top
        }
        // Overflow correction: if the adapted card would leave the panel, shift it back
        // inside and persist the corrected position so the next draw uses the safe value.
        // The cache is updated too, so the correction persists exactly once per change.
        val clampedLeft = left.coerceIn(0f, (width - cardW).coerceAtLeast(0f))
        val clampedTop = top.coerceIn(0f, (height - cardH).coerceAtLeast(0f))
        if (clampedLeft != left || clampedTop != top) {
            AirPlayPersistence.saveClusterCardXSilent(context, "road", clampedLeft.toInt())
            AirPlayPersistence.saveClusterCardYSilent(context, "road", clampedTop.toInt())
            left = clampedLeft
            top = clampedTop
            posX = left
            posY = top
        }

        rect.set(left, top, left + cardW, top + cardH)
        canvas.drawRoundRect(rect, radius, radius, glassPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        val textTop = if (hasCurrent) top + cardH * 0.40f else top + cardH * 0.64f
        // Full text, never ellipsized: the card width adapts to the text, and the
        // overflow correction above shifts the card left when the adapted width would
        // leave the panel, so text stays fully visible. The centre X is clamped so a
        // small measure-vs-render font difference can never push text past the glass.
        // NOTE: guard the upper bound with coerceAtLeast(lower) — a tiny float rounding
        // difference (e.g. max=1500.7999 < min=1500.8) makes coerceIn throw and crashes
        // onDraw mid-navigation; see diplay.log 10-09.
        if (primary.isNotEmpty()) {
            val lower = left + padH
            val upper = (left + cardW - padH - wPrimary).coerceAtLeast(lower)
            val textX = (left + cardW / 2f - wPrimary / 2f).coerceIn(lower, upper)
            canvas.drawText(primary, textX, textTop, primaryPaint)
        }
        if (hasCurrent) {
            val lower = left + padH
            val upper = (left + cardW - padH - wSecondary).coerceAtLeast(lower)
            val textX = (left + cardW / 2f - wSecondary / 2f).coerceIn(lower, upper)
            canvas.drawText(next.currentRoad, textX, top + cardH * 0.74f, secondaryPaint)
        }
    }
}
