package com.freddyq.diplay.changan.audio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.freddyq.diplay.customization.CustomizationSettingsGroup
import com.freddyq.diplay.customization.CustomizationToggle
import com.freddyq.diplay.customization.MediaKeyRoute
import com.freddyq.diplay.customization.VehicleCustomization
import com.freddyq.diplay.customization.VehicleCustomizations

/** New explicit policy: an old Bluetooth preference must not silently enable the failed hybrid. */
class ChanganAudioCustomization : VehicleCustomization {
    override val id = "changan-audio"
    private fun enabled(context: Context) = context.getSharedPreferences(PREFS, 0)
        .getBoolean("carplay_media_controls", true)
    override fun mediaKeyRoute(context: Context) =
        if (enabled(context)) MediaKeyRoute.CARPLAY else MediaKeyRoute.EXTERNAL
    override fun settings(context: Context) = listOf(CustomizationSettingsGroup(
        R.string.audio_customization_title,
        listOf(CustomizationToggle(R.string.audio_carplay_controls, R.string.audio_carplay_description,
            enabled(context)) { value ->
            context.getSharedPreferences(PREFS, 0).edit().putBoolean("carplay_media_controls", value).commit()
        }),
    ))
    companion object { const val PREFS = "diplay_changan_audio" }
}

class ChanganAudioInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        VehicleCustomizations.register(ChanganAudioCustomization())
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
