package com.freddyq.diplay.changan.projection

import android.content.Context

internal object ChanganProjectionSettings {
    private const val PREFS = "diplay_changan_projection"
    private const val ENABLED = "automatic_fullscreen"
    // New explicit opt-in. Legacy auto-fly/fullscreen defaults must not enable this on upgrade.
    private const val STOCK_AMAP = "stock_amap_with_auto_projection"
    fun stockAmapEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(STOCK_AMAP, false)
    fun setStockAmapEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(STOCK_AMAP, enabled).apply()
    }
    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(ENABLED, true)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        if (!enabled) ChanganProjectionBridge.clear("setting-disabled")
    }
}
