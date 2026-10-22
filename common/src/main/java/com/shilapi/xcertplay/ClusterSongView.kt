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
 * The lyrics card: song name, lyric line and artist in one capsule, with the album cover on a side
 * the driver picks.
 *
 * Three text slots, always in the same places so the block is stable from song to song:
 *   slot 1  song name
 *   slot 2  lyric line - CarPlay's iAP2 NowPlayingUpdate (0x5001) carries only title (1) and
 *           artist (12) and has no lyric attribute at all, so this slot stays empty for now
 *   slot 3  artist, always
 * A song with no artist collapses to a single line in slot 1 rather than leaving two blank rows.
 *
 * Alignment follows the cover: side by side, the text block is vertically centred against the
 * cover; stacked, it is centred horizontally. Each line is hard-cut at the configured character
 * count with no ellipsis, and the whole block shrinks to fit whatever space the cover leaves.
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
    private var lastLine3 = ""
    private var lastLineH = 0f
    private var lastFontScale = 0
    private var measured1 = 0f
    private var measured3 = 0f
    private var maxChars = 0
    private var settingsAtMs = 0L
    private var cardAlpha = 100
    private var showArtwork = true
    private var artworkSide = 3
    private var artworkScale = 100
    private var fontScale = 100

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
        val scale = fontScale / 100f
        textPaint.textSize = lineH * 0.62f * scale
        artistPaint.textSize = lineH * 0.5f * scale

        val line1 = cut(next.title)
        val line3 = cut(next.artist)
        if (line1 != lastLine1 || line3 != lastLine3 || lineH != lastLineH || fontScale != lastFontScale) {
            lastLine1 = line1
            lastLine3 = line3
            lastLineH = lineH
            lastFontScale = fontScale
            measured1 = textPaint.measureText(line1)
            measured3 = if (line3.isEmpty()) 0f else artistPaint.measureText(line3)
        }

        val padH = lineH * 0.72f
        val padV = lineH * 0.34f

        val art = artwork?.takeIf { showArtwork && !it.isRecycled }
        val artSize = if (art != null) lineH * 3f * (artworkScale / 100f) else 0f
        val gap = if (art != null) lineH * 0.3f else 0f
        val sideBySide = artworkSide == 2 || artworkSide == 3

        // Text size follows the space the cover leaves: the panel minus the cover and the padding is
        // what the words may occupy, so a large cover gets smaller text instead of an oversized card.
        val available = (width - artSize - gap - padH * 2f).coerceAtLeast(lineH * 3f)
        var textW = maxOf(measured1, measured3)
        if (textW > available && textW > 0f) {
            val fit = available / textW
            textPaint.textSize *= fit
            artistPaint.textSize *= fit
            measured1 = textPaint.measureText(line1)
            measured3 = if (line3.isEmpty()) 0f else artistPaint.measureText(line3)
            textW = maxOf(measured1, measured3)
        }

        val lines = if (line3.isEmpty()) 1 else 3
        val textH = lineH * lines

        val w: Float
        val h: Float
        if (sideBySide) {
            w = artSize + gap + textW + padH * 2f
            h = maxOf(artSize, textH) + padV * 2f
        } else {
            w = maxOf(textW, artSize.coerceAtLeast(lineH)) + padH * 2f
            h = artSize + gap + textH + padV * 2f
        }

        val left = posX.coerceIn(0f, (width - w).coerceAtLeast(0f))
        val top = posY.coerceIn(0f, (height - h).coerceAtLeast(0f))
        rect.set(left, top, left + w, top + h)

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
            0 -> { // cover above: text centred horizontally
                artLeft = contentLeft + (contentW - artSize) / 2f
                artTop = contentTop
                textLeft = contentLeft + (contentW - textW) / 2f
                textTop = contentTop + artSize + gap
            }
            1 -> { // cover below: text centred horizontally
                textLeft = contentLeft + (contentW - textW) / 2f
                textTop = contentTop
                artLeft = contentLeft + (contentW - artSize) / 2f
                artTop = contentTop + textH + gap
            }
            2 -> { // cover left: text centred vertically against it
                artLeft = contentLeft
                artTop = contentTop + (contentH - artSize) / 2f
                textLeft = contentLeft + artSize + gap
                textTop = contentTop + (contentH - textH) / 2f
            }
            else -> { // cover right: text centred vertically against it
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

        // Slot 1 always holds the song name; slot 3 holds the artist when there is one. Slot 2 is
        // left for a lyric line the protocol does not currently provide.
        val m1 = textPaint.fontMetrics
        val baseline1 = textTop - (m1.ascent + m1.descent) / 2f
        canvas.drawText(line1, textLeft, baseline1, textPaint)
        if (line3.isNotEmpty()) {
            val m3 = artistPaint.fontMetrics
            val baseline3 = textTop + lineH * 2f - (m3.ascent + m3.descent) / 2f
            canvas.drawText(line3, textLeft, baseline3, artistPaint)
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
        fontScale = AirPlayPersistence.loadClusterSongFontScale(context)
    }

    private companion object {
        const val CARD = "song"
        const val SETTINGS_REFRESH_MS = 2_000L
    }
}
