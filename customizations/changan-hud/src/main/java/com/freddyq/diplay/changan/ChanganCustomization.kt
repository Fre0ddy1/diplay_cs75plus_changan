package com.freddyq.diplay.changan

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.freddyq.diplay.customization.CustomizationSettingsGroup
import com.freddyq.diplay.customization.CustomizationToggle
import com.freddyq.diplay.customization.VehicleCustomization
import com.freddyq.diplay.customization.VehicleCustomizations

class ChanganCustomization : VehicleCustomization {
    override val id = "changan-hud"
    override fun onSessionStarted(context: Context) = ChanganHudBridge.start(context)
    override fun onNavigationFrame(messageId: Int, payload: ByteArray) = ChanganHudBridge.onFrame(messageId, payload)
    override fun onSessionEnded() = ChanganHudBridge.clear()
    override fun onProjectionNavigationState(state: Int) = ChanganHudBridge.projectionStateChanging(state)

    override fun settings(context: Context): List<CustomizationSettingsGroup> = listOf(
        CustomizationSettingsGroup(R.string.settings_changan_hud_title, listOf(
            CustomizationToggle(
                R.string.settings_changan_hud_enabled,
                R.string.settings_changan_hud_description,
                ChanganHudSettings.enabled(context),
            ) { ChanganHudSettings.setEnabled(context, it) },
            CustomizationToggle(
                R.string.settings_changan_hud_conflict_recovery,
                R.string.settings_changan_hud_conflict_description,
                ChanganHudSettings.conflictRecoveryEnabled(context),
            ) { ChanganHudSettings.setConflictRecoveryEnabled(context,it) },
        )),
    )
}

/** The merged manifest registers the module without adding a vendor import to Application. */
class ChanganCustomizationInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        VehicleCustomizations.register(ChanganCustomization())
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
