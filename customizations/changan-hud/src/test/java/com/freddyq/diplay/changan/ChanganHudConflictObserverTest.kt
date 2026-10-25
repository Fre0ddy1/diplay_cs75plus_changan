package com.freddyq.diplay.changan

import android.content.Intent
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,33],manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ChanganHudConflictObserverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun zero() = Intent(ChanganHudProfile.ACTION).putExtra("KEY_TYPE",10001).putExtra("NEW_ICON",0).putExtra("SEG_REMAIN_DIS",0)
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms,TimeUnit.MILLISECONDS)

    @Test fun `only complete zero guidance or end states are repairs`() {
        assertNotNull(ChanganHudConflictObserver.resetKind(zero()))
        assertNull(ChanganHudConflictObserver.resetKind(zero().putExtra("NEW_ICON",3)))
        assertNull(ChanganHudConflictObserver.resetKind(zero().putExtra("SEG_REMAIN_DIS",1)))
        assertNull(ChanganHudConflictObserver.resetKind(Intent(ChanganHudProfile.ACTION).putExtra("KEY_TYPE",10001)))
        assertNull(ChanganHudConflictObserver.resetKind(zero().setAction("other")))
        listOf(0,8,25,40,311).forEach { state ->
            assertNull(ChanganHudConflictObserver.resetKind(Intent(ChanganHudProfile.ACTION).putExtra("KEY_TYPE",10019).putExtra("EXTRA_STATE",state)))
        }
        assertNotNull(ChanganHudConflictObserver.resetKind(Intent(ChanganHudProfile.ACTION).putExtra("KEY_TYPE",10019).putExtra("EXTRA_STATE",9)))
    }

    @Test fun `foreign zero bursts coalesce and stop cancels pending work`() {
        var count = 0
        val observer = ChanganHudConflictObserver(app) { count++ }
        observer.start()
        observer.start()
        repeat(5) { app.sendBroadcast(zero()) }
        idle(0)
        assertEquals(0,count)
        idle(20)
        assertEquals(1,count)
        app.sendBroadcast(zero())
        idle(0)
        observer.stop()
        idle(100)
        assertEquals(1,count)
        observer.stop()
    }

    @Test fun `our package targeted HUD packet never loops back through observer`() {
        var count = 0
        val observer = ChanganHudConflictObserver(app) { count++ }
        observer.start()
        app.sendBroadcast(ChanganHudPacket.State(9).toIntent())
        app.sendBroadcast(zero().setPackage(ChanganHudProfile.RECEIVER_PACKAGE))
        idle(100)
        assertEquals(0,count)
        observer.stop()
    }

    @Test fun `old session delayed repair cannot run after restart`() {
        var count = 0
        val observer = ChanganHudConflictObserver(app) { count++ }
        observer.start()
        app.sendBroadcast(zero())
        idle(0)
        observer.stop()
        observer.start()
        idle(100)
        assertEquals(0,count)
        observer.stop()
    }
}
