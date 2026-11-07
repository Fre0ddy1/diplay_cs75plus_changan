package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings
import androidx.annotation.StringRes
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.host.R

/** Read-only labels; no connection, grant, preference write or vehicle operation belongs here. */
internal object SettingsPresentation {
    fun choosePhone(context: Context): String = if (DiPlayPreferences.phoneAddress(context) == null) {
        context.getString(R.string.choose_iphone)
    } else {
        context.getString(R.string.choose_iphone_prefix) + DiPlayPreferences.phoneName(context)
    }

    fun hotspotOff(context: Context, connection: Boolean = false): String {
        val ssid = AirPlayPersistence.loadManualHotspotSsid(context)
        return if (ssid.isBlank()) context.getString(
            if (connection) R.string.hotspot_connect_without_name else R.string.hotspot_details_off,
        ) else context.getString(
            if (connection) R.string.msg_car_hotspot_connect else R.string.msg_car_hotspot_off, ssid,
        )
    }

    /** A missing or unreadable global flag is unknown, not proof that the network switch is off. */
    @StringRes
    fun adbUnreachable(context: Context): Int = adbUnreachable(runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED)
    }.getOrNull())

    @StringRes
    fun adbUnreachable(usbDebugFlag: Int?): Int = when (usbDebugFlag) {
        1 -> R.string.adb_unreachable_usb_on
        0 -> R.string.adb_unreachable_usb_off
        else -> R.string.adb_unreachable_unknown
    }

    fun physicalReference(context: Context): String = context.getString(
        R.string.saved_physical_reference,
        context.getString(if (AirPlayPersistence.loadPhysicalSizeBasis(context) == AirPlayPhysicalSizeBasis.HEIGHT)
            R.string.basis_longest_height else R.string.basis_widest_width),
        AirPlayPersistence.loadWidthPhysicalMm(context),
    )

    /** Preserve an already-saved intermediate scale without introducing another adjustment range. */
    fun scales(current: Int): List<Int> = (listOf(10, 8, 6, 5, 4) + current).distinct().sortedDescending()

    fun lastDisplayRequest(context: Context): String {
        val line = DisplayDiagnosticSnapshot.lastRequest(context)
            ?: return context.getString(R.string.last_display_request_unavailable)
        val stamp = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?= Display request )").find(line)
        val canvas = Regex("\\bcanvas=(\\d{1,5})x(\\d{1,5})\\b").find(line)
        return if (canvas == null || stamp == null) context.getString(R.string.last_display_request_unavailable) else {
            context.getString(R.string.last_display_request, stamp.value,
                canvas.groupValues[1], canvas.groupValues[2])
        }
    }

    fun vpnHelp(context: Context): String = context.getString(R.string.vpn_manual_authorization_help,
        "adb shell appops set ${context.packageName} ACTIVATE_VPN allow")
}

/** A disconnected home card still offers the existing wireless Connect button. */
internal object HomeCardTransport {
    fun wired(sessionRunning: Boolean, wirelessSelected: Boolean): Boolean = sessionRunning && !wirelessSelected
}
