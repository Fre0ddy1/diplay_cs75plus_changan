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
    @Test fun aSeventhSmallCoverFitsTheSameByteBudgetWithoutRemovingFreshReaderFiles() {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val originals = (0..5).map { index -> File(directory, "cp-${index.toString().padStart(12, '0')}.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(100000) } }
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(-65536) }
        val added = MediaCoverCache(directory) { 100000L }.store(bitmap)
        assertNotNull(added); assertTrue(originals.all { it.isFile && it.readBytes().contentEquals(byteArrayOf(1)) })
        assertEquals(7, directory.listFiles()!!.size)
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 6L * 262144)
        bitmap.recycle()
    }
    @Test fun aCachedImageCanBeReusedEvenWhenEverySlotIsFreshAndFull() {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, UUID.randomUUID().toString())
        val cache = MediaCoverCache(directory) { 100000L }
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(-16776961) }
        val first = cache.store(bitmap)!!; val bytes = first.readBytes()
        repeat(5) { index -> File(directory, "cp-${index.toString().padStart(12, '0')}.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(100000) } }
        assertEquals(6, directory.listFiles()!!.size)
        assertEquals(first, cache.store(bitmap))
        assertArrayEquals(bytes, first.readBytes()); assertEquals(6, directory.listFiles()!!.size)
        bitmap.recycle()
    }
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
        repeat(6) { index -> File(directory, "cp-${index.toString().padStart(12, '0')}.jpg").apply { writeBytes(ByteArray(262144)); setLastModified(time) } }
        val cache = MediaCoverCache(directory) { time }
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        assertNull(cache.store(bitmap)); assertEquals(6, directory.listFiles()!!.size)
        time += 61000
        assertNotNull(cache.store(bitmap)); assertEquals(1, directory.listFiles()!!.size)
        bitmap.recycle()
    }
}
