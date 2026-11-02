package com.shilapi.xcertplay.media

import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Exercise the real decoder worker; no codec or display is needed to close an idle worker. */
class VideoDecoderShutdownTest {
    @Test fun repeatedCloseReleasesEveryPendingSurfaceBarrierWithoutCrashing() = closeWhileInterrupted(3)
    @Test fun interruptedCleanupAlsoWorksWithAnEmptyQueue() = closeWhileInterrupted(0)

    private fun closeWhileInterrupted(pendingBarriers: Int) {
        val type = Class.forName("com.shilapi.xcertplay.media.VideoDecoder")
        val constructor = type.declaredConstructors.single { it.parameterCount == 9 }.apply { isAccessible = true }
        val requestKeyFrame: () -> Unit = {}
        val report: (String) -> Unit = {}
        val decoder = constructor.newInstance(110, null, 1280, 720, false, requestKeyFrame, report, null, null) as Closeable
        val worker = type.getDeclaredField("thread").apply { isAccessible = true }.get(decoder) as Thread
        val setSurface = type.declaredMethods.single { it.name == "setSurface" }.apply { isAccessible = true }
        val failure = AtomicReference<Throwable>()
        val callbacks = AtomicInteger()
        val callbackUnderWorkerLock = AtomicBoolean()
        worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> failure.set(error) }
        try {
            synchronized(decoder) {
                decoder.close()
                // releaseDecoder/the final barrier drain needs this monitor. Hold the worker here
                // so the second close deterministically interrupts cleanup, not its normal wait.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (worker.state != Thread.State.BLOCKED && worker.isAlive && System.nanoTime() < deadline) Thread.sleep(1)
                assertEquals("worker must reach cleanup", Thread.State.BLOCKED, worker.state)
                repeat(pendingBarriers) {
                    val applied: () -> Unit = {
                        if (Thread.holdsLock(decoder)) callbackUnderWorkerLock.set(true)
                        callbacks.incrementAndGet()
                    }
                    setSurface.invoke(decoder, null, applied)
                }
                decoder.close()
            }
            worker.join(3_000)
            assertFalse("decoder worker must terminate", worker.isAlive)
            assertNull("shutdown must not escape to the app crash handler", failure.get())
            assertEquals(pendingBarriers, callbacks.get())
            assertFalse("detach callbacks must remain outside the worker monitor", callbackUnderWorkerLock.get())
        } finally {
            decoder.close()
            worker.join(3_000)
        }
    }
}
