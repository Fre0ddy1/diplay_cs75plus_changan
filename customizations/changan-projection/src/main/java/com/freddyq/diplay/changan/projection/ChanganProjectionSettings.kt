package com.freddyq.diplay.changan.projection

import android.content.Context

internal object ChanganProjectionSettings {
    private const val PREFS = "diplay_changan_projection"
    private const val ENABLED = "automatic_fullscreen"
    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(ENABLED, true)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        if (!enabled) ChanganProjectionBridge.clear("setting-disabled")
    }
}
