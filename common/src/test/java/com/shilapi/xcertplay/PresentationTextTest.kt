package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.Locale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN", manifest = Config.NONE)
class PresentationTextTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        for (name in listOf("diplay", "xcertplay_airplay", "diplay_display_diagnostics")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun connectionStagesDoNotFallBackToPreparingInNonEnglishLocales() {
        for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH, Locale.forLanguageTag("ar"))) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
            for (status in listOf(CarPlayStatus.Pairing, CarPlayStatus.RequestingIphonePermission,
                CarPlayStatus.WaitingForReenumeration, CarPlayStatus.ConnectingBluetooth, CarPlayStatus.OpeningDataPaths)) {
                val text = localized.getString(ConnectionStageText.resource(status, false, WirelessHotspotMode.MANUAL))
                assertNotEquals(localized.getString(R.string.getting_carplay_ready), text)
                assertTrue(text.isNotBlank())
            }
            assertEquals(localized.getString(R.string.chosen_iphone_not_paired), localized.getString(
                ConnectionStageText.resource(CarPlayStatus.Failed("unused", knownCause = ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED),
                    true, WirelessHotspotMode.MANUAL)))
        }
    }

    @Test fun networkStageKeepsTransportAndModeWithoutDisplayingEndpointDetails() {
        assertEquals(R.string.status_attaching_ncm, ConnectionStageText.resource(CarPlayStatus.AttachingNetwork, false, WirelessHotspotMode.MANUAL))
        assertEquals(R.string.starting_airplay_service, ConnectionStageText.resource(CarPlayStatus.AttachingNetwork, true, WirelessHotspotMode.MANUAL))
        assertEquals(R.string.existing_wifi_attaching, ConnectionStageText.resource(CarPlayStatus.StartingHotspot, true, WirelessHotspotMode.EXISTING_WIFI))
        val ready = CarPlayStatus.HotspotReady("private-name", "5GHz", 36, "private-bssid", "192.0.2.1", "backend")
        val shown = context.getString(ConnectionStageText.resource(ready, true, WirelessHotspotMode.MANUAL))
        assertFalse(shown.contains("private")); assertFalse(shown.contains("192.0.2.1"))
    }

    @Test fun hostRedrawKeepsTheTypedChineseStageAndVpnExplanation() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val view = TextView(host)
        ReflectionHelpers.setField(host, "stageStatusView", view)
        val display = host.getString(ConnectionStageText.resource(CarPlayStatus.Pairing, true, WirelessHotspotMode.MANUAL))
        ReflectionHelpers.callInstanceMethod<Unit>(host, "setConnectionStage",
            ReflectionHelpers.ClassParameter.from(String::class.java, "localized diagnostic text"),
            ReflectionHelpers.ClassParameter.from(String::class.java, display))
        ReflectionHelpers.callInstanceMethod<Unit>(host, "updateDebugOverlays")
        assertEquals(display, view.text.toString())
        val help = host.getString(R.string.vpn_consent_launch_failed)
        val shown = ReflectionHelpers.callInstanceMethod<String>(host, "friendlyStage",
            ReflectionHelpers.ClassParameter.from(String::class.java, help))
        assertEquals(help, shown)
    }

    @Test fun unnamedHotspotUsesGenericTextAndARealNameIsPreserved() {
        AirPlayPersistence.saveManualHotspotSsid(context, " ")
        assertEquals(context.getString(R.string.hotspot_details_off), SettingsPresentation.hotspotOff(context))
        assertEquals(context.getString(R.string.hotspot_connect_without_name), SettingsPresentation.hotspotOff(context, true))
        AirPlayPersistence.saveManualHotspotSsid(context, "车机热点")
        assertTrue(SettingsPresentation.hotspotOff(context).contains("车机热点"))
        assertTrue(SettingsPresentation.hotspotOff(context, true).contains("车机热点"))
    }

    @Test fun phoneLabelsDoNotInventASelectedPhoneOrReplaceAGenuineName() {
        assertEquals(context.getString(R.string.choose_iphone), SettingsPresentation.choosePhone(context))
        DiPlayPreferences.savePhone(context, "test-address", "真实手机名")
        assertTrue(SettingsPresentation.choosePhone(context).endsWith("真实手机名"))
        DiPlayPreferences.savePhone(context, "test-address", " ")
        assertEquals(context.getString(R.string.unnamed_iphone), DiPlayPreferences.phoneName(context))
        assertEquals("test-address", DiPlayPreferences.phoneAddress(context))
        DiPlayPreferences.savePhone(context, "test-address", "Your iPhone")
        assertEquals("Your iPhone", DiPlayPreferences.phoneName(context))
    }

    @Test fun noNetworkResponseDoesNotPretendTheUsbSwitchWasOff() {
        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
        assertEquals(R.string.adb_unreachable_usb_on, SettingsPresentation.adbUnreachable(context))
        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        assertEquals(R.string.adb_unreachable_usb_off, SettingsPresentation.adbUnreachable(context))
        assertEquals(R.string.adb_unreachable_unknown, SettingsPresentation.adbUnreachable(null as Int?))
    }

    @Test fun savedSettingsAndLastRequestRemainDistinctAndReadingWritesNothing() {
        AirPlayPersistence.saveWidthPhysicalMm(context, 200)
        AirPlayPersistence.savePhysicalSizeBasis(context, AirPlayPhysicalSizeBasis.HEIGHT)
        AirPlayPersistence.saveDisplayScaleTenths(context, 7)
        DisplayDiagnosticSnapshot.begin(context,
            "Display request surface=1920x1080 resolution=80% canvas=1536x864 fps=60",
            "capability", "Display effective canvas=1536x864")
        val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        val before = preferences.all.toMap()
        val reference = SettingsPresentation.physicalReference(context)
        assertTrue(reference.contains("200"))
        assertTrue(reference.contains(context.getString(R.string.basis_longest_height)))
        val latest = SettingsPresentation.lastDisplayRequest(context)
        assertTrue(latest.contains("1536 × 864")); assertFalse(latest.contains("1920 × 1080"))
        val steps = SettingsPresentation.scales(AirPlayPersistence.loadDisplayScaleTenths(context))
        assertTrue(7 in steps); assertEquals(listOf(10, 8, 7, 6, 5, 4), steps)
        assertEquals(before, preferences.all)
        assertEquals(7, AirPlayPersistence.loadDisplayScaleTenths(context))
    }

    @Test fun absentOrUnrecognizedSnapshotIsNeverShownAsCurrentResolution() {
        assertEquals(context.getString(R.string.last_display_request_unavailable), SettingsPresentation.lastDisplayRequest(context))
        DisplayDiagnosticSnapshot.begin(context, "unrecognized-private-text", "capability", "effective")
        assertEquals(context.getString(R.string.last_display_request_unavailable), SettingsPresentation.lastDisplayRequest(context))
    }

    @Test fun homeTransportAndManualVpnHelpDoNotChangeSavedConnection() {
        assertTrue(HomeCardTransport.wired(true, false))
        assertFalse(HomeCardTransport.wired(true, true))
        assertFalse(HomeCardTransport.wired(false, false))
        AirPlayPersistence.saveWirelessEnabled(context, false)
        val before = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).all.toMap()
        assertTrue(SettingsPresentation.vpnHelp(context).contains("adb shell appops set ${context.packageName} ACTIVATE_VPN allow"))
        assertEquals(before, context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).all)
    }
}
