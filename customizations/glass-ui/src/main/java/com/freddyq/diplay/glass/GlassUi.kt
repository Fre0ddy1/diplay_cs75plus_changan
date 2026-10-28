package com.freddyq.diplay.glass

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ContextThemeWrapper
import android.view.View

/** Static frosted surfaces work on Android 7–9 without blurring live CarPlay frames. */
object GlassUi {
    val background = Color.rgb(234, 240, 245)
    val surface = Color.argb(205, 255, 255, 255)
    val inset = Color.argb(130, 239, 247, 250)
    val border = Color.argb(230, 255, 255, 255)
    val text = Color.rgb(27, 47, 63)
    val secondary = Color.rgb(73, 96, 113)
    val muted = Color.rgb(88, 111, 126)
    val accent = Color.rgb(33, 108, 123)
    val accentTrack = Color.rgb(174, 210, 216)
    val selected = Color.rgb(216, 235, 240)
    val trackOff = Color.rgb(194, 208, 218)
    val thumbOff = Color.rgb(249, 252, 254)
    val divider = Color.argb(38, 60, 99, 125)
    val warning = Color.rgb(151, 81, 25)
    val danger = Color.rgb(168, 54, 63)
    fun dialogContext(context: Context) = ContextThemeWrapper(context, R.style.Theme_DiPlay_Glass_Dialog)
    fun backdrop(): Drawable = FrostBackdrop()
    fun panel(context: Context, radius: Int = 22, nested: Boolean = false): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (nested) intArrayOf(0xD9F5FAFC.toInt(), 0x99E8F1F6.toInt())
            else intArrayOf(0xF2FFFFFF.toInt(), 0xB8F7FBFE.toInt())).apply {
            cornerRadius = context.resources.displayMetrics.density * radius
            setStroke((context.resources.displayMetrics.density).toInt().coerceAtLeast(1), border)
        }
    fun lift(view: View, nested: Boolean = false) {
        view.background = panel(view.context, nested = nested)
        view.elevation = view.resources.displayMetrics.density * if (nested) 0f else 2f
    }
    fun button(context: Context, primary: Boolean = false, destructive: Boolean = false): Drawable {
        val density = context.resources.displayMetrics.density
        val fill = if (primary) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF287F8C.toInt(), accent))
            else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xF2FFFFFF.toInt(), 0xB3EDF5F9.toInt()))
        fill.cornerRadius = density * 16
        fill.setStroke(density.toInt().coerceAtLeast(1), if (primary) accent else if (destructive) 0x55A8363F else border)
        return RippleDrawable(ColorStateList.valueOf(0x22216C7B), fill, null)
    }
}

/** Soft radial colour fields are static; gradients are rebuilt only when bounds change. */
private class FrostBackdrop : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var layers = emptyList<Shader>()
    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        layers = listOf(
            RadialGradient(w * .92f, h * .06f, w * .68f, 0xA6B6D8EF.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP),
            RadialGradient(w * .08f, h * .92f, w * .57f, 0x9ED1E8DF.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP),
            RadialGradient(w * .55f, h * .4f, w * .46f, 0xBBFFFFFF.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP),
        )
    }
    override fun draw(canvas: Canvas) {
        canvas.drawColor(GlassUi.background)
        layers.forEach { paint.shader = it; canvas.drawRect(bounds, paint) }
        paint.shader = null
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.OPAQUE
}
