package com.shilapi.xcertplay.media

/** At most one settled retry round; cancellation must never restart a closed decoder. */
internal fun <T : Any> configureDecoderWithRetry(
    running: () -> Boolean,
    configure: () -> T?,
    settle: () -> Unit,
): T? {
    if (!running()) return null
    configure()?.let { return it }
    if (!running()) return null
    try { settle() } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return null
    }
    return if (running()) configure() else null
}
