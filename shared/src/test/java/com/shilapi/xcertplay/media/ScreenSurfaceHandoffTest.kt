package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class ScreenSurfaceHandoffTest {
    private val sink=AndroidMediaSink()
    private val textures=(0..3).map { SurfaceTexture(0) }
    private val surfaces=textures.map(::Surface)
    private val release=CountDownLatch(1)
    private val workers=mutableListOf<Any>()
    private fun worker(type:Int):Any {
        val method=AndroidMediaSink::class.java.getDeclaredMethod("videoDecoder",Int::class.javaPrimitiveType).apply { isAccessible=true }
        return method.invoke(sink,type).also { workers+=it }
    }
    private fun blockFirstWorker() {
        sink.setSurface(111,surfaces[0]); sink.setSurface(112,surfaces[1])
        val first=worker(111); worker(112)
        val entered=CountDownLatch(1)
        val queue=ReflectionHelpers.getField<VideoDecodeQueue>(first,"queue")
        queue.offer(VideoJob.SurfaceChanged(null) { entered.countDown(); release.await(3,TimeUnit.SECONDS) })
        assertTrue("worker did not reach the barrier",entered.await(3,TimeUnit.SECONDS))
    }
    private fun bindings()=ReflectionHelpers.getField<Map<Int,Surface>>(sink,"surfaces")
    private fun awaitBinding(type:Int,surface:Surface) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while (bindings()[type] !== surface && System.nanoTime()<deadline) Thread.sleep(5)
        assertSame(surface,bindings()[type])
    }
    @After fun close() {
        release.countDown(); sink.close()
        workers.forEach { ReflectionHelpers.getField<Thread>(it,"thread").join(3000) }
        surfaces.forEach(Surface::release); textures.forEach(SurfaceTexture::release)
    }
    @Test fun neitherNewProducerAttachesBeforeBothOldWorkersAcknowledge() {
        blockFirstWorker()
        sink.rebindScreenPair(112,surfaces[0],111,surfaces[1])
        assertTrue(bindings().isEmpty())
        release.countDown()
        awaitBinding(112,surfaces[0]); awaitBinding(111,surfaces[1])
    }
    @Test fun aSupersededSwapCannotOverwriteTheLatestSurfacePair() {
        blockFirstWorker()
        sink.rebindScreenPair(112,surfaces[0],111,surfaces[1])
        sink.rebindScreenPair(111,surfaces[2],112,surfaces[3])
        release.countDown()
        awaitBinding(111,surfaces[2]); awaitBinding(112,surfaces[3])
    }
    @Test fun closingWhileTheHandoffIsPendingCannotReactivateEitherSurface() {
        blockFirstWorker()
        sink.rebindScreenPair(112,surfaces[0],111,surfaces[1])
        sink.close(); release.countDown()
        workers.forEach { ReflectionHelpers.getField<Thread>(it,"thread").join(3000) }
        assertTrue(bindings().isEmpty())
    }
}
