package com.shilapi.xcertplay

import android.content.Context
import android.text.format.DateUtils
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import java.text.DateFormat

/** One fixed-size, non-sensitive reason. Repeated USB polls do not repeatedly write preferences. */
internal object LastConnectionProblem {
    private const val PREFS = "diplay_last_problem"
    fun follow(context: Context, status: CarPlayStatus) {
        val token = when (status) {
            is CarPlayStatus.Failed -> status.knownCause?.let { "cause:${it.name}" } ?: "general"
            is CarPlayStatus.IphoneNotListed -> if (status.nonHubDeviceListed) "usb:other" else "usb:none"
            else -> return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("problem", null) == token) return
        prefs.edit().putString("problem", token).putLong("at", System.currentTimeMillis()).apply()
    }
    fun clear(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    private fun line(token: String): Int? = when (token) {
        "usb:other" -> R.string.car_sees_usb_device_not_iphone
        "usb:none" -> R.string.car_sees_no_usb_device
        "general" -> R.string.connection_failed_diagnostic
        else -> ConnectionFailureCause.entries.firstOrNull { token == "cause:${it.name}" }?.let(ConnectionFailureGuidance::stage)
    }
    fun homeLine(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString("problem", null) ?: return null
        val message = line(token) ?: return null
        val at = prefs.getLong("at", -1).takeIf { it >= 0 } ?: return null
        val time = DateUtils.formatSameDayTime(at, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT)
        return context.getString(R.string.last_connection_attempt, time, context.getString(message))
    }
    fun report(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString("problem", null)?.takeIf { line(it) != null } ?: return "Last problem: none recorded"
        return "Last problem: $token atEpochMs=${prefs.getLong("at", -1)}"
    }
}
