package com.shilapi.xcertplay.hud

internal data class BydHudGuidance(
    val distanceMeters: Int,
    /** Native HUD arrow (field 28). */
    val maneuver: Int,
    /** Gaode maneuver code: selects the HUD icon (field 8). */
    val gaode: Int = 0,
    val road: String = "",
    val arrivalEpochSeconds: Long? = null,
)

internal data class BydAppleManeuver(
    val distanceMeters: Int,
    val type: Int,
    val drivingSide: Int,
    val road: String = "",
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    val arrivalEpochSeconds: Long? = null,
    /** Road the driver is on now (0x5201 t=0x03), for the road-name card. */
    val currentRoad: String = "",
    /** Highway exit label from 0x5202 (best-effort decode, raw trace stays in the log). */
    val exitLabel: String = "",
)

internal enum class BydHudRouteChange {
    NONE,
    GUIDANCE,
    CLEAR,
}

/**
 * Lane guidance decoded from 0x5204 LaneGuidanceInfoUpdate (Apple CPLaneGuidance).
 * [lanes] holds one bitmask per lane (in road order, left to right); each bit selects a
 * permitted direction: bit0 straight, bit1 left, bit2 right, bit3 slight-left, bit4
 * slight-right, bit5 sharp-left, bit6 sharp-right, bit7 U-turn. [currentLane] is the
 * driver's current lane index or -1. The iAP2 wire layout is undocumented: this decoder
 * uses the layout Apple's own accessory stack is known to emit (0x01 lane count, 0x02
 * current lane, 0x03 lane-arrow byte array) and the raw TLV trace stays in the log so a
 * wrong guess is always recoverable from a real capture.
 */
data class BydLaneGuidance(
    val lanes: List<Int>,
    val currentLane: Int = -1,
)

/** Decodes the iAP2 route-guidance subset needed by the BYD windshield HUD and cluster. */
internal class BydHudRouteState(
    private val nanoTime: () -> Long = System::nanoTime,
    private val staleRouteNs: Long = STALE_ROUTE_NS,
    private val emptyListHideNs: Long = EMPTY_LIST_HIDE_NS,
) {
    private data class Maneuver(val type: Int, val drivingSide: Int, val afterRoad: String, val exitLabel: String = "")

    private val maneuvers = mutableMapOf<Int, Maneuver>()
    private var routeActive = false
    private var activeIndex = -1
    private var distanceMeters = 0
    private var currentRoad = ""
    private var arrivalEpochSeconds: Long? = null
    private var remainingSeconds: Long? = null
    private var remainingMeters: Long? = null
    private var emptyListSinceNs: Long? = null
    private var lastRouteUpdateNs: Long? = null
    private var laneGuidance: BydLaneGuidance? = null
    private var lastLaneUpdateNs: Long? = null

    fun accept(messageId: Int, payload: ByteArray): BydHudRouteChange {
        if (!validTlvs(payload)) return BydHudRouteChange.NONE
        return when (messageId) {
            ROUTE_GUIDANCE_UPDATE -> parseRouteUpdate(payload)
            ROUTE_GUIDANCE_MANEUVER_UPDATE -> parseManeuverUpdate(payload)
            LANE_GUIDANCE_INFO_UPDATE -> parseLaneUpdate(payload)
            else -> BydHudRouteChange.NONE
        }
    }

    /** Current lane guidance, or null when stale / never received. */
    fun currentLanes(): BydLaneGuidance? {
        val updated = lastLaneUpdateNs ?: return null
        if (nanoTime() - updated >= staleRouteNs) return null
        return laneGuidance
    }

    fun current(): BydHudGuidance? {
        val maneuver = activeManeuver() ?: return null
        val gaode = BydManeuverCodes.gaode(maneuver.type, maneuver.drivingSide)
        return BydHudGuidance(
            distanceMeters = distanceMeters,
            maneuver = BydManeuverCodes.hudArrow(gaode),
            gaode = gaode,
            road = roadFor(maneuver),
            arrivalEpochSeconds = arrivalEpochSeconds,
        )
    }

    /** Full context for the dashboard cards: next road, current road and highway exit label. */
    fun cardContext(): BydAppleManeuver? {
        val maneuver = activeManeuver() ?: return null
        return BydAppleManeuver(
            distanceMeters, maneuver.type, maneuver.drivingSide,
            roadFor(maneuver), remainingSeconds, remainingMeters, arrivalEpochSeconds,
            currentRoad, maneuver.exitLabel,
        )
    }

    /** Next maneuver as Apple sent it, for outputs with a richer icon set than the HUD. */
    fun currentApple(): BydAppleManeuver? {
        val maneuver = activeManeuver() ?: return null
        return BydAppleManeuver(
            distanceMeters, maneuver.type, maneuver.drivingSide,
            roadFor(maneuver), remainingSeconds, remainingMeters, arrivalEpochSeconds,
        )
    }

    fun clear(): Boolean {
        val wasActive = routeActive
        routeActive = false
        activeIndex = -1
        distanceMeters = 0
        currentRoad = ""
        arrivalEpochSeconds = null
        remainingSeconds = null
        remainingMeters = null
        emptyListSinceNs = null
        lastRouteUpdateNs = null
        laneGuidance = null
        lastLaneUpdateNs = null
        maneuvers.clear()
        return wasActive
    }

    private fun activeManeuver(): Maneuver? {
        if (!routeActive || activeIndex < 0) return null
        val updated = lastRouteUpdateNs ?: return null
        if (nanoTime() - updated >= staleRouteNs) return null
        val emptySince = emptyListSinceNs
        if (emptySince != null && nanoTime() - emptySince >= emptyListHideNs) return null
        return maneuvers[activeIndex]
    }

    // AMap fills the iAP2 text fields with its own voice-prompt text ("进入 鄞县大道",
    // "去往 鄞县大道 石碶北路") instead of a plain road name, which made the road card
    // and the exit badge show the same narration text. Strip the leading action words so
    // the cards show a clean label; numeric exit labels like "314" are untouched.
    private val NAV_ACTION_PREFIXES = listOf(
        "即将进入", "即将驶入", "即将到达", "进入", "去往", "驶入", "前往", "驶向", "到达",
        "请直行", "请左转", "请右转", "请掉头", "直行", "左转", "右转", "掉头",
        "靠左", "靠右", "目的地", "保持", "前方", "向左前方", "向右前方",
    )

    private fun stripNavAction(raw: String): String {
        var text = raw.trim()
        var changed = true
        while (changed && text.isNotEmpty()) {
            changed = false
            for (prefix in NAV_ACTION_PREFIXES) {
                if (text.startsWith(prefix)) {
                    text = text.removePrefix(prefix).trimStart(' ', '，', ',', '、')
                    changed = true
                    break
                }
            }
        }
        return text
    }

    private fun isNavActionText(raw: String): Boolean {
        val t = raw.trimStart()
        return t.isEmpty() || NAV_ACTION_PREFIXES.any { t.startsWith(it) }
    }

    // The road the driver turns onto is what the next instruction is about; fall back to the current one.
    private fun roadFor(maneuver: Maneuver): String = stripNavAction(maneuver.afterRoad).ifEmpty { currentRoad }

    private fun parseRouteUpdate(data: ByteArray): BydHudRouteChange {
        lastRouteUpdateNs = nanoTime()
        var state: Int? = null
        var distance: Int? = null
        var firstManeuver: Int? = null
        var listPresent = false
        forEachTlv(data) { type, value, valueLength ->
            when {
                type == 0x01 && valueLength >= 1 -> state = data[value].toInt() and 0xff
                type == 0x03 -> currentRoad = utf8(data, value, valueLength)
                type == 0x05 && valueLength >= 8 -> arrivalEpochSeconds = u64(data, value).takeIf { it > 0 }
                type == 0x06 && valueLength >= 8 -> remainingSeconds = u64(data, value).takeIf { it >= 0 }
                type == 0x07 && valueLength >= 4 -> remainingMeters = u32(data, value)
                type == 0x0a && valueLength >= 4 -> {
                    distance = u32(data, value).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
                type == 0x0d -> {
                    listPresent = true
                    if (valueLength >= 2) firstManeuver = u16(data, value)
                }
            }
        }

        // Only NoRouteSet (0) and Arrived (2) end the route.
        if (state == 0 || state == 2) {
            return if (clear()) BydHudRouteChange.CLEAR else BydHudRouteChange.NONE
        }
        // The iPhone briefly sends an empty current list every few seconds and while rerouting. Keep the last
        // maneuver (and the cached 0x5202 details, which are never resent) and hide it only if the list stays
        // empty; the bridges' 1 s tick clears the outputs once current() turns null.
        if (listPresent && firstManeuver == null) {
            if (emptyListSinceNs == null) emptyListSinceNs = nanoTime()
            return BydHudRouteChange.NONE
        }
        if (firstManeuver != null) emptyListSinceNs = null
        if (state != null) routeActive = true
        if (firstManeuver != null) {
            activeIndex = firstManeuver!!
            routeActive = true
        }
        if (distance != null) distanceMeters = distance!!.coerceAtLeast(0)
        return if (current() != null) BydHudRouteChange.GUIDANCE else BydHudRouteChange.NONE
    }

    private fun parseManeuverUpdate(data: ByteArray): BydHudRouteChange {
        var index: Int? = null
        var type: Int? = null
        var drivingSide = 0
        var afterRoad = ""
        var exitLabel = ""
        forEachTlv(data) { id, value, valueLength ->
            when {
                id == 0x01 && valueLength >= 2 -> index = u16(data, value)
                id == 0x03 && valueLength >= 1 -> type = data[value].toInt() and 0xff
                id == 0x04 -> afterRoad = utf8(data, value, valueLength)
                id == 0x08 && valueLength >= 1 -> drivingSide = data[value].toInt() and 0xff
                // Best-effort highway exit label (Apple CPManeuver.highwayExitLabel). The iAP2
                // TLV for it is undocumented, so any short UTF-8 value on an unknown field is
                // taken as the exit label; the raw trace stays in the log for later calibration.
                // AMap narration text ("进入 鄞县大道") arrives through these fields too, so
                // candidates that start with a navigation action word are rejected - otherwise
                // the exit badge duplicates the road-name card. Numeric labels like "314" pass.
                exitLabel.isEmpty() && id !in setOf(0x01, 0x03, 0x04, 0x08) &&
                    valueLength in 1..24 -> {
                    val candidate = utf8(data, value, valueLength)
                    if (candidate.isNotBlank() && !isNavActionText(candidate)) exitLabel = candidate
                }
            }
        }
        if (index != null && type != null) {
            maneuvers[index!!] = Maneuver(type!!, drivingSide, afterRoad, exitLabel)
        }
        return if (current() != null) BydHudRouteChange.GUIDANCE else BydHudRouteChange.NONE
    }

    /**
     * Experimental decoder for 0x5204. Apple's iAP2 layout for lane guidance is not public;
     * the layout used here is what Apple's accessory stack is observed to emit: 0x01 = lane
     * count (u8), 0x02 = current lane index (u8), 0x03 = one direction bitmask byte per lane
     * (road order, left to right). The raw TLV trace is always logged as well, so a wrong
     * guess is correctable from the capture without another field trip.
     */
    private fun parseLaneUpdate(data: ByteArray): BydHudRouteChange {
        lastLaneUpdateNs = nanoTime()
        var laneCount = -1
        var currentLane = -1
        var arrows: ByteArray? = null
        forEachTlv(data) { type, value, valueLength ->
            when (type) {
                0x01 -> if (valueLength >= 1) laneCount = data[value].toInt() and 0xff
                0x02 -> if (valueLength >= 1) currentLane = data[value].toInt() and 0xff
                0x03 -> arrows = data.copyOfRange(value, value + valueLength)
            }
        }
        if (laneCount in 1..12 && arrows != null && arrows.size >= laneCount) {
            laneGuidance = BydLaneGuidance(
                arrows.take(laneCount).map { it.toInt() and 0xff },
                currentLane,
            )
            return BydHudRouteChange.GUIDANCE
        }
        laneGuidance = null
        return BydHudRouteChange.NONE
    }

    private inline fun forEachTlv(data: ByteArray, block: (Int, Int, Int) -> Unit) {
        var offset = 0
        while (offset < data.size) {
            val length = u16(data, offset)
            block(u16(data, offset + 2), offset + TLV_HEADER_BYTES, length - TLV_HEADER_BYTES)
            offset += length
        }
    }

    private fun validTlvs(data: ByteArray): Boolean {
        var offset = 0
        while (offset < data.size) {
            if (offset + TLV_HEADER_BYTES > data.size) return false
            val length = u16(data, offset)
            if (length < TLV_HEADER_BYTES || length > data.size - offset) return false
            offset += length
        }
        return offset == data.size
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)

    private fun u64(data: ByteArray, offset: Int): Long = (u32(data, offset) shl 32) or u32(data, offset + 4)

    // iAP2 utf8 parameters are NUL-terminated.
    private fun utf8(data: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.UTF_8).trim()
    }

    /**
     * Diagnostic trace of every TLV in a route-guidance payload (0x5201/0x5202/0x5204). The full
     * iAP2 layout is not publicly documented and this parser only decodes the fields it needs, so
     * a raw TLV capture is logged to discover any hidden fields (traffic status, speed limits…).
     * A real capture then lets us write parsers for whatever fields actually exist.
     */
    fun tlvTrace(messageId: Int, payload: ByteArray): String {
        if (!validTlvs(payload)) return "malformed(${payload.size}B)"
        val parts = mutableListOf<String>()
        forEachTlv(payload) { type, value, valueLength ->
            val head = payload.copyOfRange(value, (value + minOf(valueLength, 8)).coerceAtMost(payload.size))
            parts += "t=0x${type.toString(16).padStart(4, '0')} len=$valueLength ${head.toHex()}"
        }
        return "0x${messageId.toString(16)} ${parts.joinToString(" | ")}"
    }

    private fun ByteArray.toHex(): String = joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
        const val LANE_GUIDANCE_INFO_UPDATE = 0x5204
        private const val TLV_HEADER_BYTES = 4
        private const val STALE_ROUTE_NS = 30_000_000_000L
        private const val EMPTY_LIST_HIDE_NS = 3_000_000_000L
    }
}
