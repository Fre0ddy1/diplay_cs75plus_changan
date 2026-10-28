package com.freddyq.diplay.glass

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.min

/** Translucent material with a changing rim highlight; no capture of the screen or live video. */
internal class GlassMaterialDrawable(
    private val density: Float,
    private val radiusDp: Float,
    private val nested: Boolean = false,
    private val control: Boolean = false,
    private val primary: Boolean = false,
    private val destructive: Boolean = false,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val face = RectF()
    private val inner = RectF()
    private var radius = 0f
    private var innerRadius = 0f
    private var fill: Shader? = null
    private var rim: Shader? = null
    private var reflection: Shader? = null
    private var materialAlpha = 255

    override fun onBoundsChange(bounds: Rect) {
        face.set(bounds)
        val halfStroke = density * .45f
        face.inset(halfStroke, halfStroke)
        radius = min(density * radiusDp, face.height() / 2)
        inner.set(face)
        inner.inset(density * 1.2f, density * 1.2f)
        innerRadius = (radius - density).coerceAtLeast(0f)
        val colors = when {
            primary -> intArrayOf(0xF50961D8.toInt(), 0xF20050BD.toInt())
            destructive -> intArrayOf(0xB3FFF5F6.toInt(), 0x70FFE9EC)
            control -> intArrayOf(0xC7FFFFFF.toInt(), 0x7AFFFFFF)
            nested -> intArrayOf(0x66FFFFFF, 0x40F7FAFF)
            else -> intArrayOf(0x91FFFFFF.toInt(), 0x61F8FBFF)
        }
        fill = LinearGradient(face.left, face.top, face.right, face.bottom, colors, null, Shader.TileMode.CLAMP)
        rim = LinearGradient(0f, face.top, 0f, face.bottom,
            intArrayOf(0xEDFFFFFF.toInt(), 0x38FFFFFF, 0x22475D77, if (control) GlassUi.buttonBorder else 0x7072839A),
            floatArrayOf(0f, .34f, .68f, 1f), Shader.TileMode.CLAMP)
        reflection = LinearGradient(0f, face.top, 0f, face.bottom,
            0x70FFFFFF, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) {
        paint.alpha = materialAlpha
        paint.style = Paint.Style.FILL; paint.shader = fill
        canvas.drawRoundRect(face, radius, radius, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density * .9f; paint.shader = rim
        canvas.drawRoundRect(face, radius, radius, paint)
        if (control) {
            paint.strokeWidth = density * .5f; paint.shader = reflection
            canvas.drawRoundRect(inner, innerRadius, innerRadius, paint)
        }
        paint.shader = null
    }
    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, radius); outline.alpha = .35f }
    override fun setAlpha(alpha: Int) { materialAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Reuse one bounded wallpaper for equal-size pages; old visible drawables retain their pixels. */
internal class FrostBackdrop : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    override fun onBoundsChange(bounds: Rect) {
        val width = min(640, (bounds.width() / 4).coerceAtLeast(1))
        val height = (width.toFloat() * bounds.height() / bounds.width().coerceAtLeast(1)).toInt().coerceIn(1, 360)
        bitmap = wallpaper(width, height)
    }
    override fun draw(canvas: Canvas) { bitmap?.let { canvas.drawBitmap(it, null, bounds, paint) } ?: canvas.drawColor(GlassUi.background) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        private var cached: Bitmap? = null
        @Synchronized private fun wallpaper(width: Int, height: Int): Bitmap {
            cached?.let { if (it.width == width && it.height == height) return it }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { target ->
                val canvas = Canvas(target)
                val w = width.toFloat(); val h = height.toFloat()
                canvas.drawColor(GlassUi.background)
                val fields = listOf(
                    RadialGradient(w * .88f, h * .12f, w * .64f, 0xCBA5B6D5.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP),
                    RadialGradient(w * .08f, h * .98f, w * .62f, 0xC7A9C7BC.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP),
                    RadialGradient(w * .45f, h * .05f, w * .40f, 0xA8E4D4D1.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP),
                )
                fields.forEach { paint.shader = it; canvas.drawRect(0f, 0f, w, h, paint) }
                paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
                paint.maskFilter = BlurMaskFilter((w * .025f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
                paint.strokeWidth = w * .09f; paint.color = 0x57739BD3
                canvas.drawPath(Path().apply { moveTo(-w * .12f, h * .86f); cubicTo(w * .24f, -h * .65f, w * .50f, h * 1.90f, w * 1.1f, h * .10f) }, paint)
                paint.strokeWidth = w * .045f; paint.color = 0x45F1C9B9
                canvas.drawPath(Path().apply { moveTo(w * .48f, -h * .5f); cubicTo(w * .96f, h * .45f, w * .64f, h * .55f, w * 1.2f, h * 1.3f) }, paint)
                paint.maskFilter = null; paint.style = Paint.Style.FILL; paint.color = Color.WHITE
            }
            // Never recycle shared pixels: a previous page may still be drawing during replacement.
            cached = result
            return result
        }
    }
}
