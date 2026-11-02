package com.freddyq.diplay.changan.audio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.freddyq.diplay.customization.CustomizationSettingsGroup
import com.freddyq.diplay.customization.CustomizationSettingsArea
import com.freddyq.diplay.customization.CustomizationToggle
import com.freddyq.diplay.customization.MediaKeyRoute
import com.freddyq.diplay.customization.VehicleCustomization
import com.freddyq.diplay.customization.VehicleCustomizations
import com.freddyq.diplay.customization.WirelessAudioConnection

/** Media keys stay on CarPlay; connection-scoped A2DP policy is independently reversible. */
class ChanganAudioCustomization : VehicleCustomization {
    override val id = "changan-audio"
    private var policy: MediaAudioPolicyLease? = null
    internal var policyFactory: (Context) -> MediaAudioPolicyLease = { context ->
        MediaAudioPolicyLease({ AndroidA2dpMediaPolicyPort(context, ::diagnostics) },
            SharedMediaPolicyJournal(context), ::diagnostics)
    }
    private val main = Handler(Looper.getMainLooper())
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post(action)
    }
    private fun diagnostics(message: String) {
        Log.i("DiPlay-ChanganAudio", message)
        VehicleCustomizations.diagnostic("ChanganAudio", message)
    }
    private fun policy(context: Context) = policy ?: policyFactory(context.applicationContext).also {
        policy = it
        it.configure(context.getSharedPreferences(PREFS, 0).getBoolean(MEDIA_POLICY, true))
    }
    internal fun recover(context: Context) = onMain { policy(context).recover() }
    override fun onBluetoothMediaConnectionStarting(context: Context, connection: WirelessAudioConnection) = onMain {
        policy(context).begin(connection.owner, connection.deviceAddress)
    }
    override fun onBluetoothMediaConnectionEnded(owner: Any) = onMain { policy?.end(owner) }
    override fun prepareApplicationExit(context: Context, completed: () -> Unit): Boolean {
        onMain { policy(context).prepareExit(completed) }
        return true
    }
    private fun enabled(context: Context) = context.getSharedPreferences(PREFS, 0)
        .getBoolean("carplay_media_controls", true)
    override fun mediaKeyRoute(context: Context) =
        if (enabled(context)) MediaKeyRoute.CARPLAY else MediaKeyRoute.EXTERNAL
    override fun settings(context: Context) = listOf(CustomizationSettingsGroup(
        R.string.audio_customization_title,
        listOf(CustomizationToggle(R.string.audio_carplay_controls, R.string.audio_carplay_description,
            enabled(context)) { value ->
            context.getSharedPreferences(PREFS, 0).edit().putBoolean("carplay_media_controls", value).commit()
            // This preference changes key arbitration only. Audio always uses the same CarPlay
            // handoff and channel policy; it must not silently reconnect Bluetooth media.
        }),
    ), CustomizationSettingsGroup(
        R.string.audio_connection_title,
        listOf(CustomizationToggle(R.string.audio_disable_bluetooth_media,
            R.string.audio_disable_bluetooth_media_description,
            context.getSharedPreferences(PREFS, 0).getBoolean(MEDIA_POLICY, true)) { value ->
            context.getSharedPreferences(PREFS, 0).edit().putBoolean(MEDIA_POLICY, value).commit()
            onMain { policy(context).configure(value) }
        }), area = CustomizationSettingsArea.CONNECTION,
    ))
    companion object {
        const val PREFS = "diplay_changan_audio"
        const val MEDIA_POLICY = "disable_bluetooth_media_while_connected"
    }
}

class ChanganAudioInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        val module = ChanganAudioCustomization()
        VehicleCustomizations.register(module)
        context?.let(module::recover)
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
