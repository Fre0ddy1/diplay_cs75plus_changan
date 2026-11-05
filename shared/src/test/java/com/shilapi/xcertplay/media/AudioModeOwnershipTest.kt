package com.shilapi.xcertplay.media

import android.media.AudioManager
import android.os.Looper
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29], manifest = Config.NONE, shadows = [ShadowAudioModeRequests::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AudioModeOwnershipTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(AudioManager::class.java)
    private val call = AudioStreamId(100, "telephony")
    private lateinit var sink: AndroidMediaSink
    @Before fun prepare() { ShadowAudioModeRequests.clearRequests(); sink = AndroidMediaSink(context = app) }
    @After fun restore() { sink.close(); ShadowAudioModeRequests.clearRequests() }
    private fun enter() = ReflectionHelpers.callInstanceMethod<Unit>(sink, "enterCommunicationMode",
        ReflectionHelpers.ClassParameter.from(AudioStreamId::class.java, call))
    private fun leave(id: AudioStreamId = call) = ReflectionHelpers.callInstanceMethod<Unit>(sink, "restoreAudioMode",
        ReflectionHelpers.ClassParameter.from(AudioStreamId::class.java, id))
    @Test fun releasingTheOtherOwnerAfterOurCallReturnsToNormalWithoutAStaleRequest() {
        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_IN_COMMUNICATION)
        enter(); leave()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_NORMAL)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }
    @Test fun aLiveExternalCallIsNotReleasedByOurCleanup() {
        enter()
        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_IN_CALL)
        leave()
        shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS)
        assertEquals(AudioManager.MODE_IN_CALL, manager.mode)
    }
    @Test fun anotherStreamCannotReleaseTheCurrentCall() {
        enter(); leave(AudioStreamId(100, "speechrecognition"))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        leave()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }
    @Test fun aFailingDiagnosticCannotPreventTakingOrReleasingOurMode() {
        sink.close()
        sink = AndroidMediaSink(context = app, onAudioDiagnostic = { throw IllegalStateException("report") })
        enter()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        leave()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }
}
