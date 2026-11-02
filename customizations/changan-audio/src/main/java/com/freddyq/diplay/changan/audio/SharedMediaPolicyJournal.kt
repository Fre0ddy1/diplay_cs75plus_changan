package com.freddyq.diplay.changan.audio

import android.content.Context

internal class SharedMediaPolicyJournal(context: Context) : MediaPolicyJournal {
    private val prefs = context.getSharedPreferences("diplay_bluetooth_media_restore", Context.MODE_PRIVATE)
    override fun read(): SavedMediaPolicy? {
        val address = prefs.getString("address", null) ?: return null
        return SavedMediaPolicy(address, prefs.getInt("priority", -1)).takeIf { it.priority in setOf(100, 1000) }
    }
    override fun save(value: SavedMediaPolicy) = prefs.edit().putString("address", value.address)
        .putInt("priority", value.priority).commit()
    override fun clear() = prefs.edit().clear().commit()
}
