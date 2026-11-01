package com.freddyq.diplay.changan.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MediaCoverCacheTest {
    @Test fun storedImagesAreSmallImmutableJpegsAndCallerBitmapStaysOwnedByCaller() {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, UUID.randomUUID().toString())
        val cache = MediaCoverCache(directory)
        val bitmap = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888).apply { eraseColor(-16776961) }
        val first = cache.store(bitmap)!!
        val bytes = first.readBytes()
        assertFalse(bitmap.isRecycled); assertEquals(384, bitmap.width)
        val second = cache.store(bitmap)!!
        assertEquals(first, second); assertArrayEquals(bytes, first.readBytes())
        val decoded = BitmapFactory.decodeFile(first.absolutePath)
        assertEquals(320, decoded.width); assertEquals(320, decoded.height)
        bitmap.recycle(); decoded.recycle()
    }
    @Test fun freshNativeReaderFilesAreNotEvictedToMakeRoom() {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        var time = 100000L
        repeat(6) { index -> File(directory, "cp-${index.toString().padStart(12, '0')}.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(time) } }
        val cache = MediaCoverCache(directory) { time }
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        assertNull(cache.store(bitmap)); assertEquals(6, directory.listFiles()!!.size)
        time += 61000
        assertNotNull(cache.store(bitmap)); assertEquals(1, directory.listFiles()!!.size)
        bitmap.recycle()
    }
}
