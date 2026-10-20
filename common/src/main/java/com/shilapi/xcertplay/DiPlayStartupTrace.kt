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

    fun markProcessStart() {
        processStartMs = runCatching { Process.getStartElapsedRealtime() }.getOrDefault(0L)
    }

    fun log(context: Context, label: String) {
        val now = SystemClock.elapsedRealtime()
        val base = processStartMs
        val delta = if (base > 0L && now >= base) now - base else -1L
        val line = "${stamp()}  Startup: $label at +${delta}ms"
        Log.i("DiPlayStartup", line)
        runCatching {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            val file = File(dir, "startup.log")
            // Keep the previous run: the interesting case is often the second launch.
            if (file.length() > 64 * 1024) file.renameTo(File(dir, "startup-previous.log"))
            file.appendText(line + "\n")
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
}
