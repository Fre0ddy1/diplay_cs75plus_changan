package com.freddyq.diplay.hotspot

import android.content.Context
import android.annotation.SuppressLint
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.freddyq.diplay.customization.PlatformHotspotCredentials
import com.freddyq.diplay.customization.VehicleCustomizations
import java.lang.reflect.InvocationTargetException
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/** Reads S311's saved AP configuration once. Never starts, changes or scans Wi-Fi. */
internal class SavedHotspotReader(
    private val manager: Any?,
    private val wrapper: Class<*> = WifiManager::class.java,
    private val diagnostic: (String) -> Unit = {},
) {
    @SuppressLint("PrivateApi")
    fun read(): PlatformHotspotCredentials? = try {
        val config = wrapper.getMethod("getWifiApConfiguration").invoke(manager) as? WifiConfiguration
        (config?.let(::credentials)).also {
            diagnostic(if (it == null) "saved AP unavailable or credentials incomplete" else "saved AP details read; values omitted")
        }
    } catch (error: Exception) {
        val cause = if (error is InvocationTargetException) error.targetException else error
        diagnostic("saved AP read failed type=${cause.javaClass.simpleName}")
        null
    }

    companion object {
        fun read(context: Context): PlatformHotspotCredentials? =
            SavedHotspotReader(context.applicationContext.getSystemService(Context.WIFI_SERVICE), diagnostic = {
                VehicleCustomizations.diagnostic("HotspotStartup", it)
            }).read()

        internal fun credentials(config: WifiConfiguration): PlatformHotspotCredentials? {
            val ssid = config.SSID ?: return null
            if (ssid == "<unknown ssid>") return null
            val keys = config.allowedKeyManagement ?: return null
            val open = keys.cardinality() == 1 && keys.get(WifiConfiguration.KeyMgmt.NONE)
            val wpa = keys.get(WifiConfiguration.KeyMgmt.WPA_PSK) || keys.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
            if (!open && !wpa) return null
            val secret = if (open) "" else config.preSharedKey ?: return null
            if ((!open && secret.all { it == '*' }) || ManualHotspotValidation.error(ssid, secret) != null) return null
            return PlatformHotspotCredentials(ssid, secret)
        }
    }
}
