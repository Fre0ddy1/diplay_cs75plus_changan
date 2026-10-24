package com.freddyq.diplay.customization

import android.content.Context
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/** Extension points contain no vehicle-specific protocol or UI implementation. */
interface VehicleCustomization {
    val id: String
    fun onSessionStarted(context: Context) {}
    fun onNavigationFrame(messageId: Int, payload: ByteArray) {}
    fun onSessionEnded() {}
    fun onProjectionNavigationState(state: Int) {}
    fun settings(context: Context): List<CustomizationSettingsGroup> = emptyList()
}

data class CustomizationSettingsGroup(val title: Int, val toggles: List<CustomizationToggle>)
data class CustomizationToggle(
    val title: Int,
    val description: Int,
    val enabled: Boolean,
    val onChanged: (Boolean) -> Unit,
)

/** A decoded maneuver in the standard AMap broadcast vocabulary, before vehicle limits. */
data class StandardNavigationGuidance(
    val icon: Int,
    val distanceMeters: Int,
    val nextRoad: String,
    val currentRoad: String,
    val roundaboutExit: Int,
)

/** Register at process initialization. Implementations must enqueue slow work themselves. */
open class VehicleCustomizationRegistry {
    private val extensions = CopyOnWriteArrayList<VehicleCustomization>()
    @Volatile var diagnosticListener: ((String) -> Unit)? = null

    @Synchronized fun register(extension: VehicleCustomization) {
        if (extensions.none { it.id == extension.id }) extensions += extension
    }

    fun onSessionStarted(context: Context) = dispatch { it.onSessionStarted(context.applicationContext) }

    fun onNavigationFrame(messageId: Int, payload: ByteArray) {
        if (messageId !in NAVIGATION_MESSAGES) return
        // An extension receives its own copy, so neither the phone path nor another extension
        // can observe a mutation or retain a transport-owned buffer.
        dispatch { it.onNavigationFrame(messageId, payload.copyOf()) }
    }

    fun onSessionEnded() = dispatch { it.onSessionEnded() }
    fun onProjectionNavigationState(state: Int) = dispatch { it.onProjectionNavigationState(state) }

    fun settings(context: Context): List<CustomizationSettingsGroup> = extensions.flatMap { extension ->
        try {
            extension.settings(context.applicationContext).map { group ->
                group.copy(toggles = group.toggles.map { toggle ->
                    toggle.copy(onChanged = { enabled ->
                        try {
                            toggle.onChanged(enabled)
                        } catch (error: Exception) {
                            Log.w(TAG, "${extension.id} setting change failed", error)
                        }
                    })
                })
            }
        } catch (error: Exception) {
            Log.w(TAG, "${extension.id} settings failed", error)
            emptyList()
        }
    }

    fun diagnostic(source: String, message: String) {
        try {
            diagnosticListener?.invoke("$source: $message")
        } catch (error: Exception) {
            Log.w(TAG, "Customization diagnostics failed", error)
        }
    }

    private inline fun dispatch(action: (VehicleCustomization) -> Unit) {
        extensions.forEach { extension ->
            try {
                action(extension)
            } catch (error: Exception) {
                Log.w(TAG, "${extension.id} callback failed", error)
            }
        }
    }

    companion object {
        private const val TAG = "DiPlay-Customizations"
        private val NAVIGATION_MESSAGES = setOf(0x5201, 0x5202, 0x5204)
    }
}

object VehicleCustomizations : VehicleCustomizationRegistry()
