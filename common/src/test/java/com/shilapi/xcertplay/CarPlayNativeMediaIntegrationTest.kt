package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import com.freddyq.diplay.customization.*
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayNativeMediaIntegrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controller = mock(CarPlayController::class.java)
    private val snapshots = mutableListOf<MediaSnapshot>()
    private val started = mutableListOf<Any>()
    private val ended = mutableListOf<Any>()
    private val extensions get() = ReflectionHelpers.getField<MutableList<VehicleCustomization>>(VehicleCustomizations, "extensions")
    private var previous = emptyList<VehicleCustomization>()
    @Before fun setup() {
        previous = extensions.toList(); extensions.clear()
        VehicleCustomizations.register(object : VehicleCustomization {
            override val id = "media-integration-test"
            override fun onMediaSessionStarted(context: Context, owner: Any) { started += owner }
            override fun onMediaSnapshot(context: Context, owner: Any, snapshot: MediaSnapshot) { snapshots += snapshot }
            override fun onMediaSessionEnded(owner: Any) { ended += owner }
        })
        CarPlayMediaKeys.attach(app, controller)
    }
    @After fun teardown() {
        CarPlayMediaKeys.detach(controller)
        extensions.clear(); extensions.addAll(previous)
    }
    private fun update(value: CarPlayNowPlaying) = ReflectionHelpers.callInstanceMethod<Unit>(CarPlayMediaKeys, "onNowPlayingChanged",
        ReflectionHelpers.ClassParameter.from(CarPlayController::class.java, controller),
        ReflectionHelpers.ClassParameter.from(CarPlayNowPlaying::class.java, value))
    @Test fun pausedMetadataIsForwardedWithoutRequestingFocusOrSendingAControl() {
        update(CarPlayNowPlaying(title = "手机QQ", artist = "歌手", elapsedMillis = 9000, playing = false))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("手机QQ", snapshots.last().item.title); assertFalse(snapshots.last().playing)
        assertFalse(snapshots.last().outputOwned)
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "focusRequest"))
        verify(controller, never()).sendMediaButton(anyInt())
    }
    @Test fun queuedOldMetadataCannotLeakAcrossAReconnectUsingTheSameController() {
        update(CarPlayNowPlaying(title = "旧连接", playing = true))
        CarPlayMediaKeys.onPhoneSessionEnded(); CarPlayMediaKeys.onPhoneSessionStarted()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(snapshots.isEmpty())
        assertEquals(2, started.size); assertNotSame(started[0], started[1]); assertSame(started[0], ended.single())
        update(CarPlayNowPlaying(title = "新连接", playing = false))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("新连接", snapshots.single().item.title)
    }
    @Test fun transientAndroidFocusLossDoesNotDeselectThePhonesDisplaySource() {
        ReflectionHelpers.setField(CarPlayMediaKeys, "mainAudioOwner", com.shilapi.xcertplay.airplay.CarPlayAudioOwner.PHONE)
        ReflectionHelpers.setField(CarPlayMediaKeys, "focusHeld", true)
        ReflectionHelpers.setField(CarPlayMediaKeys, "transientFocusInterrupted", true)
        update(CarPlayNowPlaying(title = "手机QQ", elapsedMillis = 9000, playing = true))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(snapshots.last().outputOwned); assertTrue(snapshots.last().sourceSelected)
        verify(controller, never()).sendMediaButton(anyInt())
    }
    @Test fun nativeAudioOwnerIsRespectedEvenForPausedMetadata() {
        ReflectionHelpers.setField(CarPlayMediaKeys, "mainAudioOwner", com.shilapi.xcertplay.airplay.CarPlayAudioOwner.ACCESSORY)
        update(CarPlayNowPlaying(title = "暂停的手机QQ", playing = false))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(snapshots.last().sourceSelected)
        assertEquals("暂停的手机QQ", snapshots.last().item.title)
        verify(controller, never()).sendMediaButton(anyInt())
    }
    @Test fun permanentAndroidLeaseLossDoesNotRepublishAPausedCardOverTheNativeSource() {
        ReflectionHelpers.setField(CarPlayMediaKeys, "mainAudioOwner", com.shilapi.xcertplay.airplay.CarPlayAudioOwner.PHONE)
        ReflectionHelpers.setField(CarPlayMediaKeys, "focusHeld", false)
        update(CarPlayNowPlaying(title = "原车接管后的暂停音乐", playing = false))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(snapshots.last().sourceSelected)
        assertFalse(snapshots.last().outputOwned)
        verify(controller, never()).sendMediaButton(anyInt())
    }
}
