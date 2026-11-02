package com.freddyq.diplay.mediabridge.receiver

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.freddyq.diplay.mediabridge.contract.MediaFrame

/** Integration helper for CityOfPhantomService.P(); it does not write settings or VHAL. */
object SceneCompatibility {
    fun musicEligible(
        provider: Int, playing: Boolean, mediaType: String?, linkageEnabled: Boolean, suppressed: Boolean,
        outputOwned: Boolean, sourceSelected: Boolean,
    ): Boolean {
        if (!linkageEnabled || suppressed || !playing || mediaType != "song") return false
        return provider in 1..4 ||
            (provider == MediaFrame.CARPLAY_PROVIDER && outputOwned && sourceSelected)
    }

    /** Call BEFORE the OEM HTTP/network-only image branch. Never accept arbitrary file/content paths. */
    fun loadCover(resolver: ContentResolver, reference: String, trustedAuthority: String): Bitmap? {
        val uri = Uri.parse(reference)
        if (uri.scheme != "content" || uri.authority != trustedAuthority) return null
        return runCatching {
            val bytes = resolver.openInputStream(uri)?.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= MediaFrame.MAX_JPEG_BYTES)
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..MediaFrame.MAX_COVER_EDGE && bounds.outHeight in 1..MediaFrame.MAX_COVER_EDGE)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
}
