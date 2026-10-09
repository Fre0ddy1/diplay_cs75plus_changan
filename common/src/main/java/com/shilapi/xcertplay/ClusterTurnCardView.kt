package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * Instruction card drawn by DiPlay on top of the dashboard map.
 *
 * Visual language follows Apple's turn banners: a dark glass capsule with a hairline stroke,
 * the maneuver glyph in a soft chip on the left, distance and road stacked on the right.
 * Maneuver glyphs are Material Symbols (Apache 2.0), tinted the system blue; the roundabout
 * exit number sits in a small badge on the glyph.
 */
internal class ClusterTurnCardView(context: Context) : View(context) {
    private var guidance: ClusterTurnGuidance? = null
    private var xPx = AirPlayPersistence.DEFAULT_CLUSTER_TURN_CARD_X_PX
    private var yPx = AirPlayPersistence.DEFAULT_CLUSTER_TURN_CARD_Y_PX
    private var size = CarPlayClusterDisplay.OverlaySize.MEDIUM

    private val accent = Color.rgb(10, 132, 255)
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(232, 28, 28, 30) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(38, 255, 255, 255) }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(179, 199, 199, 204); typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val rect = RectF()
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

    fun setLayout(xPx: Int, yPx: Int, size: CarPlayClusterDisplay.OverlaySize) {
        this.xPx = xPx.coerceIn(0, 1920)
        this.yPx = yPx.coerceIn(0, 720)
        this.size = size
        invalidate()
    }

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
        val heightFraction = when (size) {
            CarPlayClusterDisplay.OverlaySize.SMALL -> 0.22f
            CarPlayClusterDisplay.OverlaySize.MEDIUM -> 0.28f
            CarPlayClusterDisplay.OverlaySize.LARGE -> 0.34f
        }
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
        // Width adapts to the text (no ellipsis — text must stay fully visible); if the
        // adapted card would leave the panel, shift it left and persist the correction.
        val w = (textLeft + textW + h * 0.10f).coerceAtMost(panelW)
        var left = xPx.toFloat().coerceIn(0f, (panelW - w).coerceAtLeast(0f))
        var top = yPx.toFloat().coerceIn(0f, (panelH - h).coerceAtLeast(0f))
        if (left != xPx.toFloat() || top != yPx.toFloat()) {
            AirPlayPersistence.saveClusterTurnCardXSilent(context, left.toInt())
            AirPlayPersistence.saveClusterTurnCardYSilent(context, top.toInt())
            xPx = left.toInt()
            yPx = top.toInt()
        }

        rect.set(left, top, left + w, top + h)
        canvas.drawRoundRect(rect, radius, radius, glassPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        val chipTop = top + (h - chip) / 2f
        rect.set(chipLeft + left, chipTop, chipLeft + left + chip, chipTop + chip)
        canvas.drawRoundRect(rect, chip * 0.26f, chip * 0.26f, chipPaint)

        val exit = next.roundaboutExit.takeIf { it in 1..9 }
        drawGlyph(canvas, next, chipLeft + left, chipTop, chip, exit)

        val textX = left + textLeft
        canvas.drawText(distanceText, textX, top + h * 0.44f, distancePaint)
        if (roadText.isNotEmpty()) {
            canvas.drawText(roadText, textX, top + h * 0.72f, roadPaint)
        }
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
