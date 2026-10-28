package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.widget.Toast
import com.freddyq.diplay.customization.ApplicationExitControl
import com.freddyq.diplay.customization.VehicleCustomizations
import com.shilapi.xcertplay.host.R

internal object DiPlayAppExit {
    fun allowEntry(context: Context, intent: Intent, launcher: Boolean = false): Boolean {
        if (launcher && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            VehicleCustomizations.onManualApplicationLaunch(context)
        }
        return VehicleCustomizations.allowAutomaticStartup(context)
    }
    fun title(context: Context): String = context.getString(VehicleCustomizations.applicationExitUi()?.title ?: R.string.exit_application)
    fun description(context: Context): String? = VehicleCustomizations.applicationExitUi()?.let { context.getString(it.description) }
    fun request(activity: Activity): Boolean {
        VehicleCustomizations.applicationExitUi()?.let {
            Toast.makeText(activity, activity.getString(it.progress), Toast.LENGTH_SHORT).show()
        }
        return VehicleCustomizations.requestApplicationExit(activity, object : ApplicationExitControl {
            override val automaticActivities = listOf(ComponentName(activity, CarPlayHostActivity::class.java))
            override fun shutdown(completed: () -> Unit) {
                CarPlayBackgroundSession.releaseSettingsHold()
                VehicleCustomizations.onSessionEnded()
                com.shilapi.xcertplay.hud.BydNavigationOutputs.endNow()
                if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop(completed)
                else {
                    // No owned session: discard queued local work without touching the factory map.
                    InCallClusterBridge.invalidateProjectionChannel()
                    completed()
                }
            }
            override fun removeTasks() {
                activity.getSystemService(ActivityManager::class.java)?.appTasks?.forEach { it.finishAndRemoveTask() }
                if (!activity.isFinishing) activity.finishAndRemoveTask()
            }
            override fun terminate() { Process.killProcess(Process.myPid()) }
        })
    }
}
