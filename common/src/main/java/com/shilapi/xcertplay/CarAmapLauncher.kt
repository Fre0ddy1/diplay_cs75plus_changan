package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.util.Log

/**
 * Launches the head unit's stock Amap and immediately returns focus to DiPlay.
 *
 * On Changan Feiyu OS the instrument-cluster (dashboard) projection only works while the
 * stock car Amap is running, so after DiPlay opens we optionally bring that app up and then
 * hand focus straight back. The Amap keeps running in the background to feed the cluster.
 *
 * Package candidates cover the standard car-Amap ids; the first installed one wins. If none
 * is found the call is a no-op and only a log line is written.
 */
object CarAmapLauncher {
    private const val TAG = "xcertplay-amap"

    private val CANDIDATES = listOf(
        "com.autonavi.amapauto",
        "com.autonavi.amapauto.car",
    )

    fun launchAndReturn(activity: Activity) {
        val pm = activity.packageManager
        val target = CANDIDATES.firstOrNull {
            runCatching { pm.getPackageInfo(it, 0) }.isSuccess
        }
        if (target == null) {
            Log.w(TAG, "stock Amap not found among $CANDIDATES")
            return
        }
        val launch = pm.getLaunchIntentForPackage(target)
        if (launch == null) {
            Log.w(TAG, "no launcher intent for $target")
            return
        }
        try {
            activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity.startActivity(
                Intent(activity, DiPlayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            Log.i(TAG, "launched stock Amap $target and returned to DiPlay")
        } catch (error: RuntimeException) {
            Log.w(TAG, "stock Amap launch failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }
}
