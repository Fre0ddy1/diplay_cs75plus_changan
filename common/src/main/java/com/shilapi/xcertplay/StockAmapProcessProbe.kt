package com.shilapi.xcertplay

/** Missing process visibility is not evidence that another package has exited. */
internal enum class ProcessPresence { RUNNING, ABSENT, UNKNOWN }

internal object StockAmapProcessProbe {
    fun groups(status: String?): Set<Int> = status?.lineSequence()
        ?.firstOrNull { it.startsWith("Groups:") }?.substringAfter(':')
        ?.trim()?.split(Regex("\\s+"))?.mapNotNull(String::toIntOrNull)?.toSet() ?: emptySet()

    fun restrictedProc(mounts: String?, groups: Set<Int>): Boolean {
        if (mounts == null) return true
        val proc = mounts.lineSequence().map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 4 && it[1] == "/proc" } ?: return true
        val options = proc[3].split(',')
        val hidden = options.any { it == "hidepid=1" || it == "hidepid=2" }
        val bypass = options.firstOrNull { it.startsWith("gid=") }?.substringAfter('=')?.toIntOrNull()
        return hidden && (bypass == null || bypass !in groups)
    }

    fun classify(target: String, cmdlines: List<String>?, complete: Boolean): ProcessPresence {
        if (cmdlines?.any { it.substringBefore('\u0000') == target } == true) return ProcessPresence.RUNNING
        return if (cmdlines != null && complete) ProcessPresence.ABSENT else ProcessPresence.UNKNOWN
    }

    fun ready(presence: ProcessPresence, sinceLaunchMs: Long): Boolean = when (presence) {
        ProcessPresence.RUNNING -> true
        ProcessPresence.ABSENT -> false
        // The launcher has handed focus back by now; the Binder handshake owns actual readiness.
        ProcessPresence.UNKNOWN -> sinceLaunchMs >= UNKNOWN_GRACE_MS
    }

    const val UNKNOWN_GRACE_MS = 2_000L
}
