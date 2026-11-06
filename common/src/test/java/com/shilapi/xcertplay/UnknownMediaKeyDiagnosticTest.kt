package com.shilapi.xcertplay
import android.content.Intent
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class UnknownMediaKeyDiagnosticTest {
    @Test fun unknownPressIsLoggedOnceWhileKnownKeysKeepTheirDebounce() {
        val unknown = mutableListOf<String>(); val sent = mutableListOf<Int>()
        val callback = CarPlayMediaCallback(notUsed = unknown::add) { index, _, _ -> sent += index }
        fun event(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) =
            callback.onMediaButtonEvent(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(0, 0, action, code, repeat)))
        event(KeyEvent.KEYCODE_F1); event(KeyEvent.KEYCODE_F1); event(KeyEvent.KEYCODE_F2, KeyEvent.ACTION_UP)
        event(KeyEvent.KEYCODE_F2, repeat = 1)
        assertEquals(1, unknown.size)
        assertTrue(unknown.single().startsWith("keyCode=131 "))
        event(KeyEvent.KEYCODE_MEDIA_NEXT); callback.onSkipToNext()
        assertEquals(listOf(CarPlayMediaButton.NEXT), sent)
        for (key in 300..350) event(key)
        assertEquals(16, unknown.size)
    }
}
