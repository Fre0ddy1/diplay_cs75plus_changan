package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.os.SystemClock

/**
 * The album art CarPlay sends with the current now-playing item, as its own dashboard card.
 *
 * It used to sit inside the song card, which meant its position was whatever the text capsule
 * happened to be - the driver could only nudge it within that capsule, never place it freely. As a
 * separate layer it gets the same position/size/show controls as every other card.
 *
 * The bitmap belongs to CarPlayMediaKeys, which recycles superseded transfers; this view only ever
 * draws it and checks isRecycled first, because a recycled bitmap throws on draw.
 */
internal class ClusterAlbumArtView(context: Context) : View(context) {
    private val rect = RectF()
    private val clip = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private var artwork: Bitmap? = null
    private var posX = Float.NaN
    private var posY = Float.NaN
    private var overlayScale = Float.NaN
    private var settingsAtMs = 0L
    private var visible = true

    fun setArtwork(next: Bitmap?) {
        if (artwork === next) return
        artwork = next
        visibility = if (drawable() == null) GONE else VISIBLE
        invalidate()
    }

    /** External position or size change (settings Apply) - drop the caches so the next draw reloads. */
    fun notifyPositionChanged() {
        posX = Float.NaN
        posY = Float.NaN
        overlayScale = Float.NaN
        settingsAtMs = 0L
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
    }

    private fun drawable(): Bitmap? {
        val next = artwork ?: return null
        if (next.isRecycled) return null
        return next.takeIf { visible }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (posX.isNaN()) {
            posX = AirPlayPersistence.loadClusterCardX(context, CARD).toFloat()
            posY = AirPlayPersistence.loadClusterCardY(context, CARD).toFloat()
        }
        if (overlayScale.isNaN()) {
            overlayScale = AirPlayPersistence.clusterCardScale(context, CARD)
        }
        val now = SystemClock.elapsedRealtime()
        if (settingsAtMs == 0L || now - settingsAtMs >= SETTINGS_REFRESH_MS) {
            settingsAtMs = now
            visible = AirPlayPersistence.loadClusterCardVisible(context, CARD)
        }
        val art = drawable() ?: return
        // Square, sized like the other cards: a fraction of the shorter panel edge.
        val unit = minOf(width, height).coerceAtLeast(1).toFloat() * overlayScale
        val size = unit * 0.28f
        val left = posX.coerceIn(0f, (width - size).coerceAtLeast(0f))
        val top = posY.coerceIn(0f, (height - size).coerceAtLeast(0f))
        rect.set(left, top, left + size, top + size)
        // Rounded like the glass capsules it lives next to, so a cover does not read as a stray
        // rectangle pasted over the map.
        clip.set(rect)
        val saved = canvas.save()
        runCatching {
            canvas.clipPath(
                Path().apply { addRoundRect(clip, size * 0.16f, size * 0.16f, Path.Direction.CW) },
            )
        }
        canvas.drawBitmap(art, null, rect, paint)
        canvas.restoreToCount(saved)
    }

    private companion object {
        const val CARD = "album"
        const val SETTINGS_REFRESH_MS = 2_000L
    }
}
