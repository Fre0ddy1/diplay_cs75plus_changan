package com.shilapi.xcertplay.media
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MainVideoArrivalTest {
    @Test fun onlyMainFramesCountAndReopenedStreamsNeedANewFrame() {
        val sink = AndroidMediaSink()
        try {
            sink.onScreenStreamActive(110, true)
            assertFalse(sink.mainVideoReceived)
            sink.onVideoFrame(111, byteArrayOf(0, 0, 1, 9))
            assertFalse(sink.mainVideoReceived)
            sink.onVideoFrame(110, byteArrayOf(0, 0, 1, 9))
            assertTrue(sink.mainVideoReceived)
            sink.onScreenStreamActive(110, true)
            assertTrue(sink.mainVideoReceived)
            sink.onScreenStreamActive(110, false)
            sink.onScreenStreamActive(110, true)
            assertFalse(sink.mainVideoReceived)
        } finally { sink.close() }
    }
}
