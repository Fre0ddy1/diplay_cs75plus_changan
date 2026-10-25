package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class StockAmapProcessProbeTest {
    private val pkg = "com.autonavi.amapauto"
    @Test fun groupsComeFromTheActualAppProcess() =
        assertEquals(setOf(3001,3002,3003), StockAmapProcessProbe.groups("Name: app\nGroups:\t3001 3002 3003\n"))
    @Test fun s311RuntimeGroupsCannotSeeSystemMapProcesses() =
        assertTrue(StockAmapProcessProbe.restrictedProc("proc /proc proc rw,gid=3009,hidepid=2 0 0", setOf(3001,3002,3003)))
    @Test fun adbReadprocGroupDoesNotRepresentTheNormalApp() =
        assertFalse(StockAmapProcessProbe.restrictedProc("proc /proc proc rw,gid=3009,hidepid=2 0 0", setOf(3009)))
    @Test fun inaccessibleMountOrScanMeansUnknown() {
        assertTrue(StockAmapProcessProbe.restrictedProc(null, emptySet()))
        assertEquals(ProcessPresence.UNKNOWN, StockAmapProcessProbe.classify(pkg, null, false))
        assertEquals(ProcessPresence.UNKNOWN, StockAmapProcessProbe.classify(pkg, emptyList(), false))
    }
    @Test fun onlyMainProcessEstablishesRunning() {
        assertEquals(ProcessPresence.RUNNING, StockAmapProcessProbe.classify(pkg, listOf(pkg+"\u0000"), false))
        assertEquals(ProcessPresence.ABSENT, StockAmapProcessProbe.classify(pkg, listOf(pkg+":push", pkg+"evil"), true))
    }
    @Test fun completeVisibleScanCanEstablishAbsence() =
        assertEquals(ProcessPresence.ABSENT, StockAmapProcessProbe.classify(pkg, emptyList(), true))
    @Test fun unknownDoesNotWaitForTwentyFiveSeconds() {
        assertFalse(StockAmapProcessProbe.ready(ProcessPresence.UNKNOWN, 1999))
        assertTrue(StockAmapProcessProbe.ready(ProcessPresence.UNKNOWN, 2000))
    }
    @Test fun confirmedMissingProcessStillWaitsWhileRunningIsImmediatelyReady() {
        assertFalse(StockAmapProcessProbe.ready(ProcessPresence.ABSENT, 60_000))
        assertTrue(StockAmapProcessProbe.ready(ProcessPresence.RUNNING, 0))
    }
}
