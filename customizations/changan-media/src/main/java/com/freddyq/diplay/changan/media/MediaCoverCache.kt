package com.freddyq.diplay.changan.media

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Immutable files: leave a grace period for the original service's asynchronous file reader. */
internal class MediaCoverCache(private val directory: File, private val wallTime: () -> Long = System::currentTimeMillis) {
    fun store(bitmap: Bitmap): File? {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cover directory unavailable")
        val now = wallTime()
        val files = directory.listFiles { f -> f.name.matches(Regex("cp-[a-f0-9]{12}\\.jpg")) }.orEmpty()
        files.filter { now - it.lastModified() > 60_000 }.forEach { it.delete() }
        val temporary = File.createTempFile("cover-", ".tmp", directory)
        val scaled = Bitmap.createScaledBitmap(bitmap, 320, 320, true)
        try {
            FileOutputStream(temporary).use {
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw IOException("cover encoding failed")
            }
            if (temporary.length() !in 1..262_144) throw IOException("cover size out of bounds")
            val digest = MessageDigest.getInstance("SHA-256").digest(temporary.readBytes())
                .take(6).joinToString("") { "%02x".format(it.toInt() and 255) }
            val target = File(directory, "cp-$digest.jpg")
            // A full cache may still contain this exact image. Reuse it before enforcing new-file capacity.
            val retained = directory.listFiles { f -> f.extension == "jpg" }.orEmpty()
            if (!target.exists() && (retained.size >= 128 ||
                    retained.sumOf { it.length() } + temporary.length() > 6L * 262_144)) return null
            if (!target.exists() && !temporary.renameTo(target)) throw IOException("cover commit failed")
            // A new send reuses this image; extend the reader grace period.
            target.setLastModified(now)
            return target
        } finally {
            temporary.delete()
            if (scaled !== bitmap) scaled.recycle()
        }
    }
}
