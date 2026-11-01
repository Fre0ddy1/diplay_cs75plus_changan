package com.freddyq.diplay.changan.media

import android.os.IBinder
import android.os.Parcel
import com.freddyq.diplay.customization.MediaItem
import com.freddyq.diplay.customization.MediaSnapshot
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal data class TrackKey(val title: String?, val artist: String?, val album: String?) {
    // Only rejects a stale artwork callback at arrival; never allocates a display identifier.
    constructor(item: MediaItem) : this(item.title, item.artist, item.album)
}
internal data class CoverKey(val track: TrackKey?, val artworkId: Int?) {
    // A valid transfer belongs to this phone session and may precede the final text fields.
    constructor(item: MediaItem) : this(if (item.artworkId == null) TrackKey(item) else null, item.artworkId)
}

/** Only the three observed display transactions; no source switching, focus or media controls. */
internal class IncallMediaProtocol(private val remote: IBinder) {
    fun metadata(sourceId: Int, snapshot: MediaSnapshot) = metadata(metadataJson(sourceId, snapshot))
    fun metadata(json: String) = write(37) { writeString(json) }
    fun progress(sourceId: Int, snapshot: MediaSnapshot, now: Long) {
        // OEM handleSetMediaPlayTime forwards TX38 only when mMediaoPlayingStatus == 2.
        if (!snapshot.playing) return
        val total = snapshot.item.durationMillis?.takeIf { it in 1..Int.MAX_VALUE.toLong() } ?: return
        val position = snapshot.positionAt(now)?.coerceIn(0, total) ?: return
        write(38) { writeInt(sourceId); writeInt(position.toInt()); writeInt(total.toInt()) }
    }
    fun cover(sourceId: Int, file: File) = write(39) {
        writeInt(sourceId); writeString(file.name); writeString(file.absolutePath)
    }
    private inline fun write(code: Int, body: Parcel.() -> Unit) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(TOKEN); data.body()
            if (!remote.transact(code, data, reply, 0)) throw IOException("Incall media TX$code unhandled")
            reply.readException()
        } finally { reply.recycle(); data.recycle() }
    }
    companion object {
        const val TOKEN = "com.incall.serversdk.interactive.IDouInteractiveManager"
        fun metadataJson(sourceId: Int, snapshot: MediaSnapshot): String {
            require(sourceId > 0)
            return JSONObject().put("playStatus", if (snapshot.playing) 2 else 1)
                .put("programName", snapshot.item.album.orEmpty())
                .put("singerName", snapshot.item.artist.orEmpty())
                .put("songName", snapshot.item.title.orEmpty())
                .put("sourceID", sourceId).put("sourceType", 25).toString()
        }
    }
}
