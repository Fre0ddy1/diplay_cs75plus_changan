package com.shilapi.xcertplay
import org.junit.Assert.*
import org.junit.Test

class DiagnosticPreviewTest {
    @Test fun smallReportIsUnchanged() {
        val report = "version\n--- diplay.log ---\nlatest"
        assertEquals(report, DiagnosticPreview.text(report))
    }
    @Test fun previewPreservesSummaryAndLatestEvidenceWithoutChangingExport() {
        val report = "version and settings\n--- diplay.log ---\n" + "old data\n".repeat(60_000) + "LATEST FAILURE"
        val preview = DiagnosticPreview.text(report)
        assertTrue(preview.startsWith("version and settings\n…\n"))
        assertTrue(preview.endsWith("LATEST FAILURE"))
        assertTrue(preview.length <= DiagnosticPreview.MAX_CHARS)
        assertTrue(report.length > 500_000)
    }
    @Test fun hugeHeaderOrMissingMarkerCannotBypassTheLimit() {
        for (report in listOf("x".repeat(500_000), "head\n".repeat(80_000) + "--- diplay.log ---\nTAIL")) {
            val preview = DiagnosticPreview.text(report)
            assertTrue(preview.length <= DiagnosticPreview.MAX_CHARS)
            assertTrue(report.endsWith(preview.substringAfter("…\n")))
        }
    }
    @Test fun longLastLineAndUnicodeCutsStayValid() {
        val report = "settings\n--- diplay.log ---\n" + "🙂".repeat(160_000)
        val preview = DiagnosticPreview.text(report)
        assertTrue(preview.length <= DiagnosticPreview.MAX_CHARS)
        assertEquals(preview, String(preview.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertTrue(preview.endsWith("🙂"))
    }
}
