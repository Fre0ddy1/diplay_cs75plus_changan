package com.shilapi.xcertplay
import org.junit.Assert.*
import org.junit.Test

class CrashRecordTest {
    @Test fun nestedAndCyclicCausesRemainBoundedAndKeepOurCallingFrames() {
        val a = IllegalStateException("first"); val b = IllegalArgumentException("second")
        a.initCause(b); b.initCause(a)
        a.stackTrace = (List(25) { StackTraceElement("android.framework.Native", "call", "Native.java", 1) } +
            StackTraceElement("com.freddyq.diplay.Customization", "start", "Customization.kt", 55)).toTypedArray()
        val text = CrashRecord.format("main", a, 123)
        assertTrue(text.contains("atEpochMs=123")); assertTrue(text.contains("Customization.kt:55"))
        assertEquals(1, "java.lang.IllegalStateException".toRegex().findAll(text).count())
        assertTrue(text.length < 5_000)
    }
    @Test fun secretsAreRemovedBeforeWritingAndEachRecordHasAFixedLimit() {
        val record = CrashRecord.safeRecord("crash\npassword=secret\nssid=private\npeer=192.168.5.2\n" + "normal\n".repeat(8_000))
        assertFalse(record.contains("secret")); assertFalse(record.contains("private"))
        assertFalse(record.contains("192.168.5.2")); assertTrue(record.contains("[ip]"))
        assertTrue(record.length <= 16_384)
    }
}
