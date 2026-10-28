package com.freddyq.diplay.hotspot

import com.shilapi.xcertplay.network.CarHotspotTethering.Result
import java.util.concurrent.atomic.AtomicLong

/** Coalesce foreground events, retry one transient failure, never run an unbounded watchdog. */
internal class HotspotStartupWorker(
    private val execute: (() -> Unit) -> Unit,
    private val eligible: () -> Boolean,
    private val enable: (() -> Boolean) -> Result,
    private val pause: () -> Unit = { Thread.sleep(2_000) },
    private val finished: (Result) -> Unit,
) {
    private val generation = AtomicLong()
    @Volatile private var running = false
    @Synchronized fun request(): Boolean {
        if (running) return false
        running = true
        val ticket = generation.get()
        val cancelled = { ticket != generation.get() || !eligible() }
        try {
            execute {
                try {
                    var result = if (cancelled()) Result.CANCELLED else enable(cancelled)
                    if (result in setOf(Result.FAILED, Result.TIMED_OUT) && !cancelled()) {
                        pause()
                        if (!cancelled()) result = enable(cancelled) else result = Result.CANCELLED
                    }
                    if (!cancelled()) finished(result)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: Exception) {
                    if (!cancelled()) finished(Result.FAILED)
                } finally { running = false }
            }
        } catch (error: Exception) { running = false; throw error }
        return true
    }
    fun cancel() { generation.incrementAndGet() }
}
