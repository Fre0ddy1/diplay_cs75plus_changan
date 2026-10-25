package com.freddyq.diplay.changan

import com.freddyq.diplay.customization.StandardNavigationGuidance
import com.shilapi.xcertplay.customization.PhoneNavigationDecoder

/** The S311_MCA broadcast receiver, verified on the Android 9 Wutong firmware. */
internal object ChanganHudProfile {
    const val RECEIVER_PACKAGE = "com.incall.interactive"
    const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"

}

internal data class ChanganHudFrame(
    val icon: Int,
    val distanceMeters: Int,
    val road: String,
    val currentRoad: String,
    val roundaboutExit: Int,
) {
    companion object {
        fun from(maneuver: StandardNavigationGuidance): ChanganHudFrame? {
            // This is AMap NEW_ICON, not the BYD HUD arrow or a vehicle-property icon.
            val mapped = maneuver
            if (mapped.icon == 0) return null
            return ChanganHudFrame(
                // The receiver itself uses 131071 as its out-of-range distance sentinel.
                mapped.icon, mapped.distanceMeters.coerceIn(0, 131071),
                roadName(maneuver.nextRoad), roadName(maneuver.currentRoad),
                mapped.roundaboutExit.takeIf { it in 0..10 } ?: 0,
            )
        }

        /** The receiver copies 64 UTF-8 bytes without preserving character boundaries. */
        internal fun roadName(value: String): String {
            val text = value.replace('\u0000', ' ').replace('\n', ' ').replace('\r', ' ').trim()
            val out = StringBuilder()
            var bytes = 0
            var offset = 0
            while (offset < text.length) {
                val codePoint = text.codePointAt(offset)
                val character = String(Character.toChars(codePoint))
                val size = character.toByteArray(Charsets.UTF_8).size
                if (bytes + size > 64) break
                out.append(character)
                bytes += size
                offset += Character.charCount(codePoint)
            }
            return out.toString()
        }
    }
}

internal sealed class ChanganHudPacket {
    data class State(val value: Int) : ChanganHudPacket()
    data class Guidance(val frame: ChanganHudFrame) : ChanganHudPacket()
}

internal data class ChanganHudProjection(val revision: Long = 0, val quietUntilNs: Long = Long.MIN_VALUE)

/** Change-driven guidance remains valid until the phone or CarPlay session ends it. */
internal class ChanganHudSession(
    private val nanoTime: () -> Long = System::nanoTime,
    private val projection: () -> ChanganHudProjection = { ChanganHudProjection() },
    private val emit: (ChanganHudPacket) -> Boolean,
) {
    private val route = PhoneNavigationDecoder(nanoTime, retainGuidanceUntilSessionEnds = true)
    private var accepting = false
    private var ownsOutput = false
    private var needsStart = true
    private var lastFrame: ChanganHudFrame? = null
    private var lastSentNs: Long? = null
    private var projectionRevision = 0L
    private var lastExternalRecoveryNs: Long? = null

    fun start() {
        stop()
        accepting = true
    }

    fun onFrame(messageId: Int, payload: ByteArray, enabled: Boolean) {
        if (!accepting) return
        route.accept(messageId, payload)
        tick(enabled)
    }

    fun tick(enabled: Boolean) {
        if (!accepting) return
        val frame = if (enabled) route.current()?.let(ChanganHudFrame::from) else null
        if (frame == null) {
            finishOutput()
            return
        }
        val now = nanoTime()
        val transition = projection()
        if (transition.revision != projectionRevision) {
            projectionRevision = transition.revision
            needsStart = true
            lastFrame = null
        }
        // Projection retains its existing handshake. Do not repeatedly counter its clear states.
        if (now < transition.quietUntilNs) return
        if (needsStart) {
            if (!emit(ChanganHudPacket.State(8))) return
            ownsOutput = true
            needsStart = false
        }
        if (frame == lastFrame && lastSentNs?.let { now - it < REPEAT_NS } == true) return
        if (emit(ChanganHudPacket.Guidance(frame))) {
            lastFrame = frame
            lastSentNs = now
        }
    }

    fun stop() {
        accepting = false
        route.clear()
        finishOutput()
        lastExternalRecoveryNs = null
    }

    /** Restore only our still-valid phone guidance, never foreign data or an ended route. */
    fun recoverFromExternalReset(enabled: Boolean): Boolean {
        if (!accepting || !ownsOutput || !enabled || route.current()==null) return false
        val now = nanoTime()
        if (now < projection().quietUntilNs) return false
        if (lastExternalRecoveryNs?.let { now-it < EXTERNAL_RECOVERY_MIN_NS } == true) return false
        lastExternalRecoveryNs = now
        needsStart = true
        lastFrame = null
        lastSentNs = null
        tick(enabled)
        return !needsStart
    }

    private fun finishOutput() {
        // An idle/disabled output must never clear another navigation application's guidance.
        if (ownsOutput && !emit(ChanganHudPacket.State(9))) return
        ownsOutput = false
        needsStart = true
        lastFrame = null
        lastSentNs = null
    }

    companion object {
        private const val REPEAT_NS = 1_000_000_000L
        private const val EXTERNAL_RECOVERY_MIN_NS = 250_000_000L
        fun projectionStateClearsGuidance(state: Int): Boolean = state in setOf(0, 9, 12, 25, 311)
    }
}
