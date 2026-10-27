package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** Coalesces Android delivery paths without changing the audio route or focus. */
internal class WheelMediaKeyRouter(private val diagnostic: (String) -> Unit = {}) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastIndex = -1
    private var lastAt = -1L
    private var pending: Runnable? = null
    private var pendingIndex = -1
    private var state = CarPlayNowPlaying()

    fun route(index: Int, preferExternal: Boolean, send: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (index == lastIndex && lastAt >= 0 && now - lastAt < DEBOUNCE_MS) return
        lastIndex = index
        lastAt = now
        // A second deliberate action is retained rather than silently lost behind a timer.
        pending?.let { handler.removeCallbacks(it); it.run() }
        if (!preferExternal || index !in setOf(CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.PLAY_PAUSE)) {
            send()
            return
        }
        pendingIndex = index
        pending = Runnable {
            pending = null
            pendingIndex = -1
            diagnostic("no external playback change within ${EXTERNAL_WAIT_MS}ms; send one CarPlay key")
            send()
        }.also { handler.postDelayed(it, EXTERNAL_WAIT_MS) }
    }

    /** True only for feedback to a recent user key, never ordinary playback progress. */
    fun observe(next: CarPlayNowPlaying): Boolean {
        val trackChanged = state.title != next.title || state.artist != next.artist || state.album != next.album
        val restarted = state.elapsedMillis?.let { before ->
            next.elapsedMillis?.let { after -> before > 2_000 && after + 1_000 < before }
        } == true
        val acted = when (pendingIndex) {
            CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS -> trackChanged || restarted
            CarPlayMediaButton.PLAY_PAUSE -> state.playing != next.playing
            else -> false
        }
        state = next
        if (acted) {
            pending?.let(handler::removeCallbacks)
            pending = null
            pendingIndex = -1
            diagnostic("external playback changed; suppress duplicate CarPlay key")
        }
        return acted
    }

    fun cancel() {
        pending?.let(handler::removeCallbacks)
        pending = null
        pendingIndex = -1
        lastIndex = -1
        lastAt = -1L
        state = CarPlayNowPlaying()
    }

    companion object {
        const val DEBOUNCE_MS = 250L
        const val EXTERNAL_WAIT_MS = 1_000L
    }
}
