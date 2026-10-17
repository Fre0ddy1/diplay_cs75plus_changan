package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import com.shilapi.xcertplay.hud.ClusterSong

/**
 * The song now playing, as a slim glass capsule on the dashboard.
 *
 * Title and artist only. CarPlay's iAP2 NowPlayingUpdate (0x5001) carries MediaItemAttributes -
 * title (1) and artist (12) - and the protocol has no lyric attribute at all: the lyrics a phone
 * displays are drawn by the music app itself as pixels on the centre screen, so there is no text
 * stream to forward. The field logs show exactly that and nothing more - 0x5001 frames plus artwork
 * transfers, no lyric field.
 *
 * Positioned and scaled exactly like the guidance cards (absolute canvas coordinates plus a
 * percentage), so it takes the same two controls instead of being pinned to one spot. Unlike those
 * cards it is not part of the nav-only group: the whole-screen mirror can carry a song card too, and
 * ClusterMapPresentation.applyCardsVisibility deliberately leaves this view alone.
 */
internal class ClusterSongView(context: Context) : View(context) {
    private val rect = RectF()
    private val textPaint = GlassCardStyle.labelPaint(0f, 235, true)
    private var song: ClusterSong? = null
    private var posX = Float.NaN
    private var posY = Float.NaN
    private var overlayScale = Float.NaN
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
        // Paused music keeps its title up: the card is an information overlay, not a play indicator.
        invalidate()
    }

    /** External position or size change (settings Apply) - drop the caches so the next draw reloads. */
    fun notifyPositionChanged() {
        posX = Float.NaN
        posY = Float.NaN
        overlayScale = Float.NaN
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
        if (posX.isNaN()) {
            posX = AirPlayPersistence.loadClusterCardX(context, CARD).toFloat()
            posY = AirPlayPersistence.loadClusterCardY(context, CARD).toFloat()
        }
        if (overlayScale.isNaN()) {
            overlayScale = AirPlayPersistence.clusterCardScale(context, CARD)
        }
        val unit = minOf(width, height).coerceAtLeast(1).toFloat() * overlayScale
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
        val w = measured + padH * 2f
        val left = posX.coerceIn(0f, (width - w).coerceAtLeast(0f))
        val top = posY.coerceIn(0f, (height - h).coerceAtLeast(0f))
        rect.set(left, top, left + w, top + h)
        GlassCardStyle.drawCapsule(canvas, rect, radius)
        val metrics = textPaint.fontMetrics
        val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, rect.left + padH, baseline, textPaint)
    }

    private companion object {
        const val CARD = "song"
    }
}
