package com.shilapi.xcertplay

import android.view.MotionEvent
import android.view.View
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HostTouchGestureIsolationTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var view: View
    private val reports = mutableListOf<List<AirPlayContact>>()
    @Before fun prepare() {
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        view = View(RuntimeEnvironment.getApplication()).apply { layout(0, 0, 800, 480) }
        val controller = mock(CarPlayController::class.java)
        doAnswer { reports += it.getArgument<List<AirPlayContact>>(0); true }.`when`(controller).sendTouch(anyList())
        ReflectionHelpers.setField(host, "controller", controller)
    }
    @After fun restore() {
        ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
    }
    @Test fun twoFingerInputKeepsPointerIdsThenThirdFingerReleasesPhoneTouches() {
        touch(MotionEvent.ACTION_DOWN, listOf(7))
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(7, 3))
        assertEquals(listOf(7, 3), reports.last().map { it.id })
        touch(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(7, 3, 9))
        assertTrue(reports.last().isEmpty())
        val sent = reports.size
        touch(MotionEvent.ACTION_MOVE, listOf(7, 3, 9))
        assertEquals(sent, reports.size)
        assertTrue(ReflectionHelpers.getField(host, "gestureSequenceActive"))
    }
    @Test fun fourthFingerRemainsAHostGestureAndDoesNotReachCarPlay() {
        touch(MotionEvent.ACTION_DOWN, listOf(7))
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(7, 3))
        touch(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(7, 3, 9))
        val sent = reports.size
        touch(MotionEvent.ACTION_POINTER_DOWN or (3 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(7, 3, 9, 2))
        assertEquals(sent, reports.size)
        assertEquals(4, ReflectionHelpers.getField<Int>(host, "gestureStartFingers"))
    }
    private fun touch(action: Int, ids: List<Int>) {
        val properties = ids.map { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coords = ids.indices.map { index -> MotionEvent.PointerCoords().apply { x = 380f + index * 10; y = 240f; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(0, 0, action, ids.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try {
            ReflectionHelpers.callInstanceMethod<Boolean>(host, "onHostTouch",
                ReflectionHelpers.ClassParameter.from(View::class.java, view),
                ReflectionHelpers.ClassParameter.from(MotionEvent::class.java, event))
        } finally { event.recycle() }
    }
}
