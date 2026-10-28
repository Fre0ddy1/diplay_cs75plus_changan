package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.ResultReceiver
import android.provider.Settings
import com.freddyq.diplay.customization.PlatformHotspotStarter
import com.freddyq.diplay.customization.VehicleCustomization
import com.freddyq.diplay.customization.VehicleCustomizations
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [PlatformHotspotReadyTest.ApState::class, PlatformHotspotReadyTest.Permission::class])
class PlatformHotspotReadyTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val extensions get() = ReflectionHelpers.getField<MutableList<VehicleCustomization>>(VehicleCustomizations, "extensions")
    private var saved = emptyList<VehicleCustomization>()
    private var starts = 0
    private var notices = 0
    private var cancelled = false
    private var reject = false
    private var noticeFails = false
    private var cancelAfterStart = false
    @Before fun setup() {
        saved = extensions.toList(); extensions.clear(); ApState.on = false; Permission.allowed = true
        VehicleCustomizations.register(object : VehicleCustomization {
            override val id = "test-platform"
            override fun platformHotspotStarter(context: Context) = object : PlatformHotspotStarter {
                override fun start(receiver: ResultReceiver) {
                    starts++
                    if (reject) { receiver.send(1, null); return }
                    ApState.on = true
                    receiver.send(0, null)
                    if (cancelAfterStart) cancelled = true
                }
                override fun onReady() { notices++; if (noticeFails) error("notice failure") }
            }
        })
    }
    @After fun restore() { extensions.clear(); extensions.addAll(saved) }
    private fun enable() = CarHotspotTethering.enable(app, { cancelled }, {})
    @Test fun restoredHotspotNotifiesOnceAndExistingOnDoesNotRepeat() {
        assertEquals(CarHotspotTethering.Result.READY, enable())
        assertEquals(1, starts); assertEquals(1, notices)
        assertEquals(CarHotspotTethering.Result.READY, enable())
        assertEquals(1, starts); assertEquals(1, notices)
    }
    @Test fun rejectedRequestHasNoSuccessNotice() {
        reject = true
        assertEquals(CarHotspotTethering.Result.FAILED, enable()); assertEquals(0, notices)
    }
    @Test fun permissionDenialHasNoRequestOrSuccessNotice() {
        Permission.allowed = false
        assertEquals(CarHotspotTethering.Result.PERMISSION_REQUIRED, enable())
        assertEquals(0, starts); assertEquals(0, notices)
    }
    @Test fun cancellationSuppressesLateSuccessNotice() {
        cancelAfterStart = true
        assertEquals(CarHotspotTethering.Result.CANCELLED, enable()); assertEquals(0, notices)
    }
    @Test fun noticeFailureCannotTurnAWorkingHotspotIntoConnectionFailure() {
        noticeFails = true
        assertEquals(CarHotspotTethering.Result.READY, enable()); assertTrue(ApState.on)
    }
    @Implements(WifiManager::class)
    class ApState {
        companion object { var on = false }
        @Implementation fun getWifiApState(): Int = if (on) 13 else 11
    }
    @Implements(Settings.System::class)
    class Permission {
        companion object {
            var allowed = true
            @Implementation @JvmStatic fun canWrite(context: Context): Boolean = allowed
        }
    }
}
