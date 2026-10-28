package com.freddyq.diplay.hotspot

import android.os.Bundle
import android.os.Looper
import android.os.ResultReceiver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FrameworkHotspotStarterTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager = FakeConnectivityManager()
    private val log = mutableListOf<String>()
    private var result: Int? = null
    private val receiver = object : ResultReceiver(null) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { result = resultCode }
    }
    private fun starter(wrapper: Class<*> = FakeConnectivityManager::class.java) =
        FrameworkHotspotStarter(app, { log += it }, wrapper, { manager })
    @Test fun wrapperReceivesWifiTypeAndMainHandlerWithoutPrivateBinderAccess() {
        starter().start(receiver)
        assertEquals(1, manager.calls); assertEquals(0, manager.type)
        assertFalse(manager.provisioning); assertSame(Looper.getMainLooper(), manager.handler.looper)
        assertNull(result)
    }
    @Test fun actualFrameworkCallbackSuperclassIsUsedAndReportsAcceptance() {
        starter().start(receiver)
        assertEquals("android.net.ConnectivityManager\$OnStartTetheringCallback", manager.callback.javaClass.superclass.name)
        manager.callback.javaClass.getMethod("onTetheringStarted").invoke(manager.callback)
        assertEquals(0, result)
    }
    @Test fun failureCallbackReportsFailureWithoutCallingStartAgain() {
        starter().start(receiver)
        manager.callback.javaClass.getMethod("onTetheringFailed").invoke(manager.callback)
        assertEquals(1, result); assertEquals(1, manager.calls)
    }
    @Test fun missingWrapperMethodIsReportedBeforeAnyRequest() {
        assertThrows(NoSuchMethodException::class.java) { starter(String::class.java).start(receiver) }
        assertEquals(0, manager.calls)
    }
    @Test fun permissionRejectionRemainsVisibleToTheCaller() {
        manager.reject = true
        val error = assertThrows(InvocationTargetException::class.java) { starter().start(receiver) }
        assertTrue(error.targetException is SecurityException)
        assertEquals(0, manager.calls)
    }
}
