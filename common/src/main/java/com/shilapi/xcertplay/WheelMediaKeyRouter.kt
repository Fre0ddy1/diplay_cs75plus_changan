package com.shilapi.xcertplay

import android.os.SystemClock

/** One selected key destination; metadata never acknowledges an audio route or schedules HID. */
internal class WheelMediaKeyRouter(private val diagnostic: (String) -> Unit = {}) {
    private var lastIndex = -1
    private var lastAt = -1L
    private var lastExternal = false
    fun route(index: Int, preferExternal: Boolean, send: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (index == lastIndex && preferExternal == lastExternal && lastAt >= 0 && now - lastAt < DEBOUNCE_MS) return
        lastIndex = index; lastAt = now; lastExternal = preferExternal
        if (preferExternal) {
            diagnostic("external-only media-key mode; no CarPlay key or delayed fallback")
            return
        }
        send()
    }
    fun cancel() { lastIndex = -1; lastAt = -1L }
    companion object { const val DEBOUNCE_MS = 250L }
}
