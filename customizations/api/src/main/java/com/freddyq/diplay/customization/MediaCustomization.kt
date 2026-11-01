package com.freddyq.diplay.customization

/** Retained metadata, independent of the phone protocol and any vehicle's source numbering. */
data class MediaItem(
    val title: String?, val artist: String?, val album: String?,
    val durationMillis: Long?, val artworkId: Int?,
    val sourceApp: String? = null,
)

data class MediaSnapshot(
    val item: MediaItem,
    val playing: Boolean,
    val positionMillis: Long?,
    val positionUpdatedAtMillis: Long,
    /** Observation only; consumers must never acquire focus or select an audio source. */
    val outputOwned: Boolean,
) {
    fun positionAt(now: Long): Long? = positionMillis?.let { position ->
        val delta = if (playing) (now - positionUpdatedAtMillis).coerceAtLeast(0) else 0L
        val bounded = position.coerceAtLeast(0).coerceAtMost(Long.MAX_VALUE - delta) + delta
        item.durationMillis?.takeIf { it > 0 }?.let { bounded.coerceAtMost(it) } ?: bounded
    }
}
