package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
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
    private val artRect = RectF()
    private val textPaint = GlassCardStyle.labelPaint(0f, 235, true)
    private val artworkPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private var song: ClusterSong? = null
    private var artwork: Bitmap? = null
    private var posX = Float.NaN
    private var posY = Float.NaN
    private var overlayScale = Float.NaN
    private var lastText = ""
    private var lastSize = 0f
    private var measured = 0f
    private var maxChars = 0
    private var maxCharsAtMs = 0L
    private var settingsAtMs = 0L
    private var artworkVisible = true
    private var artworkScale = 80
    private var artworkX = 0
    private var artworkY = 0
    private var cardAlpha = 100

    fun setSong(next: ClusterSong?) {
        // Obey the same show/hide switch as the guidance cards. This is read here rather than in
        // the presentation's visibility pass on purpose: the card must not join the nav-only group
        // (it also shows in the mirror), but it must still respect a driver who switched it off.
        val wanted = if (AirPlayPersistence.loadClusterCardVisible(context, CARD)) next else null
        if (song == wanted) {
            visibility = if (wanted == null) GONE else VISIBLE
            return
        }
        song = wanted
        visibility = if (wanted == null) GONE else VISIBLE
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
        refreshSettingsIfDue()
        val unit = minOf(width, height).coerceAtLeast(1).toFloat() * overlayScale
        val h = unit * 0.095f
        val radius = h * 0.5f
        textPaint.textSize = h * 0.42f
        val label = clipped(next.text)
        // Cached measurement: the card redraws on every guidance tick, and measureText on a stable
        // string is pure waste there.
        if (label != lastText || textPaint.textSize != lastSize) {
            lastText = label
            lastSize = textPaint.textSize
            measured = textPaint.measureText(label)
        }
        val padH = h * 0.55f
        // The artwork sits in the card's leading slot and pushes the text along; without it the card
        // is exactly the text capsule it was before.
        // The artwork comes from CarPlayMediaKeys, which recycles its own bitmaps when a transfer
        // is superseded or the session changes. Drawing a recycled one throws, so check first.
        val art = artwork?.takeIf { artworkVisible && !it.isRecycled }
        val artSize = if (art != null) h * (artworkScale / 100f) else 0f
        val artGap = if (art != null) h * 0.3f else 0f
        val textShift = if (art != null) artSize + artGap else 0f
        val w = measured + padH * 2f + textShift
        val left = posX.coerceIn(0f, (width - w).coerceAtLeast(0f))
        val top = posY.coerceIn(0f, (height - h).coerceAtLeast(0f))
        rect.set(left, top, left + w, top + h)
        GlassCardStyle.drawCapsule(canvas, rect, radius, cardAlpha / 100f)
        if (art != null) {
            val half = artSize / 2f
            val cx = rect.left + padH + half + artworkX
            val cy = rect.centerY() + artworkY
            artRect.set(cx - half, cy - half, cx + half, cy + half)
            // Rounded like the capsule it sits in; clipPath is safe on the presentation surface.
            val saved = canvas.save()
            runCatching {
                canvas.clipPath(
                    Path().apply { addRoundRect(artRect, artSize * 0.18f, artSize * 0.18f, Path.Direction.CW) },
                )
            }
            canvas.drawBitmap(art, null, artRect, artworkPaint)
            canvas.restoreToCount(saved)
        }
        val metrics = textPaint.fontMetrics
        val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, rect.left + padH + textShift, baseline, textPaint)
    }

    /** The album art CarPlay sent for the current item, or null to draw a text-only card. */
    fun setArtwork(next: Bitmap?) {
        if (artwork === next) return
        artwork = next
        invalidate()
    }

    /** A settings change arrived; drop the caches so the next draw picks it up at once. */
    fun notifySettingsChanged() {
        settingsAtMs = 0L
        maxCharsAtMs = 0L
        posX = Float.NaN
        posY = Float.NaN
        overlayScale = Float.NaN
        invalidate()
    }

    private fun refreshSettingsIfDue() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (settingsAtMs != 0L && now - settingsAtMs < SETTINGS_REFRESH_MS) return
        settingsAtMs = now
        maxChars = AirPlayPersistence.loadClusterSongMaxChars(context)
        maxCharsAtMs = now
        artworkVisible = AirPlayPersistence.loadClusterSongArtworkVisible(context)
        artworkScale = AirPlayPersistence.loadClusterSongArtworkScale(context)
        artworkX = AirPlayPersistence.loadClusterSongArtworkX(context)
        artworkY = AirPlayPersistence.loadClusterSongArtworkY(context)
        cardAlpha = AirPlayPersistence.loadClusterSongCardAlpha(context)
    }

    /**
     * Cuts a song line down to the configured number of characters.
     *
     * The card is sized by its text, so a long title used to widen it until it crossed the panel
     * and sat in the driver's view. [refreshSettingsIfDue] keeps [maxChars] current.
     */
    private fun clipped(text: String): String {
        if (maxChars <= 0 || text.length <= maxChars) return text
        return text.take(maxChars) + "..."
    }

    private companion object {
        const val CARD = "song"
        const val SETTINGS_REFRESH_MS = 2_000L
    }
}
