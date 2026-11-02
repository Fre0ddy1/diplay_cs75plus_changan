package com.freddyq.diplay.mediabridge.receiver

import com.freddyq.diplay.mediabridge.contract.BridgeReceipt
import com.freddyq.diplay.mediabridge.contract.MediaFrame

/** Implement inside the original MediaCore process: store state and enqueue its own observers. */
fun interface NativeProviderSlot {
    fun stage(update: NativeProviderUpdate)
}

data class NativeProviderUpdate(
    val provider: Int, val mediaInfo: Any?, val playState: Any, val audioSessionId: Int?,
    val outputOwned: Boolean, val sourceSelected: Boolean,
    val changes: ProviderChanges,
)

/** Store progress without re-notifying artwork/metadata observers every second. */
data class ProviderChanges(val media: Boolean, val cover: Boolean, val playback: Boolean, val progress: Boolean, val source: Boolean)

/** Uses verified setter names, without bundling proprietary APK/SDK classes. */
class OemProviderAdapter(
    private val slot: NativeProviderSlot,
    private val mediaClass: Class<*>,
    private val stateClass: Class<*>,
) : ProviderSink {
    override val stage = BridgeReceipt.OEM_PROVIDER_STAGED
    private var previous: PublishedMedia? = null
    private var initialized = false

    override fun stage(media: PublishedMedia?) {
        fun metadata(value: PublishedMedia?) = value?.let {
            listOf(it.frame.track, it.frame.title, it.frame.artist, it.frame.album, it.frame.durationMs, it.coverUri)
        }
        val changes = ProviderChanges(
            !initialized || metadata(previous) != metadata(media),
            !initialized || previous?.coverUri != media?.coverUri,
            !initialized || previous?.frame?.playing != media?.frame?.playing,
            !initialized || previous?.frame?.positionMs != media?.frame?.positionMs,
            !initialized || previous?.frame?.sourceSelected != media?.frame?.sourceSelected ||
                previous?.frame?.outputOwned != media?.frame?.outputOwned || previous?.frame?.audioSessionId != media?.frame?.audioSessionId,
        )
        val stateName = if (media == null) "STOP" else if (media.frame.playing) "PLAY" else "PAUSE"
        val state = requireNotNull(stateClass.enumConstants?.firstOrNull { (it as Enum<*>).name == stateName })
        val model = media?.let { value ->
            val f = value.frame
            mediaClass.getConstructor().newInstance().apply {
                fun set(name: String, type: Class<*>, value: Any?) { mediaClass.getMethod(name, type).invoke(this, value) }
                set("setId", String::class.java, f.track)
                set("setMediaName", String::class.java, f.title)
                set("setTitle", String::class.java, f.title)
                set("setMediaAuthor", String::class.java, f.artist)
                set("setMediaGroupName", String::class.java, f.album)
                set("setMediaType", String::class.java, "song")
                set("setMediaImage", String::class.java, value.coverUri ?: "")
                set("setDuration", Long::class.javaPrimitiveType!!, f.durationMs)
                set("setProgress", Long::class.javaPrimitiveType!!, f.positionMs)
            }
        }
        // Slot is mandatory. A missing registration/observer implementation cannot acknowledge OEM staging.
        // Native MediaInfo has no setExInfo. Keep bridge observations in the provider slot itself.
        slot.stage(NativeProviderUpdate(MediaFrame.CARPLAY_PROVIDER, model, state, media?.frame?.audioSessionId,
            media?.frame?.outputOwned == true, media?.frame?.sourceSelected == true, changes))
        previous = media; initialized = true
    }

    companion object {
        fun inOemProcess(loader: ClassLoader, slot: NativeProviderSlot) = OemProviderAdapter(
            slot,
            loader.loadClass("com.wt.multimedia.core.provider.contract.entity.MediaInfo"),
            loader.loadClass("com.wt.multimedia.core.provider.contract.enums.PlayStateCompat"),
        )
    }
}
