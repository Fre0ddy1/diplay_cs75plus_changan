package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.shilapi.xcertplay.hud.ClusterSong

/**
 * The lyrics card: song name, artist and the album cover, in one capsule.
 *
 * Layout is driven by the artwork side: 0 above the text, 1 below, 2 left, 3 right. The text takes
 * the opposite side automatically, so picking where the cover sits also decides where the words go.
 * The two text lines are title and artist; CarPlay's iAP2 NowPlayingUpdate (0x5001) carries title
 * (1) and artist (12) and has no lyric attribute at all, so there is no third line to draw.
 *
 * Each line is cut to the configured character count with no ellipsis - the driver asked for the
 * text to simply stop, since "..." made long titles look like errors.
 *
 * Positioned and scaled like the guidance cards (absolute canvas coordinates plus a percentage).
 * It is not part of the nav-only group: the whole-screen mirror shows it too.
 */
internal class ClusterSongView(context: Context) : View(context) {
    private val rect = RectF()
    private val artRect = RectF()
    private val textPaint = GlassCardStyle.labelPaint(0f, 235, true)
    private val artistPaint = GlassCardStyle.labelPaint(0f, 190, false)
    private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private var song: ClusterSong? = null
    private var artwork: Bitmap? = null
    private var posX = Float.NaN
    private var posY = Float.NaN
    private var overlayScale = Float.NaN
    private var lastLine1 = ""
    private var lastLine2 = ""
    private var lastSize = 0f
    private var measured1 = 0f
    private var measured2 = 0f
    private var maxChars = 0
    private var settingsAtMs = 0L
    private var cardAlpha = 100
    private var showArtwork = true
    private var artworkSide = 3
    private var artworkScale = 90

    fun setSong(next: ClusterSong?) {
        val wanted = if (AirPlayPersistence.loadClusterCardVisible(context, CARD)) next else null
        if (song == wanted) {
            visibility = if (wanted == null) GONE else VISIBLE
            return
        }
        song = wanted
        visibility = if (wanted == null) GONE else VISIBLE
        invalidate()
    }

    /** The album art CarPlay sent for the current item. Owned by CarPlayMediaKeys; never recycled here. */
    fun setArtwork(next: Bitmap?) {
        if (artwork === next) return
        artwork = next
        invalidate()
    }

    /** External position, size or layout change - drop the caches so the next draw reloads. */
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
        refreshSettingsIfDue()

        val unit = minOf(width, height).coerceAtLeast(1).toFloat() * overlayScale
        val lineH = unit * 0.075f
        textPaint.textSize = lineH * 0.62f
        artistPaint.textSize = lineH * 0.5f

        val line1 = cut(next.title)
        val line2 = if (next.artist.isEmpty()) "" else cut(next.artist)
        if (line1 != lastLine1 || line2 != lastLine2 || textPaint.textSize != lastSize) {
            lastLine1 = line1
            lastLine2 = line2
            lastSize = textPaint.textSize
            measured1 = textPaint.measureText(line1)
            measured2 = if (line2.isEmpty()) 0f else artistPaint.measureText(line2)
        }

        val padH = lineH * 0.55f
        val padV = lineH * 0.28f
        val textW = maxOf(measured1, measured2)
        val textH = if (line2.isEmpty()) lineH else lineH * 2f

        val art = artwork?.takeIf { showArtwork && !it.isRecycled }
        val artSize = if (art != null) textH * (artworkScale / 100f) else 0f
        val gap = if (art != null) lineH * 0.3f else 0f

        // Total capsule size follows the chosen side; the text always takes the opposite side.
        val w: Float
        val h: Float
        if (artworkSide == 0 || artworkSide == 1) {
            w = maxOf(textW, artSize.coerceAtLeast(lineH)) + padH * 2f
            h = artSize + gap + textH + padV * 2f
        } else {
            w = artSize + gap + textW + padH * 2f
            h = maxOf(artSize, textH) + padV * 2f
        }

        val left = posX.coerceIn(0f, (width - w).coerceAtLeast(0f))
        val top = posY.coerceIn(0f, (height - h).coerceAtLeast(0f))
        rect.set(left, top, left + w, top + h)

        // Background strength: 100 % is opaque black, 0 % leaves only text and cover.
        val cardFill = Color.argb(
            (255 * cardAlpha.coerceIn(0, 100) / 100f).toInt().coerceIn(0, 255),
            0, 0, 0,
        )
        GlassCardStyle.drawCapsule(canvas, rect, h * 0.22f, cardFill)

        val contentLeft = rect.left + padH
        val contentTop = rect.top + padV
        val contentW = rect.width() - padH * 2f
        val contentH = rect.height() - padV * 2f

        val artLeft: Float
        val artTop: Float
        val textLeft: Float
        val textTop: Float
        when (artworkSide) {
            0 -> { // cover above, text below
                artLeft = contentLeft + (contentW - artSize) / 2f
                artTop = contentTop
                textLeft = contentLeft + (contentW - textW) / 2f
                textTop = contentTop + artSize + gap
            }
            1 -> { // cover below, text above
                textLeft = contentLeft + (contentW - textW) / 2f
                textTop = contentTop
                artLeft = contentLeft + (contentW - artSize) / 2f
                artTop = contentTop + textH + gap
            }
            2 -> { // cover left, text right
                artLeft = contentLeft
                artTop = contentTop + (contentH - artSize) / 2f
                textLeft = contentLeft + artSize + gap
                textTop = contentTop + (contentH - textH) / 2f
            }
            else -> { // cover right, text left
                textLeft = contentLeft
                textTop = contentTop + (contentH - textH) / 2f
                artLeft = contentLeft + contentW - artSize
                artTop = contentTop + (contentH - artSize) / 2f
            }
        }

        if (art != null) {
            artRect.set(artLeft, artTop, artLeft + artSize, artTop + artSize)
            val saved = canvas.save()
            runCatching {
                canvas.clipPath(
                    Path().apply {
                        addRoundRect(artRect, artSize * 0.14f, artSize * 0.14f, Path.Direction.CW)
                    },
                )
            }
            canvas.drawBitmap(art, null, artRect, artworkPaint)
            canvas.restoreToCount(saved)
        }

        val m1 = textPaint.fontMetrics
        val baseline1 = textTop - (m1.ascent + m1.descent) / 2f
        canvas.drawText(line1, textLeft, baseline1, textPaint)
        if (line2.isNotEmpty()) {
            val m2 = artistPaint.fontMetrics
            val baseline2 = textTop + lineH - (m2.ascent + m2.descent) / 2f
            canvas.drawText(line2, textLeft, baseline2, artistPaint)
        }
    }

    /** Hard cut at the configured character count: no ellipsis, by request. */
    private fun cut(text: String): String {
        if (maxChars <= 0 || text.length <= maxChars) return text
        return text.substring(0, maxChars)
    }

    private fun refreshSettingsIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (settingsAtMs != 0L && now - settingsAtMs < SETTINGS_REFRESH_MS) return
        settingsAtMs = now
        maxChars = AirPlayPersistence.loadClusterSongMaxChars(context)
        cardAlpha = AirPlayPersistence.loadClusterSongCardAlpha(context)
        showArtwork = AirPlayPersistence.loadClusterSongShowArtwork(context)
        artworkSide = AirPlayPersistence.loadClusterSongArtworkSide(context)
        artworkScale = AirPlayPersistence.loadClusterSongArtworkScale(context)
    }

    private companion object {
        const val CARD = "song"
        const val SETTINGS_REFRESH_MS = 2_000L
    }
}
