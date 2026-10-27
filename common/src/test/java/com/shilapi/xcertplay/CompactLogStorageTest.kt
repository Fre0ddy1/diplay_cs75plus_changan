package com.shilapi.xcertplay

import com.freddyq.diplay.customization.DiagnosticPolicy
import com.freddyq.diplay.diagnostics.CompactDiagnosticPolicy
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CompactLogStorageTest {
    private val compact = CompactDiagnosticPolicy()
    private val tiny = object : DiagnosticPolicy {
        override val maxFileBytes = 4096L
        override val archiveCount = 2
        override val maxQueuedLines = 128
        override fun keep(message: String) = true
    }
    @Test fun filteringBeforeQueueKeepsFaultsAndRemovesRoutineData() {
        val folder = Files.createTempDirectory("compact-filter").toFile()
        try {
            val file = folder.resolve("diplay.log")
            SessionLogFile(file, compact).use {
                it.reset("connected")
                it.append("audio stats writeErrors=0")
                it.append("audio stats writeErrors=1")
                it.append("CarPlay audio resource: owner=PHONE")
                it.append("password=private")
            }
            val saved = file.readText()
            assertFalse(saved.contains("writeErrors=0"))
            assertTrue(saved.contains("writeErrors=1"))
            assertTrue(saved.contains("owner=PHONE"))
            assertFalse(saved.contains("private"))
        } finally { folder.deleteRecursively() }
    }
    @Test fun batchesNeverOvershootByteQuotaAndUtf8StaysValid() {
        val folder = Files.createTempDirectory("compact-quota").toFile()
        try {
            SessionLogFile(folder.resolve("diplay.log"), tiny).use {
                it.reset("start")
                repeat(100) { n -> it.append("event=$n " + "路".repeat(180)) }
            }
            assertTrue(folder.listFiles()!!.size <= 3)
            folder.listFiles()!!.forEach {
                assertTrue(it.length() <= 4096)
                assertFalse(it.readText().contains('\uFFFD'))
            }
        } finally { folder.deleteRecursively() }
    }
    @Test fun upgradeRetiresOnlyKnownOldSlotsAndBoundsExistingArchives() {
        val folder = Files.createTempDirectory("compact-upgrade").toFile()
        try {
            (2..7).forEach { folder.resolve("previous-$it.log").writeText("legacy\n".repeat(3000)) }
            folder.resolve("previous.log").writeText("legacy\n".repeat(3000))
            folder.resolve("unrelated.txt").writeText("keep")
            SessionLogFile(folder.resolve("diplay.log"), tiny).use { it.reset("new session") }
            (3..7).forEach { assertFalse(folder.resolve("previous-$it.log").exists()) }
            assertTrue(folder.resolve("previous.log").length() <= 4096)
            assertTrue(folder.resolve("previous-2.log").length() <= 4096)
            assertEquals("keep", folder.resolve("unrelated.txt").readText())
        } finally { folder.deleteRecursively() }
    }
    @Test fun sessionHistoryKeepsOnlyTwoPreviousSessions() {
        val folder = Files.createTempDirectory("compact-history").toFile()
        try {
            repeat(6) { n -> SessionLogFile(folder.resolve("diplay.log"), tiny).use { it.reset("session=$n") } }
            assertEquals(3, folder.listFiles()!!.size)
            assertTrue(folder.resolve("previous-2.log").readText().contains("session=3"))
            assertTrue(folder.resolve("diplay.log").readText().contains("session=5"))
        } finally { folder.deleteRecursively() }
    }
}
