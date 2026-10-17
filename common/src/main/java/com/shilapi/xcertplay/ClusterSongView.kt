package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import com.shilapi.xcertplay.hud.ClusterSong

/**
 * The song now playing, as a slim glass capsule centred at the top of the dashboard.
 *
 * Title and artist only. CarPlay's iAP2 NowPlayingUpdate (0x5001) carries MediaItemAttributes -
 * title (1) and artist (12) - and the protocol has no lyric attribute at all: the lyrics a phone
 * displays are drawn by the music app itself as pixels on the centre screen, so there is nothing
 * for us to project. Stated plainly rather than shipped as a card that silently never shows them.
 *
 * Placed at the top centre for now, per the request. Width follows the text so the glass always
 * wraps the label, and it is clamped to a share of the panel with a clip so a long title is cut
 * instead of running off the edge.
 */
internal class ClusterSongView(context: Context) : View(context) {
    private val rect = RectF()
    private val textPaint = GlassCardStyle.labelPaint(0f, 235, true)
    private var song: ClusterSong? = null
    private var lastText = ""
    private var lastSize = 0f
    private var measured = 0f

    fun setSong(next: ClusterSong?) {
        if (song == next) {
            visibility = if (next == null) GONE else VISIBLE
            return
        }
        song = next
        visibility = if (next == null) GONE else VISIBLE
        // Paused music keeps its title up: the card is an information overlay, not a play indicator,
        // and the driver reads it between turns. [ClusterSong.playing] is available if that changes.
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
        val next = song ?: return
        val panelW = width.toFloat()
        val unit = minOf(width, height).coerceAtLeast(1).toFloat()
        val h = unit * 0.095f
        val radius = h * 0.5f
        textPaint.textSize = h * 0.42f
        val label = next.text
        // Cached measurement: the card redraws on every guidance tick, and measureText on a stable
        // string is pure waste there.
        if (label != lastText || textPaint.textSize != lastSize) {
            lastText = label
            lastSize = textPaint.textSize
            measured = textPaint.measureText(label)
        }
        val padH = h * 0.55f
        val w = (measured + padH * 2f).coerceAtMost(panelW * 0.86f)
        val left = (panelW - w) / 2f
        val top = unit * 0.030f
        rect.set(left, top, left + w, top + h)
        GlassCardStyle.drawCapsule(canvas, rect, radius)
        val metrics = textPaint.fontMetrics
        val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.save()
        canvas.clipRect(rect.left + padH * 0.5f, rect.top, rect.right - padH * 0.5f, rect.bottom)
        canvas.drawText(label, rect.centerX() - measured / 2f, baseline, textPaint)
        canvas.restore()
    }
}
