package com.shilapi.xcertplay.hud

/** Next-turn instruction for the dashboard overlay. Icons use the AMap NEW_ICON vocabulary. */
data class ClusterTurnGuidance(
    val icon: Int,
    val roundaboutExit: Int,
    val distanceMeters: Int,
    val road: String,
    /** ETA / remaining trip data from 0x5201, used by the ETA card; null when unknown. */
    val arrivalEpochSeconds: Long? = null,
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    /** Road the driver is on now (0x5201 t=0x03), for the road-name card. */
    val currentRoad: String = "",
    /** Highway exit label from 0x5202 (best-effort decode), shown by the exit badge. */
    val exitLabel: String = "",
) {
    companion object {
        internal fun from(frame: BydClusterFrame): ClusterTurnGuidance {
            return ClusterTurnGuidance(
                frame.icon,
                frame.roundaboutExit,
                frame.distanceMeters,
                frame.road,
                frame.arrivalEpochSeconds,
                frame.routeRemainingSeconds.takeIf { it >= 0 }?.toLong(),
                frame.routeRemainingMeters.takeIf { it >= 0 }?.toLong(),
                frame.currentRoad,
                frame.exitLabel,
            )
        }
    }
}
