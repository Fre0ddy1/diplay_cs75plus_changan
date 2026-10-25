package com.shilapi.xcertplay.customization

import com.freddyq.diplay.customization.StandardNavigationGuidance
import com.shilapi.xcertplay.hud.BydClusterFrame
import com.shilapi.xcertplay.hud.BydHudRouteState
import com.shilapi.xcertplay.hud.NavigationOutputWorker

/** The only adapter that knows upstream's internal parser and icon mapping types. */
class PhoneNavigationDecoder(
    nanoTime: () -> Long = System::nanoTime,
    retainGuidanceUntilSessionEnds: Boolean = false,
) {
    // Stationary AMap can send only changes, with no route heartbeat for over 30 s.
    // Opt-in consumers must clear on session end. The upstream parser keeps its default policy.
    private val route = if (retainGuidanceUntilSessionEnds) {
        BydHudRouteState(nanoTime, staleRouteNs = Long.MAX_VALUE)
    } else {
        BydHudRouteState(nanoTime)
    }

    fun accept(messageId: Int, payload: ByteArray) { route.accept(messageId, payload) }
    fun clear() { route.clear() }

    fun current(): StandardNavigationGuidance? = route.cardContext()?.let { maneuver ->
        val mapped = BydClusterFrame.from(maneuver)
        StandardNavigationGuidance(
            mapped.icon, mapped.distanceMeters, maneuver.road, maneuver.currentRoad, mapped.roundaboutExit,
        )
    }

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val MANEUVER_UPDATE = 0x5202
    }
}

/** Public facade keeps upstream worker implementation details out of customization modules. */
class CustomizationOutputWorker(name: String, clearOutput: () -> Unit) {
    private val worker = NavigationOutputWorker(name, clearOutput)
    fun start(initialize: () -> Unit) = worker.start(initialize)
    fun submit(action: () -> Unit) = worker.submit(action)
    fun clear() = worker.clear()
}
