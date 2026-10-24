package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/**
 * The fly-in card dashboard ("飞屏小卡", new branch built from scratch 2026-10-24).
 *
 * Modeled after the "风清速连" box effect the driver showed in two photos: the stock gauge
 * cluster stays on screen and ONE small map card floats over the driver's side of the panel.
 *
 * How it works:
 * - The presentation window stays MATCH_PARENT but its root is fully transparent, so whatever
 *   the head unit keeps behind our window (the stock cluster UI) shows around the card.
 * - The card owns its own [TextureView], bound to the 111 navigation stream exactly like the
 *   full-screen nav view of the mirror/nav presentation. The stream stays resident; the card is
 *   only where the view renders.
 * - The map stream is produced for the whole panel, so the card zooms into a fixed centre crop
 *   of the source picture (where Apple keeps the car marker / route) instead of shrinking the
 *   whole panel into the card.
 *
 * All geometry is percent-of-panel so it follows any cluster resolution.
 */
internal class ClusterFlyCard(
    context: Context,
    private val onNavSurface: (Surface?) -> Unit,
) : FrameLayout(context) {

    /** The panel this card lives on; set by the presentation once the display size is known. */
    private var panelWidth = 0
    private var panelHeight = 0

    private val cardPanel: FrameLayout
    private val navTextureView: TextureView
    private val placeholder: TextView

    /** Where the card sits, as percent of the panel (left / width / height). Vertically centred. */
    private fun cardLeftPx() = panelWidth * CARD_LEFT_PERCENT / 100
    private fun cardWidthPx() = panelWidth * CARD_WIDTH_PERCENT / 100
    private fun cardHeightPx() = panelHeight * CARD_HEIGHT_PERCENT / 100

    init {
        // Fully transparent dashboard: the stock cluster must show around the card.
        setBackgroundColor(Color.TRANSPARENT)

        // Rounded dark-glass panel with an outline, so the video is clipped to the rounded shape.
        val rounded = GradientDrawable().apply {
            setColor(CARD_BACKGROUND_COLOR)
            cornerRadius = CARD_CORNER_RADIUS_PX
            setStroke(CARD_STROKE_WIDTH_PX, CARD_STROKE_COLOR)
        }
        cardPanel = FrameLayout(context).apply {
            background = rounded
            clipToOutline = true
        }
        addView(cardPanel)

        navTextureView = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    // The stream is produced for the whole panel, whatever the card's size is.
                    texture.setDefaultBufferSize(panelWidth, panelHeight)
                    applyTransform()
                    onNavSurface(Surface(texture))
                    onSurfaceReady?.invoke()
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = applyTransform()

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    onNavSurface(null)
                    return true
                }
            }
        }
        cardPanel.addView(
            navTextureView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        // Shown until the 111 stream really paints; hidden by setStreamActive(true).
        placeholder = TextView(context).apply {
            text = context.getString(R.string.cluster_fly_card_waiting)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
        }
        cardPanel.addView(
            placeholder,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    /** The TextureView whose Surface must be bound to the 111 stream (SCREEN_TYPE_ALT). */
    fun navTexture(): TextureView = navTextureView

    /**
     * Notified once the card's TextureView surface exists (after the nav surface callback).
     * The owner uses it to re-assert a mode requested while the view was still being created.
     * Do NOT replace the TextureView's listener - this class owns it.
     */
    var onSurfaceReady: (() -> Unit)? = null

    /** Panel size from the cluster display; also (re)places the card. Call before show(). */
    fun setPanelSize(width: Int, height: Int) {
        panelWidth = width
        panelHeight = height
        cardPanel.layoutParams = LayoutParams(cardWidthPx(), cardHeightPx()).apply {
            leftMargin = cardLeftPx()
            topMargin = (height - cardHeightPx()) / 2
        }
        applyTransform()
    }

    /** Placeholder visibility follows the real stream state, never a guess. */
    fun setStreamActive(active: Boolean) {
        placeholder.visibility = if (active) View.GONE else View.VISIBLE
    }

    /**
     * Maps the centre crop of the source picture onto the card (cover fit, centred).
     *
     * The crop region is where the 111 map keeps the route and car marker (the panel's centre,
     * inside the safe area), so the card shows a usable map instead of a shrunken whole panel.
     */
    fun applyTransform() {
        val cardW = cardWidthPx()
        val cardH = cardHeightPx()
        if (panelWidth <= 0 || panelHeight <= 0 || cardW <= 0 || cardH <= 0) return
        val cropL = panelWidth * SRC_CROP_LEFT_PERCENT / 100f
        val cropT = panelHeight * SRC_CROP_TOP_PERCENT / 100f
        val cropR = panelWidth - panelWidth * SRC_CROP_RIGHT_PERCENT / 100f
        val cropB = panelHeight - panelHeight * SRC_CROP_BOTTOM_PERCENT / 100f
        val cropW = cropR - cropL
        val cropH = cropB - cropT
        if (cropW < 1f || cropH < 1f) return
        // Cover: fill the card entirely, cropping whichever source axis is too tall/wide.
        val scale = maxOf(cardW / cropW, cardH / cropH)
        val cardLeft = cardLeftPx().toFloat()
        val cardTop = ((panelHeight - cardHeightPx()) / 2).toFloat()
        val tx = cardLeft + cardW / 2f - (cropL + cropW / 2f) * scale
        val ty = cardTop + cardH / 2f - (cropT + cropH / 2f) * scale
        navTextureView.setTransform(Matrix().apply {
            setScale(scale, scale)
            postTranslate(tx, ty)
        })
        navTextureView.invalidate()
    }

    companion object {
        const val TAG = "DiPlay-FlyCard"

        // —— Card geometry, percent of the cluster panel ——
        // Docked at the driver's side (left), like the photos: roughly a quarter of the panel
        // wide, a bit more than half tall, vertically centred.
        const val CARD_LEFT_PERCENT = 4
        const val CARD_WIDTH_PERCENT = 24
        const val CARD_HEIGHT_PERCENT = 58

        // —— Source crop, percent of the 111 stream picture ——
        // The stream is laid out for the whole panel with Apple's centred safe area
        // (35/16/36/25 in percent). Cropping to x 30..70 / y 12..88 keeps the route and the
        // car marker large inside the card.
        const val SRC_CROP_LEFT_PERCENT = 30
        const val SRC_CROP_TOP_PERCENT = 12
        const val SRC_CROP_RIGHT_PERCENT = 30
        const val SRC_CROP_BOTTOM_PERCENT = 12

        // Dark glass look: translucent dark blue, rounded, thin light stroke.
        const val CARD_BACKGROUND_COLOR = 0xD9101820.toInt()
        const val CARD_STROKE_COLOR = 0x66344C5E.toInt()
        const val CARD_CORNER_RADIUS_PX = 26f
        const val CARD_STROKE_WIDTH_PX = 2
    }
}
