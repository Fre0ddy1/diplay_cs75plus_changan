package com.freddyq.diplay.hotspot

import com.shilapi.xcertplay.network.CarHotspotTethering.Result
import org.junit.Assert.*
import org.junit.Test

class HotspotStartupWorkerTest {
    private val jobs = mutableListOf<() -> Unit>()
    private var eligible = true
    private var calls = 0
    private var pauses = 0
    private val results = mutableListOf<Result>()
    private fun worker(response: (() -> Boolean) -> Result = { calls++; Result.READY }, pause: () -> Unit = { pauses++ }) =
        HotspotStartupWorker({ jobs += it }, { eligible }, response, pause) { results += it }
    private fun drain() { while (jobs.isNotEmpty()) jobs.removeAt(0).invoke() }
    @Test fun duplicateForegroundEventsShareOneWorker() {
        val worker = worker()
        assertTrue(worker.request()); assertFalse(worker.request()); assertFalse(worker.request())
        drain(); assertEquals(1, calls); assertEquals(listOf(Result.READY), results)
    }
    @Test fun laterForegroundCanRecoverAnotherAutoShutdown() {
        val worker = worker(); worker.request(); drain(); worker.request(); drain()
        assertEquals(2, calls)
    }
    @Test fun changedTransportBeforeDispatchPreventsMutation() {
        val worker = worker(); worker.request(); eligible = false; drain()
        assertEquals(0, calls); assertTrue(results.isEmpty())
    }
    @Test fun userExitOrDisabledPreferenceCancelsQueuedWork() {
        val worker = worker(); worker.request(); worker.cancel(); drain()
        assertEquals(0, calls); assertTrue(results.isEmpty())
    }
    @Test fun transientFailureGetsOnlyOneRetry() {
        val worker = worker({ calls++; Result.TIMED_OUT }); worker.request(); drain()
        assertEquals(2, calls); assertEquals(1, pauses); assertEquals(listOf(Result.TIMED_OUT), results)
    }
    @Test fun secondObservationCanCompleteWithoutAnotherChange() {
        val worker = worker({ calls++; if (calls == 1) Result.FAILED else Result.READY }); worker.request(); drain()
        assertEquals(2, calls); assertEquals(listOf(Result.READY), results)
    }
    @Test fun permissionAndUnsupportedResultsNeverRepeatBlindly() {
        for (result in listOf(Result.PERMISSION_REQUIRED, Result.UNSUPPORTED)) {
            calls = 0; pauses = 0
            val worker = worker({ calls++; result }); worker.request(); drain()
            assertEquals(1, calls); assertEquals(0, pauses)
        }
    }
    @Test fun disablingDuringBackoffStopsSecondRequest() {
        val worker = worker({ calls++; Result.FAILED }, pause = { eligible = false }); worker.request(); drain()
        assertEquals(1, calls); assertTrue(results.isEmpty())
    }
    @Test fun inFlightEnableGetsLiveCancellationPredicate() {
        lateinit var worker: HotspotStartupWorker
        worker = worker({ cancelled -> calls++; worker.cancel(); assertTrue(cancelled()); Result.CANCELLED })
        worker.request(); drain(); assertEquals(1, calls); assertTrue(results.isEmpty())
    }
    @Test fun requestExceptionReleasesBusyStateWithoutProcessCrash() {
        val worker = worker({ error("firmware exception") }); worker.request(); drain()
        assertEquals(listOf(Result.FAILED), results); assertTrue(worker.request())
    }
}
