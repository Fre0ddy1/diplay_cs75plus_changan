package com.freddyq.diplay.customization

import android.content.Context
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/** Extension points contain no vehicle-specific protocol or UI implementation. */
interface VehicleCustomization {
    val id: String
    fun mediaKeyRoute(context: Context): MediaKeyRoute? = null
    fun onSessionStarted(context: Context) {}
    fun onNavigationFrame(messageId: Int, payload: ByteArray) {}
    fun onSessionEnded() {}
    fun onProjectionNavigationState(state: Int) {}
    fun onProjectionStarted(context: Context, automatic: Boolean, control: ProjectionControl) {}
    /** A new window is using a retained channel and requires fresh confirmation. */
    fun onProjectionResumed(context: Context, control: ProjectionControl): Boolean = false
    fun onProjectionLayoutRequested(layout: ProjectionLayout) {}
    fun onProjectionContentReady(context: Context, window: Any, control: ProjectionContentControl) {}
    fun onProjectionContentLost(window: Any) {}
    fun onProjectionManualControl() {}
    fun onProjectionReleased() {}
    fun shouldRetryAutomaticProjection(): Boolean = true
    /** Called only after the host observes an unavailable channel, rather than a layout retry. */
    fun shouldActivateProjectionChannel(): Boolean = shouldRetryAutomaticProjection()
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

    fun mediaKeyRoute(context: Context): MediaKeyRoute? {
        for (extension in extensions) {
            try {
                extension.mediaKeyRoute(context.applicationContext)?.let { return it }
            } catch (error: Exception) {
                Log.w(TAG, "${extension.id} media-key policy failed", error)
            }
        }
        return null
    }

    fun onNavigationFrame(messageId: Int, payload: ByteArray) {
        if (messageId !in NAVIGATION_MESSAGES) return
        // An extension receives its own copy, so neither the phone path nor another extension
        // can observe a mutation or retain a transport-owned buffer.
        dispatch { it.onNavigationFrame(messageId, payload.copyOf()) }
    }

    fun onSessionEnded() = dispatch { it.onSessionEnded() }
    fun onProjectionNavigationState(state: Int) = dispatch { it.onProjectionNavigationState(state) }
    fun onProjectionStarted(context: Context, automatic: Boolean, control: ProjectionControl) =
        dispatch { it.onProjectionStarted(context.applicationContext, automatic, control) }
    fun onProjectionResumed(context: Context, control: ProjectionControl): Boolean {
        var handled = false
        dispatch { handled = it.onProjectionResumed(context.applicationContext, control) || handled }
        return handled
    }
    fun onProjectionLayoutRequested(layout: ProjectionLayout) = dispatch { it.onProjectionLayoutRequested(layout) }
    fun onProjectionContentReady(context: Context, window: Any, control: ProjectionContentControl) =
        dispatch { it.onProjectionContentReady(context.applicationContext, window, control) }
    fun onProjectionContentLost(window: Any) = dispatch { it.onProjectionContentLost(window) }
    fun onProjectionManualControl() = dispatch { it.onProjectionManualControl() }
    fun onProjectionReleased() = dispatch { it.onProjectionReleased() }
    fun shouldRetryAutomaticProjection(): Boolean = extensions.all { extension ->
        try { extension.shouldRetryAutomaticProjection() } catch (error: Exception) {
            Log.w(TAG, "${extension.id} retry policy failed", error)
            true
        }
    }

    fun shouldActivateProjectionChannel(): Boolean = extensions.all { extension ->
        try { extension.shouldActivateProjectionChannel() } catch (error: Exception) {
            Log.w(TAG, "${extension.id} channel activation policy failed", error)
            true
        }
    }

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
