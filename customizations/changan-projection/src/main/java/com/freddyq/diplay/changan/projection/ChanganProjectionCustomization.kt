package com.freddyq.diplay.changan.projection

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.freddyq.diplay.customization.CustomizationSettingsGroup
import com.freddyq.diplay.customization.CustomizationToggle
import com.freddyq.diplay.customization.ProjectionControl
import com.freddyq.diplay.customization.ProjectionLayout
import com.freddyq.diplay.customization.VehicleCustomization
import com.freddyq.diplay.customization.VehicleCustomizations

class ChanganProjectionCustomization : VehicleCustomization {
    override val id = "changan-projection"
    // Controller preparation and AirPlay activation may both announce the same session.
    // Projection has its own start generation; a duplicate phone-start must not reset recovery.
    override fun onSessionStarted(context: Context) = Unit
    override fun onSessionEnded() = ChanganProjectionBridge.clear("session-end")
    override fun onProjectionStarted(context: Context, automatic: Boolean, control: ProjectionControl) =
        ChanganProjectionBridge.start(context, automatic, control)
    override fun onProjectionLayoutRequested(layout: ProjectionLayout) = ChanganProjectionBridge.layoutRequested(layout)
    override fun onProjectionManualControl() = ChanganProjectionBridge.clear("manual-control")
    override fun onProjectionReleased() = ChanganProjectionBridge.clear("projection-released")
    override fun shouldRetryAutomaticProjection() = ChanganProjectionBridge.shouldRetryAutomaticProjection()
    override fun settings(context: Context) = listOf(
        CustomizationSettingsGroup(R.string.settings_changan_projection_title, listOf(
            CustomizationToggle(
                R.string.settings_changan_projection_fullscreen,
                R.string.settings_changan_projection_description,
                ChanganProjectionSettings.enabled(context),
            ) { ChanganProjectionSettings.setEnabled(context, it) },
        )),
    )
}

class ChanganProjectionInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        VehicleCustomizations.register(ChanganProjectionCustomization())
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
