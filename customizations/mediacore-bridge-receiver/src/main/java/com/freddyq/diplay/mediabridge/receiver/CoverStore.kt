package com.freddyq.diplay.mediabridge.receiver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.freddyq.diplay.mediabridge.contract.MediaFrame
import java.io.File
import java.util.UUID

/** Host-private files. Only the explicit host ContentProvider exposes them for reading. */
class CoverStore(private val directory: File, private val authority: String) {
    init { require(authority.isNotBlank()); directory.mkdirs() }

    fun put(jpeg: ByteArray): String {
        require(jpeg.size in 4..MediaFrame.MAX_JPEG_BYTES)
        require(jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        require(bounds.outWidth in 1..MediaFrame.MAX_COVER_EDGE && bounds.outHeight in 1..MediaFrame.MAX_COVER_EDGE)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        val name = UUID.randomUUID().toString() + ".jpg"
        val file = File(directory, name)
        try {
            file.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
        } catch (e: Exception) { file.delete(); throw e }
        finally { bitmap.recycle() }
        return Uri.Builder().scheme("content").authority(authority).appendPath(name).build().toString()
    }

    fun resolve(uri: Uri): File {
        require(uri.scheme == "content" && uri.authority == authority && uri.pathSegments.size == 1)
        val name = uri.lastPathSegment!!
        require(Regex("[0-9a-f-]{36}\\.jpg").matches(name))
        return File(directory, name).also { require(it.isFile) }
    }

    fun retain(uri: String?, previous: String? = null) {
        val names = listOfNotNull(uri, previous).map { Uri.parse(it).lastPathSegment }.toSet()
        directory.listFiles()?.filter { it.name !in names && it.name.endsWith(".jpg") }?.forEach { it.delete() }
    }
}
