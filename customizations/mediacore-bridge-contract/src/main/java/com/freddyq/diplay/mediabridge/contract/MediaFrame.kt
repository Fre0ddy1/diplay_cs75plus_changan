package com.freddyq.diplay.mediabridge.contract

import android.os.Bundle

/** Wire units are milliseconds. This is metadata, never audio samples or a control command. */
data class MediaFrame(
    val track: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playing: Boolean = false,
    val outputOwned: Boolean = false,
    val sourceSelected: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val audioSessionId: Int? = null,
    val cover: String = KEEP,
) {
    init {
        require(track.isNotBlank() && track.length <= MAX_TEXT)
        require(listOf(title, artist, album).all { it.length <= MAX_TEXT })
        require(positionMs >= 0 && durationMs >= 0)
        require(durationMs == 0L || positionMs <= durationMs)
        require(audioSessionId == null || audioSessionId > 0)
        require(cover in setOf(KEEP, REPLACE, CLEAR))
    }

    fun toBundle() = Bundle().apply {
        putInt("version", VERSION)
        putString("track", track); putString("title", title); putString("artist", artist); putString("album", album)
        putBoolean("playing", playing); putBoolean("outputOwned", outputOwned); putBoolean("sourceSelected", sourceSelected)
        putLong("positionMs", positionMs); putLong("durationMs", durationMs); putString("cover", cover)
        audioSessionId?.let { putInt("audioSessionId", it) }
    }

    companion object {
        const val VERSION = 1
        const val CARPLAY_PROVIDER = 12 // Actual OEM enum entry; never masquerade as Bt(3) or WeCarFlow(1).
        const val MAX_TEXT = 256
        const val MAX_JPEG_BYTES = 128 * 1024
        const val MAX_COVER_EDGE = 384
        const val KEEP = "keep"
        const val REPLACE = "replace"
        const val CLEAR = "clear"

        @Suppress("DEPRECATION")
        fun fromBundle(b: Bundle): MediaFrame {
            val types = mapOf(
                "version" to Int::class.javaObjectType, "track" to String::class.java,
                "title" to String::class.java, "artist" to String::class.java, "album" to String::class.java,
                "playing" to Boolean::class.javaObjectType, "outputOwned" to Boolean::class.javaObjectType,
                "sourceSelected" to Boolean::class.javaObjectType, "positionMs" to Long::class.javaObjectType,
                "durationMs" to Long::class.javaObjectType, "cover" to String::class.java,
                "audioSessionId" to Int::class.javaObjectType,
            )
            require(b.keySet().all { types[it]?.isInstance(b.get(it)) == true })
            require((types.keys - "audioSessionId").all { b.containsKey(it) })
            require(b.getInt("version") == VERSION)
            return MediaFrame(
                b.getString("track")!!, b.getString("title")!!, b.getString("artist")!!, b.getString("album")!!,
                b.getBoolean("playing"), b.getBoolean("outputOwned"), b.getBoolean("sourceSelected"),
                b.getLong("positionMs"), b.getLong("durationMs"),
                if (b.containsKey("audioSessionId")) b.getInt("audioSessionId") else null, b.getString("cover")!!,
            )
        }
    }
}

object BridgeReceipt {
    const val LOCAL_MODEL = "LOCAL_MODEL" // No original service was updated.
    const val OEM_PROVIDER_STAGED = "OEM_PROVIDER_STAGED" // Not a scene/VHAL acknowledgement.
    fun of(code: String, stage: String, sequence: Long = -1) = Bundle().apply {
        putString("code", code); putString("stage", stage); putLong("sequence", sequence)
    }
}
