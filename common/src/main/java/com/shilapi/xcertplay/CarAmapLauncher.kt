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
    private var lastReadiness: String? = null

    fun launchAndReturn(activity: Activity) =
        launchAndReturnTo(activity, DiPlayActivity::class.java)

    /**
     * Readiness probe for the dashboard projection bring-up: true when there is nothing to wait
     * for (no stock car Amap installed at all) or the Amap process is up. Feiyu OS only keeps the
     * instrument-cluster projection channel alive while the stock Amap runs, so the cold-start
     * auto projection must not fire before this turns true (see the retry/readiness gate in
     * CarPlayHostActivity).
     */
    fun stockAmapReady(context: Context): Boolean {
        val pm = context.packageManager
        val candidates = CANDIDATES.filter { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }
        if (candidates.isEmpty()) return true
        val states = candidates.map(::processPresence)
        val state = when {
            ProcessPresence.RUNNING in states -> ProcessPresence.RUNNING
            ProcessPresence.UNKNOWN in states -> ProcessPresence.UNKNOWN
            else -> ProcessPresence.ABSENT
        }
        val description = state.name
        if (lastReadiness != description) {
            lastReadiness = description
            Log.i(TAG, "stock Amap presence=$state; unknown uses a bounded launcher grace, not the 25s gate")
        }
        return StockAmapProcessProbe.ready(state, SystemClock.elapsedRealtime() - lastLaunchAtMs)
    }

    fun launchAndReturnTo(activity: Activity, returnActivity: Class<*>) {
        if (launchedInCurrentRun) {
            // The in-run guard exists so reconnects never flash the Amap over DiPlay again,
            // but if the Amap process has since exited (user closed it, system killed it) the
            // cluster has no feed and the dashboard shows nothing. Re-launch in that case.
            val definitelyExited = CANDIDATES.all { processPresence(it) == ProcessPresence.ABSENT }
            if (!definitelyExited) {
                Log.i(TAG, "stock Amap launched in this run; running or visibility unknown, skip duplicate launch")
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
        // ActivityManager is only a positive hint. /proc may use hidepid and cannot prove
        // absence without visibility; never interpret a hidden process as a dead process.
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val alreadyRunning = runCatching {
            am?.getRunningAppProcesses()?.any { it.processName.startsWith(target) } == true
        }.getOrDefault(false) || processPresence(target) == ProcessPresence.RUNNING
        if (alreadyRunning) {
            lastLaunchAtMs = now
            launchedInCurrentRun = true
            Log.i(TAG, "stock Amap $target already running, skipping launch")
            return
        }
        try {
            lastLaunchAtMs = now
            activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            launchedInCurrentRun = true
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
     * Only a complete, permitted scan can establish absence. The S311 mounts /proc with
     * hidepid=2,gid=3009, while the normal app has no group3009. ADB run-as retains that
     * debugger group and is therefore not an equivalent visibility test.
     */
    private fun processPresence(target: String): ProcessPresence {
        val mounts = runCatching { File("/proc/mounts").readText() }.getOrNull()
        val groups = StockAmapProcessProbe.groups(runCatching { File("/proc/self/status").readText() }.getOrNull())
        if (StockAmapProcessProbe.restrictedProc(mounts, groups)) return ProcessPresence.UNKNOWN
        val proc = File("/proc")
        val entries = runCatching { proc.listFiles() }.getOrNull() ?: return ProcessPresence.UNKNOWN
        val cmdlines = mutableListOf<String>()
        var complete = true
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid <= 0) continue
            val cmdline = try {
                File(entry, "cmdline").readBytes().toString(Charsets.UTF_8)
            } catch (_: Exception) {
                complete = false
                continue
            }
            if (cmdline.substringBefore('\u0000') == target) return ProcessPresence.RUNNING
            cmdlines.add(cmdline)
        }
        return StockAmapProcessProbe.classify(target, cmdlines, complete)
    }
}
