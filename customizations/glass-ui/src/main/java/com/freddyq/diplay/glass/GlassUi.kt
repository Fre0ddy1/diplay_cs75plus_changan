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
    val background = Color.rgb(205, 213, 223)
    val surface = Color.argb(130, 255, 255, 255)
    val inset = Color.argb(85, 246, 249, 254)
    val border = Color.argb(76, 112, 128, 148)
    val buttonSurface = Color.rgb(225, 233, 241)
    val buttonSurfaceEnd = Color.rgb(216, 227, 236)
    val buttonBorder = Color.rgb(82, 107, 130)
    val text = Color.rgb(27, 35, 49)
    val secondary = Color.rgb(63, 75, 94)
    val muted = Color.rgb(66, 80, 98)
    val accent = Color.rgb(0, 86, 195)
    val accentTrack = Color.rgb(166, 197, 236)
    val selected = Color.argb(195, 255, 255, 255)
    val trackOff = Color.rgb(141, 155, 174)
    val thumbOff = Color.rgb(241, 246, 249)
    val divider = Color.argb(45, 77, 92, 113)
    val warning = Color.rgb(120, 64, 20)
    val danger = Color.rgb(145, 43, 54)
    fun dialogContext(context: Context) = ContextThemeWrapper(context, R.style.Theme_DiPlay_Glass_Dialog)
    fun backdrop(): Drawable = FrostBackdrop()
    fun panel(context: Context, radius: Int = 28, nested: Boolean = false): Drawable =
        GlassMaterialDrawable(context.resources.displayMetrics.density, radius.toFloat(), nested = nested)
    fun lift(view: View, nested: Boolean = false) {
        view.background = panel(view.context, nested = nested)
        view.elevation = view.resources.displayMetrics.density * if (nested) 0f else 3f
    }
    fun button(context: Context, primary: Boolean = false, destructive: Boolean = false): Drawable {
        val fill = GlassMaterialDrawable(context.resources.displayMetrics.density, 30f,
            control = true, primary = primary, destructive = destructive)
        return RippleDrawable(ColorStateList.valueOf(0x330056C3), fill, null)
    }
}
