package com.shilapi.xcertplay.orchestration

import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/** Diagnostic classification only: all existing retries, deadlines and transports stay unchanged. */
enum class ConnectionFailureCause {
    CHOSEN_IPHONE_NOT_PAIRED, NO_PAIRED_IPHONE, IPHONE_NOT_CHOSEN, WIFI_DIRECT_CHANNEL_UNAVAILABLE,
    CAR_HOTSPOT_OFF, CAR_HOTSPOT_DETAILS_DIFFER, CAR_HOTSPOT_START_TIMED_OUT,
    CAR_HOTSPOT_START_FAILED, HOTSPOT_NOT_READY, HOTSPOT_NOT_CONFIGURED,
    BLUETOOTH_OFF, BLUETOOTH_UNAVAILABLE,
}

open class ConnectionFailureException(val reason: ConnectionFailureCause, message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** Exception chains from vendor APIs need not be acyclic. */
fun connectionFailureCause(error: Throwable): ConnectionFailureCause? {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    while (current != null && seen.size < 32 && seen.add(current)) {
        if (current is ConnectionFailureException) return current.reason
        current = current.cause
    }
    return null
}
