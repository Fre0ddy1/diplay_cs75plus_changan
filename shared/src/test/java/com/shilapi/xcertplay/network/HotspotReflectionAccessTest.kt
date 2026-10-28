package com.shilapi.xcertplay.network

import android.os.ResultReceiver
import com.shilapi.xcertplay.network.CarHotspotTethering.Result
import com.shilapi.xcertplay.network.hidden.PrivateHotspotFixture
import com.shilapi.xcertplay.network.hidden.PublicHotspotContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HotspotReflectionAccessTest {
    private val receiver = ResultReceiver(null)
    @Test fun oldRuntimeClassLookupCannotInvokeNonPublicBinderProxy() {
        val service = PrivateHotspotFixture.service { fail("must not call through inaccessible Method") }
        val method = service.javaClass.getMethod("startTethering", Int::class.javaPrimitiveType,
            ResultReceiver::class.java, Boolean::class.javaPrimitiveType, String::class.java)
        assertThrows(IllegalAccessException::class.java) { method.invoke(service, 0, receiver, false, "example") }
    }
    @Test fun publicContractFallbackAvoidsJavaProxyVisibilityFailure() {
        var calls = 0
        val service = PrivateHotspotFixture.service { calls++ }
        AndroidHotspotStarter.invokeStart(service, PublicHotspotContract::class.java, receiver, "example")
        assertEquals(1, calls)
    }
    @Test fun javaAccessFailureIsNotMislabelledAsMissingFirmwareApi() {
        val log = mutableListOf<String>()
        val result = CarHotspotTethering.enable(20, { false }, { true }, { false }, { log += it }) { throw IllegalAccessException() }
        assertEquals(Result.FAILED, result); assertTrue(log.any { "Java access" in it })
    }
    @Test fun lookupFailureRecordsExceptionType() {
        val log = mutableListOf<String>()
        val result = CarHotspotTethering.enable(20, { false }, { true }, { false }, { log += it }) { throw NoSuchMethodException() }
        assertEquals(Result.UNSUPPORTED, result); assertTrue(log.any { "NoSuchMethodException" in it })
    }
    @Test fun permissionDiagnosticsKeepPermissionNamesWithoutRawErrorPayload() {
        val log = mutableListOf<String>()
        val result = CarHotspotTethering.enable(20, { false }, { true }, { false }, { log += it }) {
            throw InvocationTargetException(SecurityException("private-payload android.permission.TETHER_PRIVILEGED"))
        }
        assertEquals(Result.PERMISSION_REQUIRED, result)
        assertTrue(log.any { "android.permission.TETHER_PRIVILEGED" in it }); assertTrue(log.none { "private-payload" in it })
    }
    @Test fun acceptedRequestStillRequiresActualOnStateAndLogsTimeout() {
        val log = mutableListOf<String>()
        val result = CarHotspotTethering.enable(20, { false }, { true }, { false }, { log += it }) { it.send(0, null) }
        assertEquals(Result.TIMED_OUT, result)
        assertTrue(log.any { "resultCode=0" in it }); assertTrue(log.any { "did not report ON" in it })
    }
}
