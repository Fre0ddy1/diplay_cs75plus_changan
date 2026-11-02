package com.freddyq.diplay.changan.audio

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class MediaAudioPolicyLeaseTest {
    private val address = "02:11:22:33:44:55"
    private val other = "02:11:22:33:44:66"
    private val owner = Any()
    private val priorities = mutableMapOf(address to 100, other to 100)
    private val writes = mutableListOf<Pair<String, Int>>()
    private val disconnects = mutableListOf<String>()
    private var saved: SavedMediaPolicy? = null
    private var allowJournal = true
    private var allowBind = true
    private var immediate = true
    private var acceptWrite = true
    private var queryAvailable = true
    private var ready: (() -> Unit)? = null
    private var closes = 0
    private val log = mutableListOf<String>()
    private val journal = object : MediaPolicyJournal {
        override fun read() = saved
        override fun save(value: SavedMediaPolicy): Boolean { if (allowJournal) saved = value; return allowJournal }
        override fun clear(): Boolean { saved = null; return true }
    }
    private val lease = MediaAudioPolicyLease({ object : MediaAudioPolicyPort {
        override fun start(ready: () -> Unit): Boolean {
            this@MediaAudioPolicyLeaseTest.ready = ready
            if (allowBind && immediate) ready()
            return allowBind
        }
        override fun priority(address: String) = priorities[address].takeIf { queryAvailable }
        override fun setPriority(address: String, value: Int): Boolean {
            // Recovery must already be durable at the moment the setting changes.
            if (value == 0) assertNotNull(saved)
            writes += address to value
            if (acceptWrite) priorities[address] = value
            return acceptWrite
        }
        override fun disconnectMedia(address: String): Boolean { disconnects += address; return true }
        override fun close() { closes++ }
    } }, journal, log::add)

    @Test fun selectedPhoneOnlyAndOriginalValueRestored() {
        lease.begin(owner, address)
        assertEquals(listOf(address to 0), writes)
        assertEquals(listOf(address), disconnects)
        assertEquals(100, priorities[other])
        lease.end(owner)
        assertEquals(listOf(address to 0, address to 100), writes)
        assertNull(saved)
    }
    @Test fun originallyDisabledIsNeverEnabledByDisconnect() {
        priorities[address] = 0
        lease.begin(owner, address); lease.end(owner)
        assertTrue(writes.isEmpty()); assertTrue(disconnects.isEmpty()); assertNull(saved)
    }
    @Test fun autoConnectPriorityIsRestoredExactly() {
        priorities[address] = 1000
        lease.begin(owner, address); lease.end(owner)
        assertEquals(listOf(address to 0, address to 1000), writes)
    }
    @Test fun failedJournalDoesNotModifyVehicleSetting() {
        allowJournal = false
        lease.begin(owner, address)
        assertTrue(writes.isEmpty()); assertTrue(disconnects.isEmpty())
    }
    @Test fun unknownOriginalPolicyDoesNotModifySetting() {
        priorities[address] = -1
        lease.begin(owner, address)
        assertTrue(writes.isEmpty()); assertNull(saved)
    }
    @Test fun missingServiceDoesNotFallBackToRadioOrOtherProfiles() {
        allowBind = false
        lease.begin(owner, address)
        assertTrue(writes.isEmpty()); assertTrue(disconnects.isEmpty())
    }
    @Test fun stoppedConnectionCannotApplyAfterLateBinding() {
        immediate = false
        lease.begin(owner, address); val late = ready!!
        lease.end(owner); late()
        assertTrue(writes.isEmpty()); assertEquals(1, closes)
    }
    @Test fun oldOwnerCannotRestoreNewConnection() {
        lease.begin(owner, address)
        val next = Any(); lease.begin(next, other)
        val before = writes.toList(); lease.end(owner)
        assertEquals(before, writes)
        assertEquals(0, priorities[other]); lease.end(next)
        assertEquals(100, priorities[other])
    }
    @Test fun sameOwnerDoesNotRepeatedlyDisconnect() {
        repeat(8) { lease.begin(owner, address) }
        assertEquals(1, writes.size); assertEquals(1, disconnects.size)
    }
    @Test fun userSettingChangeWinsOverOurRestoration() {
        lease.begin(owner, address)
        priorities[address] = 1000
        lease.end(owner)
        assertEquals(listOf(address to 0), writes)
        assertEquals(1000, priorities[address]); assertNull(saved)
    }
    @Test fun disablingNewOptionRestoresButDoesNotAlterMediaKeyChoice() {
        lease.begin(owner, address); lease.configure(false)
        assertEquals(100, priorities[address]); assertNull(saved)
        lease.configure(true)
        assertEquals(0, priorities[address]); lease.end(owner)
    }
    @Test fun startupRecoversJournalWithoutAConnectionOrMediaDisconnect() {
        saved = SavedMediaPolicy(address, 100); priorities[address] = 0
        lease.recover()
        assertEquals(listOf(address to 100), writes)
        assertTrue(disconnects.isEmpty()); assertNull(saved)
    }
    @Test fun exitCompletionRunsAfterRestoration() {
        lease.begin(owner, address)
        var completed = false
        lease.prepareExit { assertEquals(100, priorities[address]); completed = true }
        assertTrue(completed); assertNull(saved)
        lease.begin(Any(), other); assertEquals(100, priorities[other])
    }
    @Test fun failedRestoreIsRetainedAndBlocksOverwritingRecoveryForAnotherPhone() {
        lease.begin(owner, address); acceptWrite = false
        lease.begin(Any(), other)
        assertEquals(SavedMediaPolicy(address, 100), saved)
        assertFalse(writes.contains(other to 0))
    }
    @Test fun exitTimeoutKeepsUnrestoredJournalForNextProcess() {
        saved = SavedMediaPolicy(address, 100); priorities[address] = 0; immediate = false
        var calls = 0; lease.prepareExit { calls++ }
        assertEquals(0, calls)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1500, TimeUnit.MILLISECONDS)
        assertEquals(1, calls); assertNotNull(saved)
        ready!!(); assertEquals(1, calls); assertEquals(0, priorities[address]); assertNotNull(saved)
    }
}
