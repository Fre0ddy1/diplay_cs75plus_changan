package com.shilapi.xcertplay

import android.content.Context

/**
 * How much of a session's diagnostics reaches `diplay.log`.
 *
 * The log used to take everything, and most of what it took was chatter: in a three-minute field
 * sample the iAP2 notifications alone were 237 lines, the per-stream video statistics another 130.
 * Every one of those lines costs a redaction pass, a queue slot and a write on the head unit's
 * flash - and none of it is read while the picture and the sound are fine.
 *
 * So the full log is now opt-in: **诊断 → 完整日志写入** ("full session log"), off by default. With
 * it off only the events a bug report actually needs survive - connection state, the moments
 * navigation switches the dashboard, wheel-key presses, the audio buffer summary, and the first
 * frame of each screen stream. Everything below is still one switch away for the sessions where the
 * answer is inside a noisy family (a stuck 0x5201 route update, a stream that is received but never
 * decoded).
 */
internal object SessionLogPolicy {
    /**
     * True when the driver asked for the complete log.
     *
     * Read on every line, so it is volatile. Defaults to true so anything that runs without the app
     * applying a preference (unit tests, tools) keeps writing exactly what it wrote before; the
     * CarPlay session always installs the stored value before it opens the log.
     */
    @Volatile
    var full: Boolean = true

    /** Applies the stored preference. Same process as the CarPlay session, so it takes effect now. */
    fun applyStoredPreference(context: Context) {
        full = AirPlayPersistence.loadFullSessionLog(context)
    }

    fun shouldWrite(line: String): Boolean = full || !isChatter(line)

    /**
     * Families that only ever matter while debugging.
     *
     * Matched together with the two spaces that follow the timestamp, so a marker can never hit a
     * message body that merely mentions the same words.
     */
    private val CHATTER = listOf(
        // iAP2 notifications, the single biggest family: route updates alone arrive several times a
        // second (field 2026-10-29: 237 lines in three minutes). The milestones around them -
        // identification accepted, authentication accepted, availability, tunnel closed - are rare
        // and stay.
        "  iAP tunnel iap2 rx=0x",
        "  iAP tunnel iap2 tx=0x",
        "  iAP tunnel iap2 subscriptions sent",
        // The same notifications when they reach the log through another route: vehicle status,
        // location, MFi authentication steps, and the artwork transfers (each carries a whole JPEG).
        "  iap2 rx=0x",
        "  iap2 tx=0x",
        "  iap2 mfi",
        "  iap2 artwork",
        // The 0x5201/0x5204 route dump behind the dashboard's turn card.
        "  Route trace: ",
        // Per-stream receive counters, one line per stream every five seconds.
        "  Receive: ",
        // Per-stream video statistics: the "every H.264 stream" lines. Each stream's first frame is
        // a separate line and is kept.
        "  Video: video stats",
        // Periodic decoder bookkeeping and clock-trim results. The buffer summary ("audio stats")
        // and a clock-trim reset (a real discontinuity) stay.
        "  Audio: decoder stats",
        "  Audio: clock trim off",
        // RTSP/plist chatter. Stream setup and teardown stay: they mark when the phone added or
        // dropped a stream.
        "  airplay rx ",
        "  airplay tx ",
        "  airplay command",
        "  AirPlay command",
        "  airplay modesChanged",
        "  AirPlay modesChanged",
        "  airplay suggestUI",
        "  airplay SETUP keys=",
        "  airplay SETUP response",
        "  airplay audio ",
        "  airplay screen ",
        "  airplay video ",
        "  airplay /info",
        // Input and theme polling.
        "  Touch: ",
        "  THEME_DIAGNOSTIC sample",
    )

    private fun isChatter(line: String): Boolean = CHATTER.any { line.contains(it) }
}
