package com.shilapi.xcertplay

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Start-up timing, appended to `logs/startup.log`.
 *
 * The driver reports "tapping the icon takes a while before anything happens", and there was no
 * number behind it: the session log only starts when the CarPlay screen comes up, so everything
 * before that - Application, the activity's own layout, the authentication bootstrap - was
 * invisible. Each mark carries the time since the process started, so the export shows exactly
 * which step costs what. Kept in its own file because the session log is reset on every CarPlay
 * screen entry.
 */
internal object DiPlayStartupTrace {
    private var processStartMs = 0L

    /**
     * `first frame drawn` is the only mark driven by a `ViewTreeObserver.OnDrawListener`. Removing
     * that listener from inside `onDraw` cannot stop the callbacks already dispatched for the same
     * frame, and a field log showed 1482 copies of the line on one entry, which rotates the 64 KB
     * file and buries a real launch. Report it once per process, whatever calls it.
     */
    @Volatile
    private var firstFrameReported = false

    fun markProcessStart() {
        processStartMs = runCatching { Process.getStartElapsedRealtime() }.getOrDefault(0L)
    }

    /**
     * Returns false when [label] was already reported and no line was written. Only
     * [FIRST_FRAME_LABEL] is deduplicated; every other mark keeps its own meaning.
     */
    fun log(context: Context, label: String): Boolean {
        if (label == FIRST_FRAME_LABEL && firstFrameReported) return false
        val now = SystemClock.elapsedRealtime()
        val base = processStartMs
        val delta = if (base > 0L && now >= base) now - base else -1L
        val line = "${stamp()}  Startup: $label at +${delta}ms"
        runCatching {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            val file = File(dir, "startup.log")
            // Keep the previous run: the interesting case is often the second launch.
            if (file.length() > 64 * 1024) file.renameTo(File(dir, "startup-previous.log"))
            file.appendText(line + "\n")
        }
        if (label == FIRST_FRAME_LABEL) firstFrameReported = true
        Log.i("DiPlayStartup", line)
        return true
    }

    private const val FIRST_FRAME_LABEL = "first frame drawn"

    private fun stamp(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
}
