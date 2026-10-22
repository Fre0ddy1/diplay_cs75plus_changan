package com.shilapi.xcertplay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Shared "liquid glass" renderer for the nav-only dashboard cards.
 *
 * CarPlay's glass is a translucent, frosted capsule that reads as a raised pane on top of the map.
 * A real backdrop blur is not viable here: these cards draw directly onto the cluster presentation
 * surface (remote display, hardware-composited), so GPU blur is unreliable and can tank the frame
 * rate on a head unit. The glass is therefore painted with a low-cost lookalike:
 *
 *   1. A translucent tinted fill so the map underneath shows through faintly.
 *   2. A vertical sheen gradient inside the top edge (the "glass catches the light" band).
 *   3. A soft dark drop shadow under the capsule (the "floats above the map" cue).
 *   4. A bright hairline top edge + a dim bottom edge (glossy bevel).
 *
 * The shared paints are created once and reused. The draw path is not allocation-free: drawChip and
 * drawTextBackstop build a Paint (and, for the text backstop, a LinearGradient) per call. That is
 * cheap at the rate these cards redraw (about once a second) but would matter if they ever animate.
 */
internal object GlassCardStyle {

    // The glass tint: a cool, slightly blue-grey translucent base. Light enough to read as glass,
    // dark enough to keep white text legible over a bright map.
    private const val FILL_R = 118
    private const val FILL_G = 140
    private const val FILL_B = 162
    private const val FILL_ALPHA = 58            // ~23%, lets the map bleed through

    // Sheen band: near-white at the top fading to transparent just below the top edge.
    private const val SHEEN_ALPHA_TOP = 72
    private const val SHEEN_ALPHA_MID = 22

    /**
     * Shadow: a small fixed offset with a faint fill, so the capsule reads as lifted without a
     * slab appearing under it. The earlier proportional offset (14 % of the height) plus a 35 %
     * black fill drew a solid dark block under tall cards rather than a shadow; field feedback was
     * "the shadow block is too big and too heavy". The fill also has to stay faint because a shape's
     * shadow layer is not honoured on every hardware-accelerated path, and a transparent fill would
     * then leave no shadow at all.
     */
    private const val SHADOW_DY_PX = 2f
    private const val SHADOW_FILL_ALPHA = 26
    private const val SHADOW_BLUR = 5f
    private const val SHADOW_LAYER_ALPHA = 64

    /** Hairline edges. */
    private val topEdge = Color.argb(90, 255, 255, 255)
    private val bottomEdge = Color.argb(28, 0, 0, 0)
    private val outline = Color.argb(40, 255, 255, 255)

    // Paints are stateless apart from geometry, so they are safe to share across cards and threads
    // as long as each caller sets its own RectF before drawing (no per-card mutation needed).
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Faint, not solid: this used to be 35 % black, which painted a dark slab rather than depth.
        color = Color.argb(SHADOW_FILL_ALPHA, 0, 0, 0)
        setShadowLayer(SHADOW_BLUR, 0f, SHADOW_DY_PX, Color.argb(SHADOW_LAYER_ALPHA, 0, 0, 0))
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(FILL_ALPHA, FILL_R, FILL_G, FILL_B)
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val chipFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(34, 255, 255, 255)
    }

    /**
     * Draws a glass capsule (background + sheen + bevel + outline + shadow) into [rect].
     * [radius] is the corner radius; the shadow is drawn first so the fill sits on top of it.
     *
     * [fillOverride] replaces the translucent tint with an exact colour. The song card uses it for
     * its background-strength setting: at 100 % the driver expects an opaque black fill, not the
     * faint blue-grey glass every other card uses. A throwaway paint is built when overriding, since
     * the shared one is what every other card draws with.
     */
    fun drawCapsule(canvas: Canvas, rect: RectF, radius: Float, fillOverride: Int? = null) {
        // A small, fixed drop shadow. saveLayer bounds where the soft edge may paint.
        canvas.saveLayer(rect.left - radius, rect.top - radius, rect.right + radius, rect.bottom + radius, null)
        val shadowRect = RectF(rect)
        shadowRect.offset(0f, SHADOW_DY_PX)
        canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint)
        canvas.restore()

        // Base fill: the shared glass tint, or the caller's exact colour.
        if (fillOverride == null) {
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        } else {
            val custom = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillOverride }
            canvas.drawRoundRect(rect, radius, radius, custom)
        }

        // Top sheen band.
        sheenPaint.shader = LinearGradient(
            rect.left, rect.top, rect.left, rect.top + rect.height() * 0.55f,
            Color.argb(SHEEN_ALPHA_TOP, 255, 255, 255),
            Color.argb(SHEEN_ALPHA_MID, 255, 255, 255),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, radius, radius, sheenPaint)
        sheenPaint.shader = null

        // Glossy bevel: bright top hairline, dim bottom hairline, faint full outline.
        edgePaint.color = topEdge
        canvas.drawLine(rect.left + radius, rect.top + 0.75f, rect.right - radius, rect.top + 0.75f, edgePaint)
        edgePaint.color = bottomEdge
        canvas.drawLine(rect.left + radius, rect.bottom - 0.75f, rect.right - radius, rect.bottom - 0.75f, edgePaint)
        edgePaint.color = outline
        canvas.drawRoundRect(rect, radius, radius, edgePaint)
    }

    /** Draws a soft glass chip (used for the maneuver glyph and the lane capsules). */
    fun drawChip(canvas: Canvas, rect: RectF, radius: Float, highlight: Boolean = false) {
        if (highlight) {
            val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 10, 132, 255) }
            canvas.drawRoundRect(rect, radius, radius, accent)
            val accentStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(160, 10, 132, 255); style = Paint.Style.STROKE; strokeWidth = 2f
            }
            canvas.drawRoundRect(rect, radius, radius, accentStroke)
        } else {
            canvas.drawRoundRect(rect, radius, radius, chipFillPaint)
            val chipEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(40, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.5f
            }
            canvas.drawRoundRect(rect, radius, radius, chipEdge)
        }
    }

    /**
     * Legibility backstop: a faint dark vertical gradient behind the text area, so even where the
     * map is bright under the translucent glass the labels stay readable. Drawn between the glass
     * fill and the text.
     */
    fun drawTextBackstop(canvas: Canvas, rect: RectF, radius: Float) {
        val back = Paint(Paint.ANTI_ALIAS_FLAG)
        back.shader = LinearGradient(
            rect.left, rect.top, rect.left, rect.bottom,
            Color.argb(40, 0, 0, 0), Color.argb(22, 0, 0, 0),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, radius, radius, back)
        back.shader = null
    }

    /** Shared white label paint with a subtle shadow for legibility over any map brightness. */
    fun labelPaint(textSize: Float, alpha: Int = 255, medium: Boolean = true): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alpha, 255, 255, 255)
            this.textSize = textSize
            typeface = android.graphics.Typeface.create(
                if (medium) "sans-serif-medium" else "sans-serif",
                android.graphics.Typeface.NORMAL,
            )
            setShadowLayer(textSize * 0.12f, 0f, textSize * 0.08f, Color.argb(140, 0, 0, 0))
        }
}
