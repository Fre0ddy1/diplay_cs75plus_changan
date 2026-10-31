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
 * The block always reserves three lines, artist or not, so the capsule height is stable.
 *
 * Alignment follows the cover: side by side, the three-line block is vertically centred against
 * the cover; stacked, every line is centred horizontally. The text block is a fixed size rather
 * than the measured length of the current song: [maxChars] Chinese characters wide, even when the
 * title or artist is shorter. That keeps the capsule - and with it the album cover - in exactly the
 * same place from one song to the next, which is what the driver needs to line the card up once.
 * A longer title wraps into slot 2 and a longer artist is hard-cut, both without an ellipsis.
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

        val (line1, line2) = splitTitle(next.title)
        val line3 = cut(next.artist)

        val padH = lineH * 0.72f
        val padV = lineH * 0.34f

        val art = artwork?.takeIf { showArtwork && !it.isRecycled }
        val artSize = if (art != null) lineH * 3f * (artworkScale / 100f) else 0f
        val gap = if (art != null) lineH * 0.3f else 0f
        val sideBySide = artworkSide == 2 || artworkSide == 3

        // The text block is one fixed size: [maxChars] Chinese characters wide even when the current
        // title and artist are shorter. Measuring the actual words instead let the capsule - and the
        // cover pinned to its edge - change width with every song, so nothing could be lined up.
        // The width is capped by what the panel leaves; a big cover or a huge character count then
        // shrinks the font rather than letting the capsule run off the screen.
        val room = width - padH * 2f - (if (sideBySide) artSize + gap else 0f)
        val available = room.coerceAtLeast(lineH)
        var textW = fixedTextWidth()
        if (textW > available) {
            val fit = available / textW
            textPaint.textSize *= fit
            artistPaint.textSize *= fit
            textW = fixedTextWidth()
        }

        // The card is sized for three lines even when the current song has no artist: the driver
        // wants one stable block, not a card that changes height with the metadata.
        val textH = lineH * 3f

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
        // Overflow correction, same rule as every other drawn card (field request 2026-10-25):
        // shift back inside and persist the corrected position silently, so the next read is
        // already inside the panel instead of clamping again on every frame.
        if (left != posX || top != posY) {
            AirPlayPersistence.saveClusterCardXSilent(context, CARD, left.toInt())
            AirPlayPersistence.saveClusterCardYSilent(context, CARD, top.toInt())
            posX = left
            posY = top
        }
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
                        addRect(artRect, Path.Direction.CW)
                    },
                )
            }
            canvas.drawBitmap(art, null, artRect, artworkPaint)
            canvas.restoreToCount(saved)
        }

        // Stacked layouts centre every line inside the fixed-width block, so a short title sits
        // under the middle of the cover instead of hanging off to one side. Side-by-side layouts
        // keep the lines left-aligned and move the whole block to the cover's vertical centre.
        val centred = !sideBySide
        textPaint.textAlign = if (centred) Paint.Align.CENTER else Paint.Align.LEFT
        artistPaint.textAlign = textPaint.textAlign
        val textX = if (centred) textLeft + textW / 2f else textLeft

        // Slot 1 (and slot 2 when the name is long) hold the song name; slot 3 holds the artist.
        // A baseline is the slot's vertical centre plus half the font box: writing it as
        // slotTop - (ascent + descent) / 2 centres the glyphs on the slot's TOP edge and lifts the
        // whole block by half a line, which is what pinned the title to the capsule's upper edge.
        val m1 = textPaint.fontMetrics
        val baseline1 = textTop + lineH * 0.5f - (m1.ascent + m1.descent) / 2f
        canvas.drawText(line1, textX, baseline1, textPaint)
        if (line2.isNotEmpty()) {
            val baseline2 = textTop + lineH * 1.5f - (m1.ascent + m1.descent) / 2f
            canvas.drawText(line2, textX, baseline2, textPaint)
        }
        if (line3.isNotEmpty()) {
            val m3 = artistPaint.fontMetrics
            val baseline3 = textTop + lineH * 2.5f - (m3.ascent + m3.descent) / 2f
            canvas.drawText(line3, textX, baseline3, artistPaint)
        }
    }

    /** One line's width: the configured character count at full-width (Chinese) advances. */
    private fun fixedTextWidth(): Float =
        textPaint.measureText(REFERENCE_CHAR) * maxChars.coerceAtLeast(1)

    /**
     * Splits a song name across slots 1 and 2.
     *
     * The configured character count is the width of one slot, so a longer name simply continues on
     * the second line rather than being cut off; anything past two slots is dropped. No ellipsis.
     */
    private fun splitTitle(title: String): Pair<String, String> {
        if (maxChars <= 0 || title.length <= maxChars) return title to ""
        val first = title.substring(0, maxChars)
        val rest = title.substring(maxChars)
        val second = if (rest.length <= maxChars) rest else rest.substring(0, maxChars)
        return first to second
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

        /** The reference glyph for one "character": all full-width CJK glyphs share its advance. */
        const val REFERENCE_CHAR = "中"
    }
}
