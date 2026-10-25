package com.shilapi.xcertplay

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.freddyq.diplay.customization.ProjectionControl
import com.freddyq.diplay.customization.ProjectionLayout
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class InCallProjectionControlTest {
    private val calls = mutableListOf<Pair<Int,List<Int>>>()
    private var oldBinder: IBinder? = null
    private var oldOpen = false
    private var oldCallbacks: Map<Int,Int> = emptyMap()
    private val callbacks get() = ReflectionHelpers.getStaticField<MutableMap<Int,Int>>(InCallClusterBridge::class.java,"lastCallbackValue")
    private val endpoint get() = ReflectionHelpers.getStaticField<ProjectionControl>(InCallClusterBridge::class.java,"projectionControl")
    @Before fun prepare() {
        oldBinder = ReflectionHelpers.getStaticField(InCallClusterBridge::class.java,"binder")
        oldOpen = ReflectionHelpers.getStaticField(InCallClusterBridge::class.java,"openState")
        oldCallbacks = callbacks.toMap()
        callbacks.clear()
        val fake = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.incall.serversdk.interactive.IDouInteractiveManager")
                val values = mutableListOf<Int>()
                repeat(if (code == 1) 1 else 2) { values += data.readInt() }
                calls += code to values
                reply?.writeNoException()
                reply?.writeInt(1)
                return true
            }
        }
        ReflectionHelpers.setStaticField(InCallClusterBridge::class.java,"binder",fake)
        ReflectionHelpers.setStaticField(InCallClusterBridge::class.java,"openState",true)
    }
    @After fun restore() {
        ReflectionHelpers.setStaticField(InCallClusterBridge::class.java,"binder",oldBinder)
        ReflectionHelpers.setStaticField(InCallClusterBridge::class.java,"openState",oldOpen)
        callbacks.clear()
        callbacks.putAll(oldCallbacks)
    }
    @Test fun `fullscreen uses LVDS three while preserving gesture source and finish flag`() {
        assertTrue(endpoint.requestLayout(ProjectionLayout.FULLSCREEN))
        assertEquals(listOf(21 to listOf(1,0),8 to listOf(3,0),1 to listOf(0)),calls)
    }
    @Test fun `released projection cannot be reopened by a delayed customization request`() {
        ReflectionHelpers.setStaticField(InCallClusterBridge::class.java,"openState",false)
        assertFalse(endpoint.requestLayout(ProjectionLayout.FULLSCREEN))
        assertTrue(calls.isEmpty())
    }
    @Test fun `rejected binder transaction is not reported as dispatched`() {
        ReflectionHelpers.setStaticField(InCallClusterBridge::class.java,"binder",object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int) = false
        })
        assertFalse(endpoint.requestLayout(ProjectionLayout.FULLSCREEN))
    }
    @Test fun `reconnect preserves already confirmed full layout without sending card mode`() {
        callbacks[1] = 3
        callbacks[2] = 3
        InCallClusterBridge.openProjection(RuntimeEnvironment.getApplication(),automatic=true)
        assertTrue(calls.none { it.first in setOf(1,8,21) })
    }
}
