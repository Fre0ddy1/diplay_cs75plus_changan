package com.shilapi.xcertplay
import android.content.Context
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LastConnectionProblemTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { LastConnectionProblem.clear(app) }
    @Test fun knownCauseIsSavedWithoutThePrivateExceptionMessage() {
        LastConnectionProblem.follow(app, CarPlayStatus.Failed("ssid=secret", knownCause = ConnectionFailureCause.CAR_HOTSPOT_DETAILS_DIFFER))
        assertTrue(LastConnectionProblem.report(app).contains("CAR_HOTSPOT_DETAILS_DIFFER"))
        assertFalse(LastConnectionProblem.report(app).contains("secret"))
        assertNotNull(LastConnectionProblem.homeLine(app))
    }
    @Test fun repeatedPollsDoNotRewriteTimeAndRecoveryClearsTheWarning() {
        LastConnectionProblem.follow(app, CarPlayStatus.IphoneNotListed(false))
        val prefs = app.getSharedPreferences("diplay_last_problem", Context.MODE_PRIVATE)
        prefs.edit().putLong("at", 123L).commit()
        LastConnectionProblem.follow(app, CarPlayStatus.IphoneNotListed(false))
        assertEquals(123L, prefs.getLong("at", -1))
        LastConnectionProblem.follow(app, CarPlayStatus.IphoneNotListed(true))
        assertTrue(LastConnectionProblem.report(app).contains("usb:other"))
        LastConnectionProblem.clear(app)
        assertNull(LastConnectionProblem.homeLine(app))
    }
    @Test fun invalidAndUnknownTokensCannotBecomeRawUiText() {
        val prefs = app.getSharedPreferences("diplay_last_problem", Context.MODE_PRIVATE)
        prefs.edit().putString("problem", "ssid=hidden").putLong("at", 123L).commit()
        assertNull(LastConnectionProblem.homeLine(app))
        assertFalse(LastConnectionProblem.report(app).contains("hidden"))
        LastConnectionProblem.follow(app, CarPlayStatus.Failed("private name"))
        assertTrue(LastConnectionProblem.report(app).contains("general"))
    }

    @Test fun crashRecorderWritesThenDelegatesToTheExistingHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val installed = org.robolectric.util.ReflectionHelpers.getStaticField<Boolean>(CrashRecord::class.java, "installed")
        var delegated = 0
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated++ }
            org.robolectric.util.ReflectionHelpers.setStaticField(CrashRecord::class.java, "installed", false)
            CrashRecord.install(app)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("diagnostic crash"))
            assertEquals(1, delegated)
            assertTrue(CrashRecord.report(app).contains("IllegalStateException: diagnostic crash"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
            org.robolectric.util.ReflectionHelpers.setStaticField(CrashRecord::class.java, "installed", installed)
        }
    }
}
