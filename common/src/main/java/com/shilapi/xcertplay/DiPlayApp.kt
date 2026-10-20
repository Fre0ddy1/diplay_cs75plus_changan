package com.shilapi.xcertplay

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records any uncaught exception (crash) into the on-device diagnostic log before the
 * process dies, so the next exported diplay.log / previous.log contains the crash stack.
 * The car head-unit has no logcat access from the driver's seat, so without this the
 * in-app diagnostic logs never show why the app died. The crash line is appended to
 * diplay.log; on the next start the log reset rotates it into previous.log, so both the
 * freshly exported log and the archived ones carry the stack.
 */
class DiPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiPlayStartupTrace.markProcessStart()
        // Runs before any Activity reads a preference, so a shipped default change reaches installs
        // that already stored the previous value.
        runCatching { AirPlayPersistence.applyDefaultsRevision(this) }
        DiPlayStartupTrace.log(this, "Application.onCreate")
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val logDir = File(filesDir, "logs")
                logDir.mkdirs()
                val crashLog = File(logDir, "diplay.log")
                val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                val stack = Log.getStackTraceString(throwable)
                crashLog.appendText("\n===== CRASH $stamp on ${thread.name} =====\n$stack\n")
                Log.e("DiPlayCrash", "uncaught on ${thread.name}", throwable)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
