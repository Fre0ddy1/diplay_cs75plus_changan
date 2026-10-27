package com.freddyq.diplay.customization

/** Distribution-owned log budget and filtering; no vehicle protocol or playback behavior. */
interface DiagnosticPolicy {
    val maxFileBytes: Long
    val archiveCount: Int
    val maxQueuedLines: Int
    fun keep(message: String): Boolean
}
