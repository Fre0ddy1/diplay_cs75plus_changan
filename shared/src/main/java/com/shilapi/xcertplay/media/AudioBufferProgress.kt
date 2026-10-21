package com.shilapi.xcertplay.media

/** Tracks the unsigned AudioTrack head across wrap without flushing already queued sound. */
internal class AudioBufferProgress(private val frameBytes: Int) {
    private var writtenBytes = 0L
    private var playedFrames = 0L
    private var lastHead = 0L

    fun written(bytes: Int) { writtenBytes += bytes }

    /**
     * Compression resynchronises the head: [bytes] of still-queued PCM are discarded, so they must
     * leave the ledger too. Without this the estimate stays permanently inflated by everything the
     * track dropped and the rebuffer decision never sees a real underrun (field log: the estimate
     * sat at ~23 k frames while the caller kept reporting an empty incoming queue).
     */
    fun discarded(bytes: Long) { writtenBytes -= bytes }

    fun queuedBytes(rawHead: Int): Long {
        val head = rawHead.toLong() and 0xffff_ffffL
        playedFrames += (head - lastHead) and 0xffff_ffffL
        lastHead = head
        return (writtenBytes - playedFrames * frameBytes).coerceAtLeast(0)
    }

    fun shouldRebuffer(isMedia: Boolean, playing: Boolean, underrunSinceStart: Boolean,
        compressedQueueEmpty: Boolean, rawHead: Int, floorBytes: Long): Boolean =
        isMedia && playing && underrunSinceStart && compressedQueueEmpty &&
            queuedBytes(rawHead) <= floorBytes
}
