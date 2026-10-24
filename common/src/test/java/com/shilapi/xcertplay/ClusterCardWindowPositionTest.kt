package com.shilapi.xcertplay

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The per-card position store, as used by the Apple full-navigation card WINDOW ("apple").
 *
 * Two properties matter more than the values themselves:
 * - the window's default must be the panel origin (0,0), not the drawn cards' 43,43 fallback,
 *   or the first launch would offset Apple's card for no reason;
 * - a coordinate correction made from a layout pass must be persisted WITHOUT notifying the
 *   overlay-settings listener. That is what keeps `read -> clamp -> write` in
 *   ClusterMapPresentation.cardWindowParams() from cascading into another apply/refresh round;
 *   a silent save that started notifying would turn every layout pass into a recursion risk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ClusterCardWindowPositionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    private companion object {
        /** ClusterMapPresentation.CARD_WINDOW_CARD_ID. */
        const val APPLE_WINDOW = "apple"

        /** Panel bounds the store clamps against (UNI-V dashboard canvas). */
        const val PANEL_WIDTH = 1920
        const val PANEL_HEIGHT = 720
    }

    @Before
    fun clearState() {
        prefs.edit().clear().apply()
        AirPlayPersistence.overlaySettingsListener = null
    }

    @After
    fun releaseListener() {
        AirPlayPersistence.overlaySettingsListener = null
    }

    @Test
    fun theAppleWindowDefaultsToThePanelOrigin() {
        // 43 would be the fallback of a drawn card; the window must not inherit it.
        assertEquals(0, AirPlayPersistence.loadClusterCardX(context, APPLE_WINDOW))
        assertEquals(0, AirPlayPersistence.loadClusterCardY(context, APPLE_WINDOW))
    }

    @Test
    fun aSavedAppleWindowPositionIsStoredAndReadBack() {
        AirPlayPersistence.saveClusterCardX(context, APPLE_WINDOW, 420)
        AirPlayPersistence.saveClusterCardY(context, APPLE_WINDOW, 180)
        assertEquals(420, AirPlayPersistence.loadClusterCardX(context, APPLE_WINDOW))
        assertEquals(180, AirPlayPersistence.loadClusterCardY(context, APPLE_WINDOW))
    }

    @Test
    fun aSilentlyCorrectedPositionIsPersistedWithoutNotifyingTheListener() {
        var notifications = 0
        AirPlayPersistence.overlaySettingsListener = { notifications++ }

        AirPlayPersistence.saveClusterCardXSilent(context, APPLE_WINDOW, 1440)
        AirPlayPersistence.saveClusterCardYSilent(context, APPLE_WINDOW, 520)

        assertEquals(0, notifications)
        assertEquals(1440, AirPlayPersistence.loadClusterCardX(context, APPLE_WINDOW))
        assertEquals(520, AirPlayPersistence.loadClusterCardY(context, APPLE_WINDOW))
    }

    @Test
    fun aDriverEnteredPositionNotifiesTheListenerOncePerAxis() {
        // The contrast with the silent path: the settings Apply button must reach the live
        // window, so the notifying savers have to fire the listener.
        var notifications = 0
        AirPlayPersistence.overlaySettingsListener = { notifications++ }

        AirPlayPersistence.saveClusterCardX(context, APPLE_WINDOW, 100)
        AirPlayPersistence.saveClusterCardY(context, APPLE_WINDOW, 200)

        assertTrue("expected the overlay listener to be told about the change", notifications >= 2)
    }

    @Test
    fun theStoreClampsAPositionBackToThePanel() {
        // cardWindowParams() clamps further, against the card size; the store's own guarantee is
        // that nothing outside the panel canvas can ever be read back.
        AirPlayPersistence.saveClusterCardX(context, APPLE_WINDOW, 5000)
        AirPlayPersistence.saveClusterCardY(context, APPLE_WINDOW, 5000)
        assertEquals(PANEL_WIDTH, AirPlayPersistence.loadClusterCardX(context, APPLE_WINDOW))
        assertEquals(PANEL_HEIGHT, AirPlayPersistence.loadClusterCardY(context, APPLE_WINDOW))

        AirPlayPersistence.saveClusterCardX(context, APPLE_WINDOW, -200)
        AirPlayPersistence.saveClusterCardY(context, APPLE_WINDOW, -200)
        assertEquals(0, AirPlayPersistence.loadClusterCardX(context, APPLE_WINDOW))
        assertEquals(0, AirPlayPersistence.loadClusterCardY(context, APPLE_WINDOW))
    }

    @Test
    fun aStoredOutOfRangeValueIsClampedOnRead() {
        // A build that wrote a coordinate before the clamp existed must not read it back raw.
        prefs.edit()
            .putInt("cluster_card_${APPLE_WINDOW}_x_abs", 9999)
            .putInt("cluster_card_${APPLE_WINDOW}_y_abs", -1)
            .apply()
        assertEquals(PANEL_WIDTH, AirPlayPersistence.loadClusterCardX(context, APPLE_WINDOW))
        assertEquals(0, AirPlayPersistence.loadClusterCardY(context, APPLE_WINDOW))
    }
}
