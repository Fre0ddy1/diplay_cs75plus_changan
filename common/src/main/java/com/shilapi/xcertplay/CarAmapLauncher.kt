package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Launches the head unit's stock Amap and immediately returns focus to DiPlay.
 *
 * On Changan Feiyu OS the instrument-cluster (dashboard) projection only works while the
 * stock car Amap is running, so after DiPlay opens (or after a CarPlay session activates)
 * we optionally bring that app up and then hand focus straight back. The Amap keeps
 * running in the background to feed the cluster.
 *
 * Package candidates cover the standard car-Amap ids; the first installed one wins. If none
 * is found the call is a no-op and only a log line is written. A short debounce prevents
 * duplicate launches when both the DiPlay cold start and the CarPlay session activate
 * fire within the same window. The return hop is deferred so the (slow) stock Amap finishes
 * coming to the foreground before the DiPlay task is pulled back over it; otherwise the
 * Amap's startup animation can cover the return intent.
 */
object CarAmapLauncher {
    private const val TAG = "xcertplay-amap"

    private val CANDIDATES = listOf(
        "com.autonavi.amapauto",
        "com.autonavi.amapauto.car",
    )

    private const val DEBOUNCE_MS = 30_000L
    private const val RETURN_DELAY_MS = 1_200L
    @Volatile private var lastLaunchAtMs = 0L
    // Stronger guard than /proc or ActivityManager probes (which firmware may restrict):
    // within one app process run the stock Amap is launched at most once, so reconnects
    // (new CarPlay sessions after a settings change) never flash it again. A fresh app
    // process resets the flag automatically.
    @Volatile private var launchedInCurrentRun = false

    fun launchAndReturn(activity: Activity) =
        launchAndReturnTo(activity, DiPlayActivity::class.java)

    fun launchAndReturnTo(activity: Activity, returnActivity: Class<*>) {
        if (launchedInCurrentRun) {
            // The in-run guard exists so reconnects never flash the Amap over DiPlay again,
            // but if the Amap process has since exited (user closed it, system killed it) the
            // cluster has no feed and the dashboard shows nothing. Re-launch in that case.
            val stillRunning = CANDIDATES.any { isProcessRunning(it) }
            if (stillRunning) {
                Log.i(TAG, "stock Amap already launched in this run, skip")
                return
            }
            Log.i(TAG, "stock Amap launched earlier in this run but process exited, re-launching")
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAtMs < DEBOUNCE_MS) {
            Log.i(TAG, "stock Amap launch debounced (${now - lastLaunchAtMs}ms since last)")
            return
        }
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
        // If the stock Amap process is already up, do not bring it to the foreground again:
        // re-launching only flashes the Amap over DiPlay and risks a double foreground hop.
        // The cluster projection needs the Amap running, not focused.
        // getRunningAppProcesses() is restricted to the caller's own processes since Android
        // 5.1, so on the API 28 head unit it can never see the Amap. /proc/<pid>/cmdline is
        // world-readable and is the reliable probe here; the ActivityManager check is kept as
        // an additional signal where the system exposes it.
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val alreadyRunning = runCatching {
            am?.getRunningAppProcesses()?.any { it.processName.startsWith(target) } == true
        }.getOrDefault(false) || isProcessRunning(target)
        if (alreadyRunning) {
            lastLaunchAtMs = now
            launchedInCurrentRun = true
            Log.i(TAG, "stock Amap $target already running, skipping launch")
            return
        }
        try {
            lastLaunchAtMs = now
            launchedInCurrentRun = true
            activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val app = activity.applicationContext
            Handler(Looper.getMainLooper()).postDelayed({
                // REORDER covers standard activities without recreating them; NEW_TASK
                // makes a singleTask host reuse its instance and raise its task to front.
                val back = Intent(app, returnActivity)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(back)
                Log.i(TAG, "returned to ${returnActivity.simpleName}")
            }, RETURN_DELAY_MS)
            Log.i(TAG, "launched stock Amap $target; returning in ${RETURN_DELAY_MS}ms")
        } catch (error: RuntimeException) {
            Log.w(TAG, "stock Amap launch failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * Scans /proc for a process whose cmdline starts with [target]. /proc/<pid>/cmdline is
     * readable by any app on Android 9, so this works where ActivityManager hides other
     * packages' processes. Any unreadable entry is skipped; on failure the caller falls back
     * to launching the app (harmless, previous behaviour).
     */
    private fun isProcessRunning(target: String): Boolean {
        val proc = File("/proc")
        val entries = runCatching { proc.listFiles() }.getOrNull() ?: return false
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid <= 0) continue
            val cmdline = try {
                File(entry, "cmdline").readBytes().toString(Charsets.UTF_8)
            } catch (_: Exception) {
                continue
            }
            if (cmdline.startsWith(target)) return true
        }
        return false
    }
}
