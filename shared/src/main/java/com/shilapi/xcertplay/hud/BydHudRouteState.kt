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
 * One lane from the iAP2 0x5204 LaneInformations payload.
 *
 * Wire layout (verified against the MHI2Q hook's `rgd_tlv.h`, which was checked against
 * accessoryd 23G71): each lane has an index/position, a status (0 not good, 1 good,
 * 2 preferred), repeated angle values and an optional highlighted angle. Angles are signed
 * degrees; 1000 is the "unknown" sentinel and must never become a turn.
 */
data class BydLane(
    val position: Int,
    val status: Int,
    val angles: List<Int> = emptyList(),
    val highlight: Int? = null,
) {
    /** Highlighted angle wins; otherwise the first real angle. */
    val primaryAngle: Int?
        get() = highlight?.takeIf { isValidAngle(it) } ?: angles.firstOrNull { isValidAngle(it) }

    val preferred: Boolean
        get() = status == 2 || highlight?.let { isValidAngle(it) } == true

    companion object {
        fun isValidAngle(angle: Int): Boolean = angle in -180..180
    }
}

/**
 * The lane event selected by 0x5201's CurrentLaneGuidanceIndex.
 * [currentLane] is a list index (the preferred lane), not the iOS event index.
 */
data class BydLaneGuidance(
    val lanes: List<BydLane>,
    val currentLane: Int = -1,
    val showing: Boolean = true,
    val eventIndex: Int = -1,
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
    /** Lane events keyed by iOS's lane-guidance index; 0x5204 can arrive before 0x5201 selects it. */
    private val laneEvents = mutableMapOf<Int, List<BydLane>>()
    private var laneGuidanceIndex = -1
    private var laneGuidanceTotal = 0
    private var laneGuidanceShowing = false
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

    /** Current lane guidance, or null when stale / hidden / never received. */
    fun currentLanes(): BydLaneGuidance? {
        val updated = lastLaneUpdateNs ?: return null
        if (nanoTime() - updated >= staleRouteNs) return null
        if (!laneGuidanceShowing) return null
        val index = laneGuidanceIndex
        if (index < 0) return null
        val lanes = laneEvents[index] ?: return null
        if (lanes.isEmpty()) return null
        val preferred = lanes.indexOfFirst { it.status == 2 || it.highlight?.let { a -> BydLane.isValidAngle(a) } == true }
        return BydLaneGuidance(lanes, preferred, true, index)
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

    /**
     * True while a route is active. iAP2 0x5201 t=0x01 is the authoritative state:
     * 0 = NoRouteSet and 2 = Arrived clear it; anything else means a route is up.
     *
     * The 30 s freshness check keeps a session that stopped sending updates from looking like
     * an active route forever; the explicit 0/2 state is still the normal exit signal.
     */
    fun isNavigating(): Boolean {
        if (!routeActive) return false
        val updated = lastRouteUpdateNs ?: return false
        return nanoTime() - updated < staleRouteNs
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
        laneEvents.clear()
        laneGuidanceIndex = -1
        laneGuidanceTotal = 0
        laneGuidanceShowing = false
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

    // The road the driver turns onto is what the next instruction is about; fall back to the current one.
    private fun roadFor(maneuver: Maneuver): String = stripNavAction(maneuver.afterRoad).ifEmpty { currentRoad }

    private fun parseRouteUpdate(data: ByteArray): BydHudRouteChange {
        lastRouteUpdateNs = nanoTime()
        var state: Int? = null
        var distance: Int? = null
        var firstManeuver: Int? = null
        var laneIndex: Int? = null
        var laneTotal: Int? = null
        var laneShowing: Int? = null
        var listPresent = false
        forEachTlv(data) { type, value, valueLength ->
            when {
                type == 0x01 && valueLength >= 1 -> state = data[value].toInt() and 0xff
                // Current road name. The 2026-10-09 capture shows it on t=0x0004 ("同德路",
                // clean name without narration text); t=0x0003 is kept as a fallback for
                // layouts where it appears instead.
                type == 0x04 || type == 0x03 -> currentRoad = utf8(data, value, valueLength)
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
                // 0x5201 selects which cached 0x5204 lane event is current.
                type == 0x10 && valueLength >= 2 -> laneIndex = u16(data, value)
                type == 0x11 && valueLength >= 2 -> laneTotal = u16(data, value)
                type == 0x12 && valueLength >= 1 -> laneShowing = data[value].toInt() and 0xff
            }
        }

        var laneChanged = false
        laneIndex?.let {
            if (laneGuidanceIndex != it) {
                laneGuidanceIndex = it
                laneChanged = true
            }
        }
        laneTotal?.let { laneGuidanceTotal = it }
        laneShowing?.let {
            val showing = it != 0
            if (laneGuidanceShowing != showing) {
                laneGuidanceShowing = showing
                laneChanged = true
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
        return if (laneChanged || current() != null || currentLanes() != null) {
            BydHudRouteChange.GUIDANCE
        } else {
            BydHudRouteChange.NONE
        }
    }

    private fun parseManeuverUpdate(data: ByteArray): BydHudRouteChange {
        var index: Int? = null
        var type: Int? = null
        var drivingSide = 0
        var afterRoad = ""
        // Highway exit label is deliberately NOT decoded: the 2026-10-09 capture proved that
        // 0x5202's t=0x0005 (previously guessed as the label) is actually the distance to the
        // next manoeuvre (39/50 m, tracking the turn card's distance), and the best-effort
        // unknown-field guess also picks up distances ("314") or AMap narration text. No
        // reliable exit-label field exists in the stream Amap sends, so the badge stays hidden.
        val exitLabel = ""
        forEachTlv(data) { id, value, valueLength ->
            when {
                id == 0x01 && valueLength >= 2 -> index = u16(data, value)
                id == 0x03 && valueLength >= 1 -> type = data[value].toInt() and 0xff
                id == 0x04 -> afterRoad = utf8(data, value, valueLength)
                id == 0x08 && valueLength >= 1 -> drivingSide = data[value].toInt() and 0xff
            }
        }
        if (index != null && type != null) {
            maneuvers[index!!] = Maneuver(type!!, drivingSide, afterRoad, exitLabel)
        }
        return if (current() != null) BydHudRouteChange.GUIDANCE else BydHudRouteChange.NONE
    }

    /**
     * 0x5204 LaneGuidanceInformation.
     *
     * Outer TLVs: 0x0001 = lane-guidance event index, 0x0002 = LaneInformations.
     * Inside 0x0002, each lane is: 0x0000 index/position, 0x0001 status,
     * 0x0002 repeated angles, 0x0003 highlighted angle.
     *
     * This follows the MHI2Q hook's verified `rgd_tlv.h` layout, not the earlier
     * count/current/bitmask guess. A 0x5204 may arrive before the 0x5201 that selects
     * its event, so every event is cached until CurrentLaneGuidanceIndex names it.
     */
    private fun parseLaneUpdate(data: ByteArray): BydHudRouteChange {
        var eventIndex = -1
        var lanes: List<BydLane> = emptyList()
        forEachTlv(data) { type, value, valueLength ->
            when (type) {
                0x0001 -> if (valueLength >= 2) eventIndex = u16(data, value)
                0x0002 -> lanes = parseLaneInformations(data, value, valueLength)
            }
        }
        if (lanes.isEmpty()) return BydHudRouteChange.NONE
        val index = if (eventIndex >= 0) eventIndex else laneGuidanceIndex
        if (index < 0) return BydHudRouteChange.NONE
        lastLaneUpdateNs = nanoTime()
        laneEvents[index] = lanes.take(MAX_LANES)
        if (laneEvents.size > MAX_LANE_EVENTS) {
            val oldest = laneEvents.keys.filter { it != laneGuidanceIndex }.minOrNull()
            if (oldest != null) laneEvents.remove(oldest)
        }
        return BydHudRouteChange.GUIDANCE
    }

    private fun parseLaneInformations(data: ByteArray, start: Int, length: Int): List<BydLane> {
        val result = mutableListOf<BydLane>()
        var index: Int? = null
        var status = 0
        var angles = mutableListOf<Int>()
        var highlight: Int? = null

        fun flush() {
            val position = index ?: result.size
            result += BydLane(position, status, angles.toList(), highlight)
            index = null
            status = 0
            angles = mutableListOf()
            highlight = null
        }

        forEachTlvRange(data, start, length) { id, value, valueLength ->
            when (id) {
                0x0000 -> {
                    if (index != null) flush()
                    if (valueLength >= 2) index = u16(data, value)
                }
                0x0001 -> if (valueLength >= 1) status = data[value].toInt() and 0xff
                0x0002 -> {
                    angles = mutableListOf()
                    var offset = value
                    val end = value + valueLength
                    while (offset + 1 < end) {
                        angles += s16(data, offset)
                        offset += 2
                    }
                }
                0x0003 -> if (valueLength >= 2) highlight = s16(data, value)
            }
        }
        if (index != null || angles.isNotEmpty() || status != 0) flush()
        return result
    }

    private inline fun forEachTlv(data: ByteArray, block: (Int, Int, Int) -> Unit) {
        var offset = 0
        while (offset < data.size) {
            val length = u16(data, offset)
            block(u16(data, offset + 2), offset + TLV_HEADER_BYTES, length - TLV_HEADER_BYTES)
            offset += length
        }
    }

    private inline fun forEachTlvRange(
        data: ByteArray,
        start: Int,
        length: Int,
        block: (Int, Int, Int) -> Unit,
    ) {
        var offset = start
        val end = (start + length).coerceAtMost(data.size)
        while (offset + TLV_HEADER_BYTES <= end) {
            val total = u16(data, offset)
            if (total < TLV_HEADER_BYTES || offset + total > end) return
            block(u16(data, offset + 2), offset + TLV_HEADER_BYTES, total - TLV_HEADER_BYTES)
            offset += total
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

    private fun s16(data: ByteArray, offset: Int): Int = u16(data, offset).toShort().toInt()

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
        private const val MAX_LANES = 8
        private const val MAX_LANE_EVENTS = 32
        private const val STALE_ROUTE_NS = 30_000_000_000L
        private const val EMPTY_LIST_HIDE_NS = 3_000_000_000L
    }
}
