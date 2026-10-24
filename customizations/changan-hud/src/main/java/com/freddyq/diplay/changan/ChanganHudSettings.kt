package com.freddyq.diplay.changan

import android.content.Context

/** Custom-edition default is on. An explicit saved off choice always takes precedence. */
object ChanganHudSettings {
    private const val PREFS = "diplay_changan_hud"
    private const val KEY_ENABLED = "navigation_enabled"

    fun receiverInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(ChanganHudProfile.RECEIVER_PACKAGE, 0) }.isSuccess

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        ChanganHudBridge.settingsChanged()
    }
}
