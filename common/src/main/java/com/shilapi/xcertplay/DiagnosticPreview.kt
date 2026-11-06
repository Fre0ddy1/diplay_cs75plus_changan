package com.shilapi.xcertplay

/** Upstream #1132, with a total preview budget. Export still uses the complete report. */
internal object DiagnosticPreview {
    const val MAX_CHARS = 200_000
    private const val HEAD_MAX = 20_000
    fun text(report: String): String {
        if (report.length <= MAX_CHARS) return report
        val firstLog = SessionLogFile.REPORT_NAMES.mapNotNull { name ->
            val marker = "--- $name ---"
            if (report.startsWith(marker)) 0 else report.indexOf("\n$marker").takeIf { it >= 0 }?.plus(1)
        }.minOrNull() ?: 0
        var headEnd = minOf(firstLog, HEAD_MAX)
        if (headEnd > 0 && Character.isHighSurrogate(report[headEnd - 1])) headEnd--
        val marker = "…\n"
        val budget = MAX_CHARS - headEnd - marker.length
        var start = report.length - budget
        val nextLine = report.indexOf('\n', start - 1) + 1
        if (nextLine > 0 && report.length - nextLine >= budget / 2) start = nextLine
        else if (Character.isLowSurrogate(report[start])) start++
        return report.substring(0, headEnd) + marker + report.substring(start)
    }
}
