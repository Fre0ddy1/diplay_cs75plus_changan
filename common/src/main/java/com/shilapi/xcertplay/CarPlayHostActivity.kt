package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.isManualHotspotChannelCompatible
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.UsbDeviceId
import com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen CarPlay host. It renders decoded video through a [TextureView], forwards touch to
 * the active AirPlay session, and drives the complete wired or wireless bring-up through
 * [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private data class SettingsBaseline(
        val safeAreaSize: DisplaySize?,
        val safeAreaRect: SafeAreaRect?,
        val customIconBytes: ByteArray?,
    )

    private var connectionPanel: View? = null
    private var wifiRecoveryButton: View? = null
    private var reconnectAttempts = 0
    private lateinit var airPlayIdentity: AirPlayIdentity
    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.LOCAL,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = "DiPlay",
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "DIPLAY-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", ""),
            firmwareVersion = "0.1.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
            vehicleStatusEnabled = com.shilapi.xcertplay.hud.BydOutputSettings.batteryToIphoneActive(this),
            chargingConnectors = com.shilapi.xcertplay.hud.BydOutputSettings.chargingConnectors(this),
            vehicleSpeedEnabled = locationReportingEnabled && com.shilapi.xcertplay.hud.BydOutputSettings.wheelSpeedToIphoneActive(this),
        ),
        label = "DiPlay",
        hostName = "diplay-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", "").lowercase(),
        hostMac = DiPlayBootstrap.deviceId(airPlayIdentity).split(":").map { it.toInt(16).toByte() }.toByteArray(),
        wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(this),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        wifiP2pPreferredChannel = AirPlayPersistence.loadWifiP2pPreferredChannel(this),
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        existingWifiSsid = existingWifiSsid,
        existingWifiPassphrase = existingWifiPassphrase,
        locationReportingEnabled = locationReportingEnabled,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingVpnConsent = false
            if (result.resultCode == RESULT_OK) {
                vpnReady = true
                runCatching { maybeStartCarPlay() }
                    .onFailure { error ->
                        appendLog("VPN consent: start failed ${error.javaClass.simpleName}: ${error.message}")
                    }
            } else {
                setStatus(getString(R.string.vpn_consent_was_denied))
            }
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            requestStartupPrerequisites()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                if (!menuOpen) {
                    AirPlayPersistence.saveLocationReportingEnabled(
                        this@CarPlayHostActivity,
                        false,
                    )
                }
                locationReportingSwitch?.isChecked = false
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            updateResolutionMenu()
            if (!menuOpen) requestStartupPrerequisites()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                externalActivityInProgress = false
                return@registerForActivityResult
            }
            imageCrop.launch(
                Intent(this, ImageCropActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    private val imageCrop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            externalActivityInProgress = false
            if (result.resultCode == RESULT_OK) {
                updateAirPlayIconPreview()
                appendLog("Custom AirPlay icon updated")
            }
        }

    private var videoView: TextureView? = null
    private var gestureOverlay: View? = null
    private var settingsMenu: View? = null
    private var mfiTargetGroup: RadioGroup? = null
    private var mfiI2cFields: View? = null
    private var mfiRemoteFields: View? = null
    private var mfiErrorView: TextView? = null
    private var mfiI2cPathInput: EditText? = null
    private var remoteMfiServerInput: EditText? = null
    private var remoteMfiTokenInput: EditText? = null
    private var settingsBaseline: SettingsBaseline? = null
    private var locationReportingSwitch: Switch? = null
    private var statusView: TextView? = null
    private var statusScrollView: ScrollView? = null
    private var stageStatusView: TextView? = null
    private var resolutionValueView: TextView? = null
    private var resolutionPreviewView: TextView? = null
    private var hotspotStatusView: TextView? = null
    private var manualHotspotFields: View? = null
    private var existingWifiFields: View? = null
    private var existingWifiErrorView: TextView? = null
    private var manualHotspotErrorView: TextView? = null
    private var iconPreviewView: ImageView? = null
    private var iconStatusView: TextView? = null
    private var safeAreaSummaryView: TextView? = null
    private var safeAreaEditor: View? = null
    private var safeAreaEditorView: SafeAreaEditorView? = null
    private var safeAreaEditSize: DisplaySize? = null
    private var safeAreaEditorActive = false
    private var externalActivityInProgress = false
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private var currentSurface: Surface? = null
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var clusterPresentation: ClusterMapPresentation? = null
    /**
     * The picture source the current dashboard window was built for.
     *
     * CarPlay learns the cluster URL from the window's display config, so it cannot be changed on
     * a live window - a changed setting needs the window rebuilt. Null before the first build.
     */
    private var clusterWindowContent: CarPlayClusterDisplay.Content? = null
    private var clusterSurface: Surface? = null
    // Dual-resident surfaces on the generic (Changan) path: the whole-screen mirror (110) and the
    // navigation stream (111) are BOTH bound to the incall sink from the first projection and stay
    // bound; mode switches only flip view visibility (ClusterMapPresentation.setMode). Keeping 111
    // permanently active stops the head-unit from announcing "全屏导航已开启" on every mode switch
    // (incall announces on 111 stream (re-)activation; the OEM box keeps the stream resident too).
    private var mirrorClusterSurface: Surface? = null
    private var navClusterSurface: Surface? = null
    // FLY_CARD only: the small card window's Surface, bound to the SECOND cluster stream
    // (instruction card, SCREEN_TYPE_ALT_CARD). Created with the presentation; null everywhere else.
    private var cardClusterSurface: Surface? = null
    private var clusterMonitor: DiLink51ClusterMonitor? = null
    private var detectedCluster = ClusterActivityState.Snapshot(null, false)
    // Auto-project on connect: set when the pending delayed auto-projection was fired for
    // the current session, so a reconnect/restart does not re-trigger it mid-flight.
    private var autoProjectTriggered = false
    private var autoProjectToken = Any()
    // Auto-project robustness. Two separate cold-start hazards, both field-observed as
    // "auto projection sometimes does not come up, a later manual swipe does":
    //   (1) bring-up race - the head-unit silently drops the activation sequence when it arrives
    //       while the dashboard path is still coming up (incall service starting, stock Amap not
    //       running yet). Handled by awaitAutoProjectReady (bounded readiness gate) plus the
    //       bind-gated sequence in InCallClusterBridge.
    //   (2) dropped sequence - the head unit ignored the (now correctly timed) sequence anyway.
    //       There is no observable success signal (the projection display exists before
    //       activation, and both streams are decoded into our own surfaces regardless of what the
    //       head unit does), so the previous "verify" always reported success and never retried.
    //       Replaced by a bounded retry ladder that mirrors the driver's own manual recovery.
    private val autoProjectReadyTimeoutMs = 25_000L
    private val autoProjectReadyPollMs = 1_000L
    /**
     * The gate's preconditions are normally met within a second of the session coming up, so a flat
     * 1 s poll wasted up to that second on every connect. The first few seconds poll fast; after
     * that the cheap interval takes over, because a precondition that is still missing is the slow
     * case (cold boot) where 250 ms polling buys nothing.
     */
    private val autoProjectReadyFastPollMs = 250L
    private val autoProjectReadyFastWindowMs = 4_000L
    /**
     * Settle time after the session goes active before the readiness gate takes over. The gate's own
     * conditions are the real readiness signal, so this is not the old 5 s fixed trigger - see the
     * call site.
     */
    private val autoProjectSettleMs = 1_000L
    // The re-asserts go through InCallClusterBridge.reassertProjection(), which sends only F0 + C0
    // + Z. That was NOT always true - it used to send tx2[1] as well while its comment claimed
    // otherwise, and the result was one activation announcing three times (the open plus the two
    // retries that had run). It is broadcast-only now, so a silent re-assert is free and the ladder
    // can afford several of them instead of one loud re-send.
    private val autoProjectRetryDelaysMs = longArrayOf(11_000L, 30_000L, 60_000L)
    // Keep one surface per layer alive, including while its map card is hidden.
    private val clusterLayers = mutableMapOf<Boolean, ClusterMapPresentation>()
    private var clusterTurnGuidance: com.shilapi.xcertplay.hud.ClusterTurnGuidance? = null
    // Throttle the per-refresh card-state log line: arrival/remaining change every second, so
    // without a dedupe the diagnostics log would gain ~1 line per second during navigation.
    private var lastClusterCardsLog: String? = null
    private var lastClusterCardsLogElapsed = 0L
    private val clusterTurnOverlayListener: (com.shilapi.xcertplay.hud.ClusterTurnGuidance?) -> Unit = { guidance ->
        runOnUiThread {
            clusterTurnGuidance = guidance
            applyClusterTurnOverlay()
        }
    }
    /** Song now playing (title/artist from iAP2 NowPlayingUpdate), shown top centre. */
    private var clusterSong: com.shilapi.xcertplay.hud.ClusterSong? = null
    private val clusterSongListener: (com.shilapi.xcertplay.hud.ClusterSong?) -> Unit = { song ->
        runOnUiThread {
            clusterSong = song
            applyClusterTurnOverlay()
        }
    }
    // Experimental route-guidance TLV trace (0x5201/0x5202/0x5204). The full iAP2 layout is
    // undocumented; the raw TLV trace goes to the diagnostics log so a real capture can be
    // decoded later. Throttled to avoid flooding the log when the iPhone pushes frames often.
    private var lastRouteTrace: String? = null
    private var lastRouteTraceElapsed = 0L
    private val routeTraceListener: (String) -> Unit = { trace ->
        runOnUiThread {
            val now = SystemClock.elapsedRealtime()
            if (trace != lastRouteTrace || now - lastRouteTraceElapsed > 5_000L) {
                lastRouteTrace = trace
                lastRouteTraceElapsed = now
                appendLog("Route trace: $trace")
            }
        }
    }
    // Copies of stream 111 outside the dashboard (centre card, launcher maps) each get their own decoder.
    private val mirrorSink: (String, Surface?) -> Unit = { key, surface -> sink?.setMirrorSurface(SCREEN_TYPE_ALT, key, surface) }
    private val mirrorsChanged: () -> Unit = {
        updateClusterMapShown()
        if (MapMirrors.launcherShowsMap) CenterMapOverlay.hide()
    }
    // With Usage Access the card shows only over a home screen; null = not known (no monitor).
    private var homeMonitor: HomeScreenMonitor? = null
    private var homeScreenVisible: Boolean? = null
    private val hideIdleCenterMap = Runnable {
        if (SCREEN_TYPE_ALT !in activeScreenStreamTypes) CenterMapOverlay.hide()
    }
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var sessionDisplay: CarPlaySessionDisplay? = null
    private var touchOutsideContent = false
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var displayDiagnosticAttempt: String? = null
    private var hevcEnabled = true
    private var hevcSoftwareDecoderEnabled = false
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    private var navigationStreamType = 14
    private var debugLogsEnabled = false
    private var autoStartOnBoot = false
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var oemLabel = AirPlayPersistence.DEFAULT_OEM_LABEL
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var hideTopBar = true
    private var hideBottomBar = true
    private var safeAreaDrawOutside = true
    // The dock the running session declared in /info, and the view areas it declared with it.
    // A fixed dock declares one whole-screen area per edge, so the settings page can move the
    // dock between the edges live (see applyCarPlayDock); AUTOMATIC declares a single area and
    // applies on the next connection.
    private var sessionDock: CarPlayDock? = null
    private var sessionViewAreas: List<AirPlayViewArea>? = null
    private var sessionViewAreaIndex = 0
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    private var wirelessEnabled = false
    private var mfiTarget = MfiTarget.USB_CH341
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var manualHotspotSsid = ""
    private var existingWifiSsid = ""
    private var existingWifiPassphrase = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var awaitingVpnConsent = false
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var vpnReady = false
    private var hotspotStatus = HotspotStatus(state = "off")
    private var menuOpen = false
    private var latestStage = "Preparing CarPlay"
    private var darkMode = false
    private var paintWaitingScreen: () -> Unit = {}
    private var carPlayNightMode = CarPlayNightMode.SYSTEM
    private var nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
    private val nightModeController by lazy {
        CarPlayNightModeController(initialNight = darkMode) { night ->
            darkMode = night
            paintWaitingScreen()
            applyClusterTurnOverlay()
            appendLog("CarPlay switched to ${if (night) "night" else "day"} mode")
            logThemeState(nightModeDiagnosticSource, resources.configuration)
            syncAirPlayDarkMode(nightModeDiagnosticSource)
        }
    }
    private val themeDiagnostics = ThemeModeDiagnostics()
    private var pictureBinding: CarPlayPicture.Binding? = null
    private var picturePanel: View? = null
    private var picturePanelGeneration = 0
    private var clusterCropPanel: View? = null
    private var clusterCropPanelGeneration = 0
    private var lastConfiguration: Configuration? = null
    private var activeAirPlaySession: AirPlaySession? = null
    private val activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var startAfterHandshakeReset = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private var sessionLog: SessionLogFile? = null
    private var settingsGestureHint: TextView? = null
    private var gestureSequenceActive = false
    private var gestureTracking = false
    // Three-finger swipe is a forward/backward position machine over the dashboard modes,
    // like CarPlay boxes: -1 = initial (setting default), 0 = navigation stream, 1 = whole
    // interface mirror. Left swipes advance, right swipes go back, past the ends nothing happens.
    private var clusterSwipePosition = -1
    /** True when [clusterSwipePosition] was set by the navigation auto-switch, not by the driver. */
    private var clusterPositionFromAuto = false
    /**
     * The head unit took the panel back (steering-wheel "close projection") while the channel
     * itself stayed usable. Our window must stay hidden and the watcher must stop re-activating
     * until the driver asks again with a swipe or a new connection.
     */
    private var userHandedBackProjection = false
    /** Last projecting state incall reported, to tell a hand-back from our own activation ramp. */
    private var lastKnownProjecting = false
    private var initialClusterMirror: Boolean? = null
    /** Last navigation state seen by the auto-switch setting; null = re-evaluate on next tick. */
    private var lastAutoNavState: Boolean? = null
    /** Last route state reported by iAP2, waiting for the confirmation window. */
    private var pendingNavigationState: Boolean? = null
    /** A dashboard mode is waiting for the projection channel/display to become available. */
    private var autoNavModePending = false
    /** The driver chose a dashboard mode by hand; auto-switch waits for the next nav transition. */
    private var manualClusterOverride = false
    // True once the UNI-V activation sequence has been sent for this session. The head-unit
    // exposes a projection display even BEFORE activation (verified on the user's UNI-V: the
    // cluster Presentation is created on it, but the physical dashboard shows nothing). So the
    // first left swipe MUST run the activation sequence unconditionally; only afterwards can
    // swipes hot-switch between mirror/navigation on the activated window.
    private var clusterActivationAttempted = false
    // True while the head unit's ext-screen has been handed back to the OEM cluster UI (the
    // driver swiped right past whole-screen mirror). The window/session stay alive but incall is
    // no longer projecting, so the next open must re-send the full open sequence instead of the
    // quiet hot-switch, which would only make our (hidden) window visible on a display the
    // head-unit is not showing.
    private var clusterExtScreenReleased = false
    /** The start-up channel watcher; the only component allowed to send the activation sequence. */
    private var watcherThread: Thread? = null
    /** Last channel state the watcher sampled. Every swipe decision reads this, not incall. */
    @Volatile private var watcherChannelOpen = false
    /** The dashboard display the watcher sampled, reused for every window build. */
    @Volatile private var watcherDisplay: android.view.Display? = null
    @Volatile private var watcherWidth = 0
    @Volatile private var watcherHeight = 0
    private var lastClusterSizeWaitLogMs = 0L
    // True once this activity has sent the projection open sequence at all. The disconnect
    // hand-back refuses to run without it: sending the ext-screen release (r0) to a head unit that
    // never projected would switch it out of a mode it is not in.
    private var clusterProjectionStarted = false
    // True while the dashboard picture is hidden after a disconnect WITHOUT handing the ext-screen
    // back. incall is still projecting and our window is still bound, so the returning stream needs
    // no activation at all - only setMapVisible(true). Releasing the ext-screen instead forces the
    // full open sequence on reconnect, and that is what makes the head unit announce "全屏导航/
    // 镜像已开启" (driver request 2026-10-18: a returning phone must stay silent).
    private var clusterWindowHidden = false
    /** Set once per process: the projection channel is warmed up at start, before any phone is here. */
    private var clusterPrewarmSent = false
    /** When that prewarm sequence went out, so a connect right after it re-asserts silently. */
    private var clusterPrewarmAtMs = 0L
    /** True when the activation ran but the channel never became ready inside the grace window. */
    private var clusterProjectionAbandoned = false
    /**
     * How long the dashboard picture may stay frozen before it is hidden.
     *
     * Hiding is all a disconnect does now: the window goes invisible (taking the frozen frame with
     * it) while the projection session stays exactly as it was, so a returning phone shows the
     * window again - silently. Releasing the ext-screen used to restore the car's own dials, but it
     * forced a full re-activation later, i.e. the announcement the driver reported as a defect.
     *
     * The delay keeps a transient drop - which reconnects in seconds - from blanking the panel.
     */
    private val disconnectHideDelayMs = 5_000L
    /**
     * Last resort, only for a phone that never comes back: hiding alone leaves the cluster BLACK,
     * because incall keeps projecting our (now invisible) window and never falls back to the car's
     * own dials by itself. After two minutes of nothing the panel is handed back for real, which
     * costs one announcement - by then there is nobody left to hear it. Honours the existing
     * 关闭时归还仪表盘 switch, so a driver who prefers to keep the session can switch it off.
     */
    private val disconnectReleaseFallbackMs = 120_000L
    private val hideDashboardAfterDisconnect = Runnable { hideDashboardForDisconnect() }
    private val releaseDashboardAfterLongLoss = Runnable { releaseDashboardForDisconnect() }

    private var gestureStartX = 0f
    private var gestureStartY = 0f
    // Press→release trajectory: the action is decided on finger-up from the whole swipe,
    // so a mid-move axis lock is unnecessary and a down-swipe can never fire the
    // horizontal dashboard switch. The finger count at press decides which gesture applies.
    private var gestureStartFingers = 0
    private var gestureEndX = 0f
    private var gestureEndY = 0f
    // Swipe-down-to-open-settings finger count from settings (3/4/5, persistence default 3).
    private var settingsGestureFingers = 3
    // Permission/VPN-consent dialogs requested during onCreate are deferred to onResume:
    // ActivityResultLauncher.launch() before the activity reaches STARTED throws
    // IllegalStateException, which crashed the wired (USB) startup path on this ROM.
    private var pendingVpnConsent: Intent? = null
    private var pendingMicrophonePermission = false
    private var pendingLocationPermission = false
    // USB auto-switch debounce: this head unit re-broadcasts USB_DEVICE_ATTACHED while the
    // cable stays plugged in. Without a quiet window a manual wireless choice is instantly
    // overridden back to USB; with one, a repeat broadcast within 60s is ignored.
    private var lastUsbAutoSwitchElapsed = 0L
    // Failure/waiting toasts are throttled so a retrying session does not spam the screen.
    private var lastFailureToastElapsed = 0L
    private var lastUsbWaitToastElapsed = 0L
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<LogEntry>()
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    private val refreshTurnOverlay = object : Runnable {
        override fun run() {
            // The periodic refresh must never be able to stop itself: an exception thrown here used
            // to skip the postDelayed below, which killed the loop permanently and silently.
            try {
                com.shilapi.xcertplay.hud.BydNavigationOutputs.refreshTurnOverlay()
            } catch (error: Throwable) {
                appendLog("Overlay refresh failed: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }

    // Freeze detection.
    //
    // iOS can stop repainting CarPlay after a navigation session ends while the video stream keeps
    // delivering frames at full rate: the picture sits on one frame, touches still go out, and the
    // only visible symptom is a collapse in bitrate (field 2026-10-22: main screen 9066 -> 1012
    // kbps, cluster 4673 -> 91 kbps, both still "23.0fps"). Nothing in the decoder or the network
    // reports a problem, so the bitrate is what this watches.
    //
    // A normal static navigation view also has a low bitrate, and the field log for 2026-10-23
    // shows that false positive rebuilding the session three times on a 10 km drive. The watchdog
    // now only asks for repaints; it never restarts CarPlay.
    private var freezeStreak = 0
    private var freezeStrikes = 0
    private var lastFreezeActionMs = 0L
    private var lastNavigatingState: Boolean? = null

    // showUI/forceKeyFrame are RTSP writes. They are cheap, but they take the session's event write
    // lock and do socket I/O, so they must never run on the thread that draws CarPlay.
    private val repaintExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cluster-repaint").apply { isDaemon = true }
    }

    private fun requestClusterRepaint(reason: String) {
        // The executor is shut down in onDestroy while the one-second overlay loop can still fire
        // one last time; execute() on a shut-down pool throws RejectedExecutionException, which
        // used to take the process down on the first disconnect (the second one was fine because
        // the loop had already stopped). Never let a background repaint crash the app.
        runCatching {
            repaintExecutor.execute {
                val sent = runCatching { controller?.refreshClusterUi(reason) }.getOrDefault(false)
                appendLog("Cluster repaint ($reason) sent=$sent")
            }
        }.onFailure { appendLog("Cluster repaint skipped (${it.javaClass.simpleName})") }
    }

    private val checkVideoFreeze = object : Runnable {
        override fun run() {
            try {
                checkVideoFreezeNow()
            } catch (error: Throwable) {
                appendLog("Freeze check failed: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                mainHandler.postDelayed(this, FREEZE_CHECK_PERIOD_MS)
            }
        }
    }

    private fun checkVideoFreezeNow() {
        val mediaSink = sink ?: return
        if (activeAirPlaySession == null || shuttingDown.get() || isFinishing || isDestroyed) {
            freezeStreak = 0
            return
        }
        // Judge the stream the dashboard is actually showing: nav-only mode watches 111, the
        // whole-screen mirror watches 110.
        val watchingAlt = clusterPresentation?.isMirrorMode() != true
        val streamType = if (watchingAlt) 111 else 110
        val kbps = mediaSink.videoKbps(streamType)
        val ageMs = mediaSink.videoWindowAgeMs(streamType)
        // No recent window at all means the stream stopped, which is a different problem (and the
        // existing recovery paths own it) - not a freeze.
        if (kbps < 0 || ageMs > FREEZE_STALE_MS) {
            freezeStreak = 0
            return
        }
        // A call - car HFP or CarPlay telephony - can leave the navigation picture on one static
        // frame for its whole duration. Never let the freeze watchdog restart CarPlay under a call;
        // it resumes when the head unit returns to MODE_NORMAL.
        if (mediaSink.callActive()) {
            freezeStreak = 0
            return
        }
        // Siri / voice key: while the assistant is listening, and for a short window after it
        // closes, the navigation stream can legitimately stay on one static picture. Treating that
        // as a frozen session used to burn through the strike budget during a few Siri requests.
        if (mediaSink.microphoneActive(10_000L)) {
            freezeStreak = 0
            return
        }
        // Full-rate repainting proves the session is alive. Clear the strike budget too: it exists
        // to bound retries for one continuous freeze, not to accumulate static Siri screens across
        // a whole drive until an unrelated third one restarts CarPlay.
        if (kbps >= FREEZE_ACTIVE_KBPS) {
            freezeStreak = 0
            freezeStrikes = 0
            return
        }
        // Low but not frozen: the picture still changes, so this is not part of a freeze streak.
        if (kbps > FREEZE_KBPS) {
            freezeStreak = 0
            return
        }
        freezeStreak++
        if (freezeStreak < FREEZE_WINDOWS) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFreezeActionMs < FREEZE_COOLDOWN_MS) return
        lastFreezeActionMs = now
        freezeStrikes++
        // One strike is one 15 s episode. Without this reset the streak stayed above the threshold
        // through the cooldown, so every cooldown produced another strike even when the picture had
        // recovered in between.
        val streak = freezeStreak
        freezeStreak = 0
        appendLog(
            "Video freeze: stream=$streamType kbps=$kbps age=${ageMs}ms 连续 $streak 个窗口" +
                "（第 $freezeStrikes 次，${if (watchingAlt) "导航画面" else "镜像画面"}）",
        )
        if (freezeStrikes <= FREEZE_REFRESH_ATTEMPTS) {
            requestClusterRepaint("frozen stream=$streamType kbps=$kbps")
        } else {
            // Repaints did not raise the bitrate. Do NOT rebuild the session: a static navigation
            // picture at low bitrate looks identical to a frozen one, and the field log for
            // 2026-10-23 shows three self-inflicted reconnects on a 10 km drive where the main
            // stream was still healthy at 2.8-9 Mbps. Leave the session alone; a real freeze is
            // recovered by the driver's manual disconnect/reconnect.
            appendLog("Video freeze: 重绘无效，保留会话（未自动重连，可手动断开重连）")
            freezeStrikes = 0
        }
    }
    // Some head units (e.g. BYD DiLink) update resources.configuration for day/night
    // without delivering onConfigurationChanged, so poll while the activity is visible.
    private val pollConfiguration = object : Runnable {
        override fun run() {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.POLL)
            mainHandler.postDelayed(this, CONFIGURATION_POLL_INTERVAL_MILLIS)
        }
    }
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = currentSurface
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    existing?.release()
                    currentSurface = it
                    currentSurfaceTexture = texture
                }
            }
            appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
            attachSurface(surface)
            updateVideoLayout(width, height)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            updateVideoLayout(width, height)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) return true
            currentSurface?.let { surface ->
                sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
                sink?.clearSurface(SCREEN_TYPE_ALT, surface)
                surface.release()
            }
            currentSurface = null
            currentSurfaceTexture = null
            appendLog("Texture surface destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavigationWidgetUpdater.attach(applicationContext)
        CenterMapOverlay.requestShow = ::showCenterMap
        MapMirrors.sink = mirrorSink
        MapMirrors.onChanged = mirrorsChanged
        languagePreferenceAtCreate = AppLocale.preference(this)
        if (intent.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            AirPlayPersistence.saveWirelessEnabled(this, false)
        }
        if (runCatching { DiPlayBootstrap.ensure(this) }.isFailure) {
            startActivity(Intent(this, DiPlayActivity::class.java))
            finish(); return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initializeSessionLog()
        lastConfiguration = Configuration(resources.configuration)
        darkMode = nightModeOrNull(resources.configuration.uiMode) ?: false
        logThemeState(ThemeModeDiagnostics.Source.CREATE, resources.configuration)
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        configureNightMode()
        locationPermissionAvailable = hasFineLocationPermission()
        setContentView(buildContentView())
        // Panel requests (picture controls / dashboard crop / card position) arrive before the
        // view hierarchy exists on a cold start, so open them after setContentView, not before.
        if (intent.getBooleanExtra("picture_controls", false)) {
            intent.removeExtra("picture_controls")
            openPicturePanel()
        }
        if (intent.getBooleanExtra("cluster_crop_controls", false)) {
            intent.removeExtra("cluster_crop_controls")
            appendLog("Cluster crop: request received")
            openClusterCropPanel()
        }
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (menuOpen) {
                        if (safeAreaEditorActive) closeSafeAreaEditor() else cancelSettingsEdits()
                    } else {
                        showDiPlayHome()
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        // Every saved setting goes to the log at startup, so a report never needs the driver to
        // describe what they had configured (2026-10-22: the music buffer was assumed to be
        // 1000 ms while the 700 ms track in the log was really the 500 ms preset plus headroom).
        runCatching {
            AirPlayPersistence.describeSavedSettings(this).lineSequence()
                .filter { it.isNotBlank() }
                .forEach { appendLog(it) }
        }
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        appendLog(
            "Startup: adoptedSession=$reusedBackgroundSession microphone=$microphonePermissionResolved " +
                "wireless=$wirelessEnabled",
        )
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            pendingMicrophonePermission = true
        }
    }

    private fun loadPersistedSettings() {
        displayScaleTenths = AirPlayPersistence.loadDisplayScaleTenths(this)
        // Size is now chosen only through CarPlaySize; ignore the canvas scale older builds stored.
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        navigationStreamType = AirPlayPersistence.loadNavigationStreamType(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        settingsGestureFingers = AirPlayPersistence.loadSettingsGestureFingers(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        locationPermissionAvailable = hasFineLocationPermission()
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(this)
        existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        carPlayNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
    }

    private fun configureNightMode() {
        nightModeController.configure(
            carPlayNightMode,
            nightModeOrNull(resources.configuration.uiMode) ?: false,
        )
    }

    private fun openPicturePanel() {
        closePicturePanel()
        val generation = picturePanelGeneration
        val root = videoView?.parent as? FrameLayout ?: return
        root.post {
            if (generation != picturePanelGeneration || isFinishing || isDestroyed) return@post
            val panel = CarPlayPicturePanel(this, ::closePicturePanel)
            val availableWidth = (root.width - dp(24)).coerceAtLeast(1)
            val width = minOf(dp(420), if (root.width < dp(800)) availableWidth else (root.width * 0.42f).toInt())
            val height = minOf(dp(540), root.height - dp(24)).coerceAtLeast(1)
            root.addView(panel, FrameLayout.LayoutParams(width, height, Gravity.END or Gravity.TOP).apply {
                topMargin = dp(12); marginEnd = dp(12)
            })
            picturePanel = panel
        }
    }

    private fun closePicturePanel() {
        ++picturePanelGeneration
        picturePanel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        picturePanel = null
        CarPlayPicture.showOriginal(false)
    }

    private fun openClusterCropPanel() {
        // Remove any existing panel first, but do NOT rebuild the cluster window here:
        // dismiss/ensure would tear down and recreate the decoder surface (mirror stream),
        // which breaks the CarPlay session while the user is only opening the sliders.
        runCatching {
            clusterCropPanel?.let { (it.parent as? ViewGroup)?.removeView(it) }
            clusterCropPanel = null
            val generation = clusterCropPanelGeneration
            val root = videoView?.parent as? FrameLayout
            appendLog(
                "Cluster crop panel: videoParent=${videoView?.parent?.javaClass?.simpleName ?: "none"} " +
                    "root=${root != null}",
            )
            if (root == null) {
                appendLog("Cluster crop panel: no root to attach to")
                Toast.makeText(
                    this,
                    "仪表盘画面调整面板打开失败（无画面层，详见诊断日志）",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            root.post {
                runCatching {
                    if (generation != clusterCropPanelGeneration || isFinishing || isDestroyed) {
                        appendLog("Cluster crop panel: skip (generation changed)")
                        return@runCatching
                    }
                    val panel = ClusterCropPanel(
                        this,
                        ::closeClusterCropPanel,
                        onAdjust = {
                            runCatching {
                                val p = clusterPresentation
                                if (p != null) {
                                    appendLog(
                                        "Cluster crop: adjust -> refreshCrop (mirror=" +
                                            (clusterPresentation?.isMirrorMode() ?: AirPlayPersistence.loadClusterMirrorMain(this)) + ")",
                                    )
                                    p.refreshCrop()
                                } else {
                                    appendLog("Cluster crop: adjust skipped (no presentation)")
                                }
                            }.onFailure { e ->
                                appendLog("Cluster crop: refreshCrop failed ${e.message ?: e.javaClass.simpleName}")
                            }
                        },
                    )
                    // Fixed-size panel: root.width may still be 0 when the panel is opened
                    // right after cold start, which previously collapsed the panel to 1px.
                    val width = dp(360)
                    val height = minOf(
                        dp(520),
                        (resources.displayMetrics.heightPixels - dp(24)).coerceAtLeast(dp(200)),
                    )
                    root.addView(panel, FrameLayout.LayoutParams(width, height, Gravity.START or Gravity.TOP).apply {
                        topMargin = dp(12); marginStart = dp(12)
                    })
                    clusterCropPanel = panel
                    appendLog("Cluster crop panel: shown ${width}x${height}px")
                }.onFailure { e ->
                    appendLog("Cluster crop panel: open failed ${e.message ?: e.javaClass.simpleName}")
                    Toast.makeText(
                        this,
                        "仪表盘画面调整面板打开失败，详见诊断日志",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun closeClusterCropPanel() {
        ++clusterCropPanelGeneration
        clusterCropPanel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        clusterCropPanel = null
        // Apply the saved crop without destroying the window: re-transform the existing
        // TextureView in place. Only rebuild if the cluster window is gone entirely.
        if (!shuttingDown.get()) {
            val presentation = clusterPresentation
            if (presentation != null) {
                runCatching { presentation.refreshCrop() }
            } else {
                runCatching { ensureClusterPresentation() }
            }
        }
    }

    private fun requestStartupPrerequisites() {
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            requestVpnConsent()
        }
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        appendLog("Startup: requesting location permission")
        pendingLocationPermission = true
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestVpnConsent() {
        val consent = runCatching { CarPlayVpnService.prepare(this) }
            .getOrElse { error ->
                appendLog("Startup: VPN prepare failed ${error.javaClass.simpleName}: ${error.message}")
                null
            }
        if (consent == null) {
            appendLog("Startup: VPN consent already granted, starting wired path")
            vpnReady = true
            maybeStartCarPlay()
        } else if (consent.resolveActivity(packageManager) == null) {
            // This head-unit ROM has no AOSP VPN consent dialog (com.android.vpndialogs is
            // absent — launch throws ActivityNotFoundException). There is nothing to show, so
            // attempt the wired path directly: a slimmed ROM that drops the dialog usually
            // does not enforce consent either. Any rejection surfaces as AttachResult.Failed.
            appendLog("Startup: VPN consent dialog unavailable on this ROM, attempting wired path directly")
            vpnReady = true
            maybeStartCarPlay()
        } else {
            appendLog("Startup: requesting VPN consent")
            awaitingVpnConsent = true
            pendingVpnConsent = consent
            // Launch as soon as the request is registered. This covers the REORDER_TO_FRONT
            // path where onResume already ran (pending would otherwise sit until the next
            // resume); the deferred launch is safe because post() executes after onResume.
            maybeLaunchPendingStartupRequests()
        }
    }

    /**
     * Launches any permission / VPN-consent dialog that a startup path requested before the
     * activity reached a resumed state. ActivityResultLauncher.launch() while the activity is
     * still in CREATED throws IllegalStateException on this head-unit ROM; this crashed the
     * wired (USB) startup path, whose VPN consent had never been granted. onResume guarantees
     * the lifecycle is STARTED/RESUMED, so all deferred launches happen there.
     */
    private fun maybeLaunchPendingStartupRequests() {
        if (pendingVpnConsent != null) {
            val consent = pendingVpnConsent!!
            pendingVpnConsent = null
            // Post to the next loop: on this head-unit ROM the launcher can still reject a
            // launch issued directly inside onResume, crashing the wired path. Deferring keeps
            // the activity fully resumed; any residual failure is logged, not fatal.
            mainHandler.post {
                runCatching { vpnConsent.launch(consent) }
                    .onFailure { error ->
                        appendLog("Startup: VPN consent launch failed ${error.javaClass.simpleName}: ${error.message}")
                        // The dialog component exists per resolveActivity but its class is missing on
                        // this ROM (launch throws ActivityNotFoundException). A ROM that drops the
                        // dialog usually does not enforce consent either, so attempt the wired path
                        // directly — same fallback as the resolveActivity==null branch. Any rejection
                        // surfaces as AttachResult.Failed, never a crash.
                        awaitingVpnConsent = false
                        vpnReady = true
                        runCatching { maybeStartCarPlay() }
                            .onFailure { startError ->
                                appendLog("Startup: wired fallback start failed ${startError.javaClass.simpleName}: ${startError.message}")
                            }
                        setStatus(getString(R.string.vpn_consent_launch_failed))
                    }
            }
        } else if (pendingMicrophonePermission) {
            pendingMicrophonePermission = false
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        } else if (pendingLocationPermission) {
            pendingLocationPermission = false
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> = when {
        wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Host reused via FLAG_ACTIVITY_REORDER_TO_FRONT: the "picture controls" / dashboard
        // crop / card-position requests from DiPlayActivity arrive through onNewIntent, not
        // onCreate. Without these branches the panels never open and the user lands straight
        // in CarPlay.
        if (intent.getBooleanExtra("picture_controls", false)) {
            intent.removeExtra("picture_controls")
            openPicturePanel()
        }
        if (intent.getBooleanExtra("cluster_crop_controls", false)) {
            intent.removeExtra("cluster_crop_controls")
            appendLog("Cluster crop: request received")
            openClusterCropPanel()
        }
        if (intent.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED" && wirelessEnabled) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastUsbAutoSwitchElapsed < 60_000L) {
                // Duplicate attach broadcast while the cable stays plugged in. Ignoring it
                // keeps a manual wireless choice from being overridden right back to USB.
                appendLog("USB attach: ignored within auto-switch debounce window")
                return
            }
            lastUsbAutoSwitchElapsed = now
            appendLog("USB attach: switching from wireless to USB")
            Toast.makeText(this, R.string.usb_detected_switching, Toast.LENGTH_SHORT).show()
            shutdown(false, "switching to USB") {
                AirPlayPersistence.saveWirelessEnabled(this, false)
                startActivity(Intent(this, CarPlayHostActivity::class.java))
            }
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        logThemeState(ThemeModeDiagnostics.Source.START, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        mainHandler.removeCallbacks(airPlayStallHint)
        mainHandler.post(pollConfiguration)
        CenterMapOverlay.onDiPlayScreenShown()
        homeMonitor?.stop()
        homeScreenVisible = null
    }

    override fun onResume() {
        super.onResume()
        // Back from the settings page: a stop requested while it was open was ignored, so the
        // session has to become stoppable again.
        CarPlayBackgroundSession.releaseSettingsHold()
        CarPlayBackgroundSession.logger = { message -> appendLog(message) }
        // incall telling us the panel is really ours is the signal to show the dashboard window.
        // The opposite direction matters too: the wheel's close-projection hands the panel back
        // without closing the channel, and re-activating there is what made it impossible to close.
        InCallClusterBridge.projectingListener = { projecting ->
            if (projecting) runOnUiThread { onClusterProjectionConfirmed() }
            else runOnUiThread { onClusterProjectionWithdrawn() }
        }
        val languagePreference = AppLocale.preference(this)
        if (Build.VERSION.SDK_INT < 33 && languagePreference != languagePreferenceAtCreate) {
            languagePreferenceAtCreate = languagePreference
            recreate()
            return
        }
        // Re-read the night-mode choice on every resume: it is changed on the settings page (another
        // Activity), and configure() only ran in onCreate, so a new choice used to need a process
        // restart before CarPlay reacted to it.
        carPlayNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        configureNightMode()
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable && !menuOpen) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        maybeLaunchPendingStartupRequests()
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        // Refresh the persisted gesture-finger preference on every foreground return so a change
        // made in the DiPlay settings page takes effect immediately, without waiting for a reconnect.
        settingsGestureFingers = AirPlayPersistence.loadSettingsGestureFingers(this)
        if (DiLink51ClusterLayout.automatic(this) && clusterMonitor == null) {
            clusterMonitor = DiLink51ClusterMonitor(this, ::onClusterActivityState).also { it.start() }
        } else if (!DiLink51ClusterLayout.automatic(this)) {
            clusterMonitor?.stop()
            clusterMonitor = null
        }
        settingsGestureHint?.text = getString(R.string.open_diplay_settings_hint)
        // The ONLY place that may ever send the activation sequence: a start-up watcher that
        // samples the projection channel and sends it once when the channel is not open yet, and
        // records the dashboard display for every later window build. Everything after this point
        // only shows or hides the window - re-sending the sequence anywhere else is what made the
        // head unit announce "全屏镜像/导航已开启" on swipes and reconnects.
        startClusterChannelWatcher()
        mainHandler.removeCallbacks(checkVideoFreeze)
        mainHandler.postDelayed(checkVideoFreeze, FREEZE_CHECK_PERIOD_MS)
        ensureClusterPresentation()
        // Watch for the dashboard display arriving late. Cold boot: the driver can open the app
        // before the instrument cluster has come up, findDisplay() then returns nothing, the
        // readiness gate times out at 25 s, and autoProjectTriggered is already set - so the
        // projection was never attempted and never retried, and the dashboard stayed empty.
        if (!clusterDisplayListenerRegistered) {
            runCatching {
                getSystemService(android.hardware.display.DisplayManager::class.java)
                    ?.registerDisplayListener(clusterDisplayListener, mainHandler)
                clusterDisplayListenerRegistered = true
            }
        }
        // Any settings save (card dx/dy, content-top, left-rail crop, dashboard rect…) must
        // redraw the live dashboard immediately: refresh the card overlays AND re-apply the
        // TextureView crop transform. The crop must NOT reconnect (re-activating the 111 nav
        // stream would make the head unit announce "全屏导航已开启", which the driver rejected).
        AirPlayPersistence.overlaySettingsListener = {
            runOnUiThread {
                // Turning the navigation auto-switch off must hand the three-finger position
                // machine back immediately, not on the next one-second tick: if auto left it at
                // 1, a left swipe is the end of the machine and looks like "three-finger does
                // nothing". A position the driver set manually is left alone.
                if (!AirPlayPersistence.loadClusterAutoNavMode(this) && clusterPositionFromAuto) {
                    clusterSwipePosition = if (clusterSwipePosition == 1) -1 else 0
                    clusterPositionFromAuto = false
                }
                applyClusterTurnOverlay()
                // Every nav card re-reads its persisted (x, y) right away, so position
                // changes are visible on the dashboard immediately after saving.
                (clusterLayers.values + listOfNotNull(clusterPresentation)).distinct()
                    .forEach { runCatching { it.refreshCardOverlays() } }
                // The song card caches its own limits (max characters, artwork size/offset, card
                // alpha), so it needs an explicit nudge to pick a change up.
                (clusterLayers.values + listOfNotNull(clusterPresentation)).distinct()
                    .forEach { runCatching { it.notifySongSettingsChanged() } }
                refreshClusterCrop()
            }
        }
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setTurnOverlayListener(clusterTurnOverlayListener)
        // Route state drives "导航时自动切换仪表视图": the transition itself, no polling.
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setNavigationStateListener { navigating ->
            mainHandler.post { onNavigationStateEvent(navigating) }
        }
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setSongListener(clusterSongListener)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.routeTraceListener = routeTraceListener
        mainHandler.removeCallbacks(refreshTurnOverlay)
        mainHandler.post(refreshTurnOverlay)
        maybeStartCarPlay()
        // Fallback sweep: a startup request registered while onResume was already running
        // (e.g. switching to USB from the connection page) is launched here so a wired
        // session never waits for the next resume to show its VPN consent dialog.
        maybeLaunchPendingStartupRequests()
        applyFullscreenMode()
    }

    override fun onPause() {
        super.onPause()
        closePicturePanel()
    }

    // Experimental: the CarPlay instrument-cluster stream on the BYD cluster projection display.
    private fun effectiveClusterTheme(): DiLink51ClusterLayout.Theme =
        if (DiLink51ClusterLayout.automatic(this)) detectedCluster.theme ?: DiLink51ClusterLayout.theme(this)
        else DiLink51ClusterLayout.theme(this)

    private fun onClusterActivityState(state: ClusterActivityState.Snapshot) {
        if (state != detectedCluster) appendLog("Cluster map: detected theme=${state.theme} mapVisible=${state.mapVisible}")
        detectedCluster = state
        ensureClusterPresentation()
    }

    private val clusterDisplayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) =
            onClusterDisplayArrived("display $displayId added")

        // A removed display only means the window's host is gone; the activation path rebuilds it.
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
    }
    private var clusterDisplayListenerRegistered = false

    /**
     * Brings the dashboard up on a display that arrived after us.
     *
     * Nothing used to happen here, because auto projection fires once per session
     * (autoProjectTriggered) and its readiness gate polls for a bounded 25 s. On a cold boot where
     * the cluster takes longer than that, the whole chain had already run and given up, so the
     * driver got no projection at all - the reported symptom. Creating the window on arrival and
     * running the projection recovers it without a reconnect.
     *
     * Re-activating cannot re-announce: InCallClusterBridge.openProjection() reads incall's own
     * state first and takes its silent path when the head unit already reports projecting, which is
     * exactly the case when only the window was missing.
     */
    private fun onClusterDisplayArrived(reason: String) {
        if (shuttingDown.get() || handshakeResetInProgress || isFinishing || isDestroyed) return
        if (clusterPresentation != null) return
        if (!AirPlayPersistence.loadAutoFlyScreen(this)) return
        if (activeAirPlaySession == null) return
        appendLog("Cluster map: $reason; 仪表屏就绪，补建投屏窗口（不发激活序列）")
        runCatching { ensureClusterPresentation() }
        if (clusterPresentation == null) return
        val mirror = currentClusterMirrorMode()
        // Show only when the channel is actually open. The start-up watcher owns activation, so
        // this path must never send the sequence - a display arriving late is not a reason to
        // announce again.
        if (!watcherChannelOpen && !InCallClusterBridge.reportsProjecting()) {
            clusterPresentation?.setMapVisible(false)
            appendLog("Cluster map: 投屏通道未开，窗口保持隐藏")
            return
        }
        clusterPresentation?.setMapVisible(true)
        applyClusterMode(mirror, "auto")
    }

    private fun ensureClusterPresentation() {
        val theme = effectiveClusterTheme()
        if (DiLink51ClusterLayout.supported()) {
            ensureDiLink51ClusterPresentation(theme)
            return
        }
        if (clusterPresentation != null) return
        // Prefer the display the start-up watcher sampled: it was taken while incall was idle, so a
        // window build does not have to hope findDisplay() succeeds during a reconfiguration.
        val display = watcherDisplay ?: ClusterMapPresentation.findDisplay(this, theme) ?: run {
            appendLog("Cluster map: no cluster projection display among ${ClusterMapPresentation.describeDisplays(this)}")
            return
        }
        val presentation = ClusterMapPresentation(
            this,
            display,
            theme,
            { surface -> runOnUiThread { onClusterMirrorSurface(surface) } },
            { surface -> runOnUiThread { onClusterNavSurface(surface) } },
            { surface -> runOnUiThread { onClusterCardSurface(surface) } },
            navOnlyCards = true,
            flyCard = CarPlayClusterDisplay.isFlyCard(AirPlayPersistence.loadClusterContent(this)),
        )
        // The system dismisses a presentation when its display goes away; allow a new one on resume.
        presentation.setOnDismissListener {
            if (clusterPresentation === presentation) {
                clusterPresentation = null
                com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterMapShown(false)
            }
        }
        try {
            presentation.show()
            clusterPresentation = presentation
            // Remember which picture source this window was built for. The URL is fixed at build
            // time, so a later change to that setting can only take effect through a rebuild.
            clusterWindowContent = AirPlayPersistence.loadClusterContent(this)
            // A window built after the auto-switch changed the desired mode must be re-applied on
            // the next tick; this path otherwise uses the saved display choice.
            if (AirPlayPersistence.loadClusterAutoNavMode(this)) autoNavModePending = true
            presentation.setMode(currentClusterMirrorMode())
            updateClusterMapShown()
            presentation.setStreamActive(clusterStreamActive())
            // A window built while the card stream was already up must not wait for the next
            // stream event, which only comes on a transition.
            presentation.setCardStreamActive(activeScreenStreamTypes.contains(SCREEN_TYPE_ALT_CARD))
            // Invisible until the head unit is really in projection mode. Before activation incall
            // maps the ext-screen to a small placeholder box at the right of the panel, and this
            // window is created 4-5 s before the open sequence (measured 2026-10-18 14:18: window
            // 04.245, sequence 10.036) - exactly the driver's "cold start projects only a small
            // block on the right, full size a few seconds later" (2.1). The quiet window above has
            // always been created hidden for the same reason; tryActivateClusterProjection and
            // applyClusterMode show this one again.
            presentation.setMapVisible(false)
            applyClusterTurnOverlay()
            Log.i(ClusterMapPresentation.TAG, "cluster presentation shown display=${display.displayId} name=${display.name}")
            // Geometry is logged because the driver reported "cold start projects only a small
            // block on the right, full size a few seconds later" (2026-10-18 2.1). Our window is
            // MATCH_PARENT on the cluster display, so the numbers decide whether the panel is
            // showing our full-size window at all or a placeholder-sized one.
            val real = runCatching { android.util.DisplayMetrics().also { display.getRealMetrics(it) } }
                .getOrNull()
            appendLog(
                "Cluster map: presentation shown display=${display.displayId} " +
                    "real=${real?.widthPixels}x${real?.heightPixels} " +
                    "density=${real?.densityDpi}",
            )
            // Start watching incall's callback now, long before any activation. The display above
            // is always present (measured within ~300 ms of startup, including sessions where the
            // open sequence never runs at all), so display topology cannot say whether the head
            // unit is already projecting; incall's own callback is the only channel that can.
            InCallClusterBridge.observeCallback(this)
        } catch (error: RuntimeException) {
            Log.w(ClusterMapPresentation.TAG, "cluster presentation failed", error)
            appendLog("Cluster map: presentation failed ${error.javaClass.simpleName}")
        }
    }

    /**
     * Creates the instrument-cluster window without requiring the "dashboard projection"
     * setting, so a three-finger swipe can pull the dashboard up even before the first
     * projection. The window is shown hidden and the already-running main-mirror stream is
     * bound, so once a swipe sets it visible the dashboard has a picture immediately. The
     * DiLink 5.1 layout path has its own monitor and is skipped here.
     */
    private fun ensureClusterWindowQuiet() {
        if (clusterPresentation != null) return
        if (DiLink51ClusterLayout.supported()) return
        val theme = effectiveClusterTheme()
        val display = ClusterMapPresentation.findDisplay(this, theme) ?: run {
            appendLog("Cluster map: no cluster projection display (quiet)")
            return
        }
        val presentation = ClusterMapPresentation(
            this,
            display,
            theme,
            { surface -> runOnUiThread { onClusterMirrorSurface(surface) } },
            { surface -> runOnUiThread { onClusterNavSurface(surface) } },
            { surface -> runOnUiThread { onClusterCardSurface(surface) } },
            navOnlyCards = true,
            flyCard = CarPlayClusterDisplay.isFlyCard(AirPlayPersistence.loadClusterContent(this)),
        )
        presentation.setOnDismissListener {
            if (clusterPresentation === presentation) {
                clusterPresentation = null
                com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterMapShown(false)
            }
        }
        try {
            presentation.show()
            clusterPresentation = presentation
            // Remember which picture source this window was built for. The URL is fixed at build
            // time, so a later change to that setting can only take effect through a rebuild.
            clusterWindowContent = AirPlayPersistence.loadClusterContent(this)
            // A window built after the auto-switch changed the desired mode must be re-applied on
            // the next tick; this path otherwise uses the saved display choice.
            if (AirPlayPersistence.loadClusterAutoNavMode(this)) autoNavModePending = true
            presentation.setMode(currentClusterMirrorMode())
            presentation.setStreamActive(clusterStreamActive())
            presentation.setCardStreamActive(activeScreenStreamTypes.contains(SCREEN_TYPE_ALT_CARD))
            presentation.setMapVisible(false)
            applyClusterTurnOverlay()
            appendLog("Cluster map: quiet window shown display=${display.displayId}")
        } catch (error: RuntimeException) {
            appendLog("Cluster map: quiet window failed ${error.javaClass.simpleName}")
        }
    }

    private fun ensureDiLink51ClusterPresentation(theme: DiLink51ClusterLayout.Theme) {
        val fullMap = theme == DiLink51ClusterLayout.Theme.MAP
        val visible = !DiLink51ClusterLayout.automatic(this) || detectedCluster.mapVisible
        clusterLayers.filterKeys { it != fullMap }.values.forEach { it.setMapVisible(false) }
        var target = clusterLayers[fullMap]
        if (target == null) {
            val display = ClusterMapPresentation.findDisplay(this, theme) ?: return
            lateinit var presentation: ClusterMapPresentation
            presentation = ClusterMapPresentation(
                this,
                display,
                theme,
                { surface -> runOnUiThread { if (clusterPresentation === presentation) onClusterSurface(surface) } },
                { _ -> /* DiLink 5.1 single TextureView: no nav-only surface */ },
            )
            presentation.setOnDismissListener {
                if (clusterLayers[fullMap] === presentation) clusterLayers.remove(fullMap)
                if (clusterPresentation === presentation) clusterPresentation = null
            }
            try {
                presentation.setMapVisible(false)
                clusterPresentation = presentation
                clusterLayers[fullMap] = presentation
                presentation.show()
                appendLog("Cluster map: retained layer display=${display.displayId} fullMap=$fullMap")
                target = presentation
            } catch (error: RuntimeException) {
                clusterLayers.remove(fullMap)
                clusterPresentation = null
                appendLog("Cluster map: presentation failed ${error.javaClass.simpleName}")
                return
            }
        }
        clusterPresentation = target
        target.mirrorOutputSurface?.let(::onClusterSurface)
        target.setStreamActive(clusterStreamActive())
        target.setMapVisible(visible)
        applyClusterTurnOverlay()
    }

    /** Which CarPlay stream feeds the instrument cluster window (main-screen mirror or cluster stream). */
    private fun clusterStreamActive(): Boolean {
        val type = if (currentClusterMirrorMode()) SCREEN_TYPE_MAIN else SCREEN_TYPE_ALT
        return type in activeScreenStreamTypes
    }

    /**
     * The dashboard mode actually in use, not the saved display preference.
     *
     * The navigation auto-switch changes the mode for this run without writing
     * [AirPlayPersistence.KEY_CLUSTER_MIRROR_MAIN], so every runtime decision (crop, stream binding,
     * recovery) must use this instead of reading the preference again.
     */
    private fun currentClusterMirrorMode(): Boolean {
        // Every picture source can reach the whole-screen mirror (field 2026-10-25: FLY_CARD used
        // to be pinned to nav-only here, so the three-finger swipe and the reconnect both looked
        // broken). FLY_CARD simply hides its instruction-card window while mirrored.
        if (clusterSwipePosition >= 0) return clusterSwipePosition == 0
        if (AirPlayPersistence.loadClusterAutoNavMode(this)) {
            lastAutoNavState?.let { return !it }
        }
        return AirPlayPersistence.loadClusterMirrorMain(this)
    }

    private fun applyClusterTurnOverlay() {
        val overlay = CarPlayClusterDisplay.usesCustomTurnCard(AirPlayPersistence.loadClusterContent(this))
        val presentations = (clusterLayers.values + listOfNotNull(clusterPresentation)).distinct()
        // Chang'an generic path: the nav-only card group (turn card + ETA card) is driven purely
        // by the presentation's mode (visible in nav-only, hidden in whole-screen mirror) and is
        // independent of the "dashboard shows" content setting. DiLink 5.1 keeps the upstream
        // behavior: turn card follows the content setting, no ETA card.
        val changanCards = !DiLink51ClusterLayout.supported()
        val g = clusterTurnGuidance
        // Navigation starting or ending is exactly the moment iOS stops repainting the cluster: the
        // alt screen keeps the previous route on screen (field report: "退出导航后重新导航，仪表盘
        // 还是上一次的路线"). Ask for a repaint on every transition so the new route appears.
        val navigating = g != null
        if (lastNavigatingState != navigating) {
            val first = lastNavigatingState == null
            lastNavigatingState = navigating
            // A session that begins mid-route needs the nudge as well. iOS keeps the cluster on its
            // plain map view until something asks it to refresh, so a cold start during active
            // guidance stayed on the map for the whole drive and only "重新导航" brought the
            // junction picture back (field 2026-10-24). The first sample of a session is the only
            // moment that state can be observed, so it must not be skipped when already navigating.
            if (!first || navigating) {
                requestClusterRepaint(if (navigating) "navigation started" else "navigation ended")
            }
        }
        // Dedupe the card-state line: ETA seconds tick every second, so log once per state
        // change (or at least every 5 s) instead of once per refresh tick.
        val cardsLog = "Cluster cards: mode=${if (clusterPresentation?.isMirrorMode() == true) "mirror" else "nav-only"} " +
            // Whether the views EXIST, not just whether data arrived: a card that was never created
            // cannot be what the driver sees on the dashboard.
            "cards=${clusterPresentation?.cardsSummary() ?: "no-window"} " +
            "turn=${g != null} road=${g?.road?.isNotEmpty() == true || g?.currentRoad?.isNotEmpty() == true} " +
            "eta=${g?.arrivalEpochSeconds != null || g?.remainingSeconds != null} " +
            "contentTop=${AirPlayPersistence.loadClusterCardContentTopPx(this)} " +
            "turnXY=${AirPlayPersistence.loadClusterTurnCardX(this)}/" +
            "${AirPlayPersistence.loadClusterTurnCardY(this)} " +
            "exitTxt=${(g?.exitLabel ?: "").take(14)} roadTxt=${(g?.road ?: "").take(14)} curRoad=${(g?.currentRoad ?: "").take(14)}"
        val nowMs = SystemClock.elapsedRealtime()
        if (cardsLog != lastClusterCardsLog || nowMs - lastClusterCardsLogElapsed > 5_000L) {
            lastClusterCardsLog = cardsLog
            lastClusterCardsLogElapsed = nowMs
            appendLog(cardsLog)
        }
        for (presentation in presentations) {
            presentation.setTurnCardOverlay(
                AirPlayPersistence.loadClusterTurnCardX(this),
                AirPlayPersistence.loadClusterTurnCardY(this),
                AirPlayPersistence.loadClusterCardScalePercent(this, "turn"),
            )
            presentation.setSong(clusterSong)
            presentation.setSongArtwork(CarPlayMediaKeys.currentArtwork())
            // Every card here is drawn by us, and which of them exists is decided by the
            // presentation itself: the turn card only in nav-only mode, the ETA and song cards in
            // both modes, each of the drawn cards only for the switches the driver turned on.
            // The "dashboard shows" content setting deliberately plays no part - it only picks
            // which URL the phone streams, never whether our cards exist (field requirement
            // 2026-10-24: a card must not appear over CarPlay's own picture just because the
            // driver left its switch on).
            if (changanCards) {
                presentation.setTurnCardGuidance(clusterTurnGuidance)
                presentation.setEtaGuidance(clusterTurnGuidance)
            } else {
                presentation.setTurnCardGuidance(if (overlay) clusterTurnGuidance else null)
                presentation.setEtaGuidance(null)
            }
            // Apple's own full-navigation card window (FLY_CARD) keeps its position in the same
            // per-card store, so a change to it must reach the live window too - not only the next
            // time the phone starts sending that stream.
            presentation.refreshCardWindowPosition()
        }
    }

    /**
     * Hides the dashboard window after a disconnect that did not recover, leaving the projection
     * session exactly as it was.
     *
     * This is what "given the dashboard back" now means for a dropped connection. The picture is
     * what the driver complained about ("仪表上还是显示的之前的投屏画面"), so it goes away at once;
     * the ext-screen, the window and its decoders stay in our hands, which is what makes the return
     * silent - a returning phone only needs setMapVisible(true), never the open sequence that makes
     * incall announce "全屏镜像/导航已开启".
     *
     * The cost: while the phone is away the panel is BLACK, because incall keeps projecting our now
     * invisible window and does not fall back to the car's own dials on its own.
     * [releaseDashboardAfterLongLoss] is the two-minute backstop for exactly that.
     *
     * The test is the STREAM, not the session. A reconnect brings iAP2 back within a few seconds
     * while the picture stays frozen on the last decoded frame, so a session check reads that as
     * "still connected" and does nothing - field report 2026-10-18.
     */
    private fun hideDashboardForDisconnect() {
        if (shuttingDown.get() || isFinishing || isDestroyed) return
        if (!clusterProjectionStarted || clusterExtScreenReleased) return
        // Frames still flowing means the phone is here and the dashboard is not frozen at all.
        if (clusterStreamActive()) return
        if (clusterWindowHidden) return
        val presentation = clusterPresentation ?: return
        presentation.setMapVisible(false)
        clusterWindowHidden = true
        appendLog("Disconnect: 已隐藏仪表上的投屏窗口（保留投影会话，重连静默恢复）")
        // The two-minute backstop is unconditional: a phone that never comes back must still
        // get the car's own dials back, and "only hidden" leaves the panel black forever.
        mainHandler.removeCallbacks(releaseDashboardAfterLongLoss)
        mainHandler.postDelayed(releaseDashboardAfterLongLoss, disconnectReleaseFallbackMs)
    }

    /**
     * Gives the instrument cluster back to the head unit for real: hide our window and send the
     * ext-screen release (r0), exactly like the three-finger close, the field-tested path to the
     * car's own dials. It deliberately does NOT dismiss the presentation or touch the binder - the
     * full close sequence is known to tear down the head unit's projection session, after which
     * every later open is ignored.
     *
     * Only two paths call this: a disconnect the driver asked for (home-screen button or the
     * adopt path), and the two-minute last resort for a phone that never came back. Note the
     * price - after an r0 the next open is a real open, i.e. a fresh "全屏镜像/导航已开启"
     * announcement. The hand-back is unconditional: a driver who disconnects wants the car's
     * own dials back (field report 2026-10-18).
     *
     * @param immediate true for a disconnect the driver asked for: there is no reconnect coming and
     * the activity is about to finish with the ext-screen still in our hands, which leaves the
     * dashboard black rather than blank.
     */
    private fun releaseDashboardForDisconnect(immediate: Boolean = false) {
        if (shuttingDown.get() || isFinishing || isDestroyed) return
        if (!clusterProjectionStarted) return
        val presentation = clusterPresentation ?: return
        // Hiding the window is the whole hand-back now: no broadcast, no activation. The driver
        // asked for the car's own dials, and the channel stays untouched for the next session.
        presentation.setMapVisible(false)
        clusterWindowHidden = false
        mainHandler.removeCallbacks(releaseDashboardAfterLongLoss)
        appendLog(
            "Disconnect: 隐藏仪表窗口（归还仪表，不发序列）" +
                if (immediate) "（司机主动断开）" else "（${disconnectReleaseFallbackMs}ms 无画面，兜底隐藏）",
        )
    }

    private fun dismissClusterPresentation() {
        // A dismissed window cannot be "merely hidden" any more: the next one has to be activated.
        clusterWindowHidden = false
        mainHandler.removeCallbacks(releaseDashboardAfterLongLoss)
        val presentations = (clusterLayers.values + listOfNotNull(clusterPresentation)).distinct()
        clusterLayers.clear()
        clusterPresentation = null
        clusterSurface?.let {
            sink?.clearSurface(SCREEN_TYPE_ALT, it)
            sink?.setMirrorSurface(SCREEN_TYPE_MAIN, CLUSTER_MAIN_MIRROR, null)
        }
        clusterSurface = null
        mirrorClusterSurface?.let { sink?.setMirrorSurface(SCREEN_TYPE_MAIN, CLUSTER_MAIN_MIRROR, null) }
        navClusterSurface?.let { sink?.clearSurface(SCREEN_TYPE_ALT, it) }
        mirrorClusterSurface = null
        navClusterSurface = null
        cardClusterSurface?.let { sink?.clearSurface(SCREEN_TYPE_ALT_CARD, it) }
        cardClusterSurface = null
        presentations.forEach { runCatching { it.dismiss() } }
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterMapShown(false)
    }

    private fun onClusterSurface(surface: Surface?, force: Boolean = false) {
        if (!force && clusterSurface === surface) return
        // A direct handoff lets MediaCodec.setOutputSurface preserve its reference frames.
        // Clearing first would destroy the decoder and can leave stream 111 waiting for an IDR.
        if (force) {
            // Hot mode switch (mirror <-> navigation stream): release the previous binding
            // first, otherwise the cluster window keeps showing the old decoder output —
            // e.g. mirror stream 110 still displayed while pos=0 (navigation) was requested.
            clusterSurface?.let { old ->
                sink?.clearSurface(SCREEN_TYPE_ALT, old)
                sink?.setMirrorSurface(SCREEN_TYPE_MAIN, CLUSTER_MAIN_MIRROR, null)
            }
        } else if (!DiLink51ClusterLayout.supported() || surface == null) {
            clusterSurface?.let { old ->
                sink?.clearSurface(SCREEN_TYPE_ALT, old)
                sink?.setMirrorSurface(SCREEN_TYPE_MAIN, CLUSTER_MAIN_MIRROR, null)
            }
        }
        clusterSurface = surface
        // Never fall back to the main surface: two decoders must not draw into one Surface.
        if (surface != null) {
            if (currentClusterMirrorMode()) {
                // Whole CarPlay screen: mirror stream 110 (main) onto the cluster window with its
                // own decoder, leaving the centre screen untouched (same mechanism as centre-card mirrors).
                sink?.setMirrorSurface(SCREEN_TYPE_MAIN, CLUSTER_MAIN_MIRROR, surface)
            } else {
                sink?.setSurface(SCREEN_TYPE_ALT, surface)
            }
        }
    }

    /**
     * Generic (Changan) path: the mirror TextureView's Surface binds CarPlay stream 110
     * (whole-screen mirror) to the incall sink and STAYS bound - the mode switch only flips
     * view visibility (ClusterMapPresentation.setMode), never this binding, so stream 110 is
     * never re-activated and the head-unit does not announce on it.
     */
    private fun onClusterMirrorSurface(surface: Surface?) {
        if (mirrorClusterSurface === surface) return
        val old = mirrorClusterSurface
        mirrorClusterSurface = surface
        if (surface != null) {
            sink?.setMirrorSurface(SCREEN_TYPE_MAIN, CLUSTER_MAIN_MIRROR, surface)
        } else if (old != null) {
            sink?.setMirrorSurface(SCREEN_TYPE_MAIN, CLUSTER_MAIN_MIRROR, null)
        }
    }

    /**
     * Generic (Changan) path: the nav TextureView's Surface binds CarPlay stream 111
     * (navigation) to the incall sink and STAYS bound from first projection. Keeping 111
     * permanently active is what stops the head-unit from announcing "全屏导航已开启" when the
     * driver swipes into nav-only mode (incall announces on 111 stream (re-)activation events).
     */
    private fun onClusterNavSurface(surface: Surface?) {
        if (navClusterSurface === surface) return
        val old = navClusterSurface
        navClusterSurface = surface
        if (surface != null) {
            sink?.setSurface(SCREEN_TYPE_ALT, surface)
        } else if (old != null) {
            sink?.clearSurface(SCREEN_TYPE_ALT, old)
        }
    }

    /**
     * FLY_CARD (dual-stream): the small card window's Surface binds the SECOND cluster stream
     * (the instruction card, internally SCREEN_TYPE_ALT_CARD) and stays bound for the whole
     * presentation's life, exactly like the nav surface - the card window is composited on top
     * of the map view, so no mode switching ever touches it.
     */
    private fun onClusterCardSurface(surface: Surface?) {
        if (cardClusterSurface === surface) return
        val old = cardClusterSurface
        cardClusterSurface = surface
        if (surface != null) {
            sink?.setSurface(SCREEN_TYPE_ALT_CARD, surface)
        } else if (old != null) {
            sink?.clearSurface(SCREEN_TYPE_ALT_CARD, old)
        }
    }

    /**
     * The instruction-card stream's real decoded picture size (from the codec's crop rectangle).
     * Delivered on a media thread; the presentation window is re-laid out on the UI thread so the
     * FLY_CARD window is always exactly the size of the picture the phone sends.
     */
    private fun onClusterCardVideoSize(width: Int, height: Int) {
        appendLog("Cluster card stream size ${width}x$height")
        runOnUiThread {
            clusterPresentation?.setCardVideoSize(width, height)
            clusterLayers.values.forEach { it.setCardVideoSize(width, height) }
        }
    }

    /**
     * Re-applies the saved left-rail crop transform to every live
     * cluster TextureView. Called from the overlay settings listener so a slider change in the
     * settings page takes effect on the dashboard immediately - without reconnecting (a
     * reconnect would re-activate the 111 nav stream and make the head unit announce
     * "全屏导航已开启"). No-op in nav-only mode (the nav stream is always full-screen).
     */
    private fun refreshClusterCrop() {
        val presentations = (clusterLayers.values + listOfNotNull(clusterPresentation)).distinct()
        presentations.forEach { runCatching { it.refreshCrop() } }
    }

    private fun clusterDisplayConfig(): AirPlayDisplayConfig? {
        // The alt screen stays advertised in every session: three-finger swipe switches the
        // dashboard mode by re-binding the sink only, never by renegotiating /info, so the
        // iPhone must keep stream 111 available even while the dashboard mirrors the main screen.
        val theme = effectiveClusterTheme()
        val display = ClusterMapPresentation.findDisplay(this, theme) ?: return null
        val size = ClusterMapPresentation.sizeOf(display)
        if (size.x <= 0 || size.y <= 0) return null
        if (DiLink51ClusterLayout.supported()) {
            val plan = DiLink51ClusterLayout.plan(size.x, size.y, theme) ?: return null
            return DiLink51ClusterLayout.streamConfig().also {
                appendLog("Cluster map: fixed 1920x720 stream; layout=$theme viewport=$plan")
            }
        }
        return CarPlayClusterDisplay.config(
            size.x,
            size.y,
            // Changan Feiyu OS does not upscale the cluster stream: any scale below 100 %
            // only shrinks the picture (noticeably so in height). Pin the generic path to the
            // full panel size so the dashboard fills the panel regardless of the scale setting.
            100,
            AirPlayPersistence.loadClusterMarkerHorizontalStep(this),
            AirPlayPersistence.loadClusterMarkerVerticalStep(this),
            AirPlayPersistence.loadClusterContent(this),
            // The driver's four edges (px), default bottom 110 for the moulding strip. These go
            // into /info, so a change here lands on the next connection.
            safeInsets = com.shilapi.xcertplay.airplay.AirPlayInsets(
                top = AirPlayPersistence.loadClusterSafeInset(this, "top"),
                bottom = AirPlayPersistence.loadClusterSafeInset(this, "bottom"),
                left = AirPlayPersistence.loadClusterSafeInset(this, "left"),
                right = AirPlayPersistence.loadClusterSafeInset(this, "right"),
            ),
        ).also {
            appendLog("Cluster map: requesting ${it.widthPixels}x${it.heightPixels} on ${size.x}x${size.y} safeArea=${it.safeArea} url=${it.initialUrl}")
        }
    }

    /**
     * The media keys the steering wheel sends once CarPlay holds the audio focus: track change and
     * play/pause. KEYCODE_HEADSETHOOK is deliberately absent - some ROMs deliver the wheel's voice
     * key through it, and swallowing that would kill Siri instead of the music.
     */
    private fun isWheelMediaKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        // Vendor play/pause keys: CarPlayMediaButton maps them to the toggle, and without them
        // here a firmware that sends 353/331 instead of the standard codes slips past the block.
        CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE,
        CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE_DILINK3 -> true
        else -> false
    }

    // Hardware navigation belongs to the iPhone-rendered CarPlay UI, not Android View focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Which keys the head unit actually hands to this window. Wheel keys are routed per OEM
        // policy - some keys never arrive, others arrive rewritten - and the hang-up key the driver
        // needs for a WeChat call could not be identified from the logs at all (2026-10-20).
        // Only presses, only outside the noisy volume/media set, so this stays a handful of lines
        // per drive.
        if (activeAirPlaySession != null && event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
            !isWheelMediaKey(event.keyCode)
        ) {
            appendLog("Key: ${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode})")
        }
        // While navigation guidance is actually audible, steer the volume keys to the guidance
        // stream. The stream's own start/stop edge is not enough: on this head unit the navigation
        // stream can stay open for minutes after a prompt, and keys routed that whole time adjust
        // the guidance level instead of the music (field report 2026-10-20). Only PCM written to
        // the guidance track counts.
        if (navigationGuidanceActive && sink?.navigationAudioActive() == true &&
            event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val dir = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
            val navChannel = AirPlayPersistence.loadNavigationAudioChannel(this)
            // AudioManager exposes no constant for the framework's navigation stream (14);
            // it is the stream that stock car guidance (usage NAVIGATION_GUIDANCE) maps to.
            val stream = if (navChannel in 1..10) navChannel else NAVIGATION_STREAM_TYPE
            getSystemService(AudioManager::class.java)?.adjustStreamVolume(
                stream,
                dir,
                AudioManager.FLAG_SHOW_UI,
            )
            return true
        }

        // The steering wheel's hang-up key stays with the head unit. An earlier build also sent a
        // telephony Hook Switch to the phone for calls the car has no phone app behind (WeChat),
        // but the driver reports the wheel already ends those calls with the phone locked, and a
        // second hang-up reaching the phone after the car's own one can redial the last number.
        // Left in place for the log only: it names the key code the wheel really sends.
        if (activeAirPlaySession != null && event.keyCode == KeyEvent.KEYCODE_ENDCALL &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        ) {
            appendLog(
                "Call: wheel hang-up seen, CarPlay VoIP=${inCarPlayVoipCall()}; " +
                    "left to the head unit",
            )
        }

        if (!menuOpen && AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this) &&
            CarPlayRemoteKeys.dispatch(event, controller)) {
            if (event.repeatCount == 0) {
                Log.d(
                    TAG,
                    "remote key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action}",
                )
            }
            return true
        }

        // Media belongs to the head unit's Bluetooth, so the CarPlay window must not claim the
        // wheel's media keys. This must depend ONLY on the switch: music is rendered inside
        // CarPlay, so AudioManager.isBluetoothA2dpOn is normally false even though the head unit's
        // own media app receives the wheel key. The old A2DP check therefore let the key through
        // to both the car and the iPhone, skipping two tracks per press (field 2026-10-24).
        // Consuming it here keeps it away from CarPlay; the head unit's own key routing still
        // delivers it to its media app. Voice keys are handled above and stay live - Siri must
        // still open.
        if (activeAirPlaySession != null &&
            CarPlayVideo.activity == null &&
            isWheelMediaKey(event.keyCode) &&
            AirPlayPersistence.loadMediaViaCarBluetooth(this)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                appendLog(
                    "Media: wheel key ${KeyEvent.keyCodeToString(event.keyCode)} blocked " +
                        "(media belongs to the car Bluetooth)",
                )
            }
            return true
        }

        // Steering-wheel next/previous, claimed by the CarPlay screen itself. The head unit
        // gives these keys to whichever window is in front, so claiming them here is what
        // makes the wheel change tracks without the wheel-key/accessibility service.
        val mediaPress = CarPlayMediaButton.forCarPlayWindow(
            event.keyCode,
            sessionActive = activeAirPlaySession != null,
            inCall = inCarPlayCall(),
        )
        if ((event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) && mediaPress != null) {
            // A media button is a tap, and the head unit sends each tap as an instant down/up
            // pair: send once, on the press. The release is swallowed rather than passed on,
            // or the car's own player would receive an unpaired key.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val sent = controller?.sendMediaButton(mediaPress) == true
                appendLog("Media: wheel key ${event.keyCode} -> CarPlay $mediaPress sent=$sent")
            }
            return true
        }

        // Keep DiPlay's existing steering-wheel/voice-key Siri handling intact.
        if (!CarPlayMediaButton.opensSiri(event.keyCode)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) {
            val sent = controller?.requestSiri() == true
            appendLog("Siri: voice key ${event.keyCode} sent=$sent")
        }
        return true
    }

    /** True while the car's audio mode is not normal (a call is active on the head unit). */
    private fun inCarPlayCall(): Boolean =
        (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode
            ?.let { it != AudioManager.MODE_NORMAL } == true

    /**
     * True while a call runs on CarPlay's own audio path. The sink switches the head unit to
     * MODE_IN_COMMUNICATION when the phone opens a telephony/speech stream, while a cellular call
     * the head unit handles itself uses MODE_IN_CALL. The distinction is what keeps the wheel's
     * hang-up key working for the car's own phone app.
     */
    private fun inCarPlayVoipCall(): Boolean =
        (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode ==
            AudioManager.MODE_IN_COMMUNICATION

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.WINDOW_FOCUS)
            applyFullscreenMode()
        }
    }

    override fun onStop() {
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        logThemeState(ThemeModeDiagnostics.Source.STOP, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    /** Shows the dashboard map as a card on the centre screen while DiPlay is in the background. */
    private fun showCenterMap() {
        if (isDestroyed || shuttingDown.get() || sink == null) return
        if (!AirPlayPersistence.loadCenterMapOverlay(this)) return
        if (!AirPlayPersistence.loadCenterMapFollowsDashboard(this)) return
        if (MapMirrors.launcherShowsMap) return // the launcher has the map on its own screen
        // Without the stream the card would stay black; it follows once the stream starts.
        if (SCREEN_TYPE_ALT !in activeScreenStreamTypes) return
        if (!CenterMapOverlay.permitted(this)) {
            appendLog("Centre map: no permission to draw over other apps")
            return
        }
        // Without Usage Access the card shows over any app, as before.
        if (HomeScreenMonitor.hasAccess(this)) {
            val monitor = homeMonitor ?: HomeScreenMonitor(this, ::onHomeScreenVisible).also { homeMonitor = it }
            if (!monitor.running) {
                monitor.start() // its first answer shows the card
                return
            }
            if (homeScreenVisible != true) {
                CenterMapOverlay.hide()
                return
            }
        }
        if (CenterMapOverlay.shown) return
        val shown = CenterMapOverlay.show(applicationContext, MapMirrors.STREAM_ASPECT, ::onCenterMapSurface) {
            startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        appendLog("Centre map: card ${if (shown) "shown" else "failed"} streamActive=${SCREEN_TYPE_ALT in activeScreenStreamTypes}")
    }

    private fun onHomeScreenVisible(visible: Boolean) {
        homeScreenVisible = visible
        appendLog("Centre map: home screen ${if (visible) "in front" else "not in front"}")
        if (!visible) CenterMapOverlay.hide() else if (!CenterMapOverlay.diPlayInFront()) showCenterMap()
    }

    private fun onCenterMapSurface(surface: Surface?) {
        MapMirrors.set(MapMirrors.CARD, surface)
        appendLog(if (surface != null) "Centre map: mirroring the dashboard stream" else "Centre map: mirror stopped")
    }

    // The dashboard map pause must not stop the stream while a copy of the map is on screen.
    private fun updateClusterMapShown() {
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterMapShown(clusterPresentation != null && !MapMirrors.any)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshConfiguration(newConfig, ThemeModeDiagnostics.Source.CALLBACK)
        applyFullscreenMode()
        stageStatusView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        scrollLogsToBottom()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onDestroy() {
        if (CarPlayBackgroundSession.logger != null) CarPlayBackgroundSession.logger = null
        InCallClusterBridge.projectingListener = null
        if (clusterDisplayListenerRegistered) {
            runCatching {
                getSystemService(android.hardware.display.DisplayManager::class.java)
                    ?.unregisterDisplayListener(clusterDisplayListener)
            }
            clusterDisplayListenerRegistered = false
        }
        mainHandler.removeCallbacks(refreshTurnOverlay)
        mainHandler.removeCallbacks(checkVideoFreeze)
        runCatching { repaintExecutor.shutdownNow() }
        mainHandler.removeCallbacks(hideDashboardAfterDisconnect)
        mainHandler.removeCallbacks(releaseDashboardAfterLongLoss)
        // Leaving only hides the window: no broadcast, no activation. The channel is left as the
        // head unit has it, so a relaunch finds the same state the watcher sampled.
        if (isFinishing && clusterPresentation != null) {
            clusterPresentation?.setMapVisible(false)
            clusterWindowHidden = false
            sessionLog?.append("Activity finishing: dashboard window hidden")
        }
        AirPlayPersistence.overlaySettingsListener = null
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setTurnOverlayListener(null)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setSongListener(null)
        clusterMonitor?.stop()
        mainHandler.removeCallbacks(hideIdleCenterMap)
        homeMonitor?.stop()
        CenterMapOverlay.hide()
        if (CenterMapOverlay.requestShow == (::showCenterMap)) CenterMapOverlay.requestShow = null
        if (MapMirrors.sink === mirrorSink) {
            MapMirrors.sink = null
            MapMirrors.setStreamActive(false)
        }
        if (MapMirrors.onChanged === mirrorsChanged) MapMirrors.onChanged = null
        // Do NOT call dismissClusterPresentation() here. On the generic (Chang'an) path both
        // stream surfaces (110 mirror + 111 nav) stay bound to the incall sink while the cluster
        // window lives; an explicit dismiss unbinds stream 111, and the next activity's rebuilt
        // window re-attaches it, which re-activates 111 and makes the head unit announce
        // "全屏导航已开启" (field report 10-08). The system dismisses the presentation when the
        // activity is torn down anyway (setOnDismissListener clears the reference).
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(pollConfiguration)
        currentSurface?.let { surface ->
            sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
            sink?.clearSurface(SCREEN_TYPE_ALT, surface)
            surface.release()
        }
        currentSurface = null
        currentSurfaceTexture = null
        // The background session may outlive this activity (system reclaim); drop the hot-restart
        // delegate so the settings page cannot invoke restartCarPlay() on a destroyed activity.
        CarPlayBackgroundSession.setRestart(null)
        CarPlayBackgroundSession.setDockAction(null)
        pictureBinding?.close()
        pictureBinding = null
        sessionLog?.append("Activity destroyed")
        sessionLog?.close()
        sessionLog = null
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = TextureView(this).apply {
            isOpaque = false
            surfaceTextureListener = textureListener
        }
        pictureBinding = CarPlayPicture.Binding(video)
        // Gesture layer sits on top of everything and only consumes three-finger swipes;
        // all other touches fall through to the connection panel or the CarPlay video.
        val gestureLayer = View(this).apply {
            setOnTouchListener { view, event -> onGestureTouch(view, event) }
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(Color.rgb(12, 17, 27))
            isClickable = true
            setOnTouchListener { view, event -> onGestureTouch(view, event) }
        }
        panel.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay); contentDescription = getString(R.string.carplay)
        }, LinearLayout.LayoutParams(dp(88), dp(88)))
        panel.addView(TextView(this).apply {
            text = getString(R.string.diplay); textSize = 34f; setTextColor(Color.rgb(241, 245, 252))
            gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(14))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        val stage = TextView(this).apply {
            text = getString(R.string.getting_carplay_ready); textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(241, 245, 252))
        }
        panel.addView(stage)
        panel.addView(TextView(this).apply {
            text = if (wirelessEnabled) getString(R.string.keep_your_iphone_nearby_with_bluetooth_and_wi_fi_on_allow)
                else getString(R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an)
            textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.rgb(168, 182, 202))
            setPadding(0, dp(14), 0, dp(24))
        })
        panel.addView(Button(this).apply {
            text = getString(R.string.reset_carplay_wi_fi); isAllCaps = false; textSize = 18f
            visibility = View.GONE
            setOnClickListener { showDiPlayHome("wireless-recovery") }
            wifiRecoveryButton = this
        }, LinearLayout.LayoutParams(dp(300), dp(64)).apply { bottomMargin = dp(12) })
        panel.addView(Button(this).apply {
            text = getString(R.string.back_to_diplay); isAllCaps = false; textSize = 18f
            setTextColor(Color.rgb(12, 17, 27))
            background = GradientDrawable().apply { setColor(Color.rgb(166, 200, 255)); cornerRadius = dp(20).toFloat() }
            setOnClickListener { showDiPlayHome() }
        }, LinearLayout.LayoutParams(dp(300), dp(64)))
        val gestureHint = TextView(this).apply {
            text = getString(R.string.open_diplay_settings_hint)
            textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.rgb(168, 182, 202)); setPadding(0, dp(20), 0, 0)
        }
        panel.addView(gestureHint)
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        // Gesture layer on top of the connection panel: three-finger swipes work even before
        // projection starts. CarPlay touches land on the video view (listener below).
        root.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        video.setOnTouchListener { view, event -> onHostTouch(view, event) }
        videoView = video
        gestureOverlay = gestureLayer
        settingsGestureHint = gestureHint
        stageStatusView = stage
        connectionPanel = panel
        updateDebugOverlays()
        return root
    }

    private fun buildSettingsMenu(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val panel = FrameLayout(this).apply {
            setBackgroundColor(MENU_BACKGROUND)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(36), dp(48), dp(36))
        }
        content.addView(
            menuText(getString(R.string.carplay_settings), 32f, Color.WHITE, bold = true).apply {
                setPadding(dp(56), 0, 0, 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            settingsCategoryHeader(getString(R.string.connection)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(32) },
        )

        content.addView(
            buildMfiTargetSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        val wirelessRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        wirelessRow.addView(
            menuText(getString(R.string.wireless_carplay_2), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val wirelessSwitch = Switch(this).apply {
            isChecked = wirelessEnabled
            contentDescription = getString(R.string.wireless_carplay_transport)
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
            )
            setOnCheckedChangeListener { _, checked ->
                if (wirelessEnabled == checked) return@setOnCheckedChangeListener
                wirelessEnabled = checked
                hotspotStatus = HotspotStatus(state = if (wirelessEnabled) getString(R.string.hotspot_state_stopped) else getString(R.string.hotspot_state_off))
                updateHotspotStatusBlock()
                appendLog(
                    "Wireless CarPlay ${if (wirelessEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                requestStartupPrerequisites()
            }
        }
        wirelessRow.addView(
            wirelessSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            wirelessRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            buildHotspotModeSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            menuText(getString(R.string.hotspot_status), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) },
        )
        val hotspotStatusView = menuText("", 16f, MENU_ACCENT)
        content.addView(
            hotspotStatusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        content.addView(
            settingsCategoryHeader(getString(R.string.location)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            buildLocationReportingSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(
            settingsCategoryHeader(getString(R.string.startup)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            settingsSwitchRow(
                label = getString(R.string.auto_start_on_boot),
                checked = autoStartOnBoot,
                description = getString(R.string.start_carplay_automatically_after_device_boot),
            ) { checked ->
                autoStartOnBoot = checked
                appendLog("Boot auto-start ${if (checked) "enabled" else "disabled"}")
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        if (advancedAudioChannelMappingSupported) {
            content.addView(
                settingsCategoryHeader(getString(R.string.audio)),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(36) },
            )
            content.addView(
                settingsSwitchRow(
                    label = getString(R.string.advanced_audio_channel_mapping),
                    checked = advancedAudioChannelMapping,
                    description = getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                ) { checked ->
                    advancedAudioChannelMapping = checked
                    appendLog(
                        "Advanced audio channel mapping ${if (checked) "enabled" else "disabled"}; " +
                            "applies when settings close",
                    )
                    updateResolutionMenu()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }

        content.addView(
            settingsCategoryHeader(getString(R.string.identity_appearance)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            buildIdentitySettingsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            buildAirPlayIconSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content.addView(
            buildDrivingSideSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content.addView(
            settingsCategoryHeader(getString(R.string.display_video)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )

        val resolutionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        resolutionHeader.addView(
            menuText(getString(R.string.resolution), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val resolutionValue = menuText(
            CarPlayDisplayScale.label(displayScaleTenths),
            28f,
            MENU_ACCENT,
            bold = true,
        )
        resolutionHeader.addView(
            resolutionValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            resolutionHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        val seekBar = SeekBar(this).apply {
            max = CarPlayDisplayScale.MAX_TENTHS - CarPlayDisplayScale.MIN_TENTHS
            progress = displayScaleTenths - CarPlayDisplayScale.MIN_TENTHS
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        displayScaleTenths = CarPlayDisplayScale.sanitize(
                            CarPlayDisplayScale.MIN_TENTHS + progress,
                        )
                        updateResolutionMenu()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        content.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        val range = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        range.addView(
            menuText(getString(R.string.s_0_3x), 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        range.addView(
            menuText(getString(R.string.s_1_0x), 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            range,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        content.addView(
            buildStepSliderSection(
                title = getString(R.string.frame_rate),
                values = (
                    AirPlayDisplaySettings.MIN_FPS..AirPlayDisplaySettings.MAX_FPS
                    step AirPlayDisplaySettings.FPS_STEP
                    ).toList(),
                selectedValue = fps,
                label = { "$it fps" },
                onValueChanged = { value ->
                    fps = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        content.addView(
            settingsChoiceRow(
                label = getString(R.string.physical_size_basis),
                options = listOf(
                    AirPlayPhysicalSizeBasis.WIDTH to getString(R.string.widest_width),
                    AirPlayPhysicalSizeBasis.HEIGHT to getString(R.string.longest_height),
                ),
                selected = physicalSizeBasis,
            ) { value ->
                physicalSizeBasis = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        content.addView(
            buildStepSliderSection(
                title = getString(R.string.physical_length),
                values = (
                    AirPlayDisplaySettings.MIN_WIDTH_PHYSICAL_MM..
                        AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM
                    step AirPlayDisplaySettings.WIDTH_PHYSICAL_MM_STEP
                    ).toList(),
                selectedValue = widthPhysicalMm,
                label = { "$it mm" },
                onValueChanged = { value ->
                    widthPhysicalMm = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

        val hevcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hevcRow.addView(
            menuText("HEVC (H.265)", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val hevcSwitch = Switch(this).apply {
            isChecked = hevcEnabled
            contentDescription = getString(R.string.hevc_h_265_video_transport)
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
            )
            setOnCheckedChangeListener { _, checked ->
                if (hevcEnabled == checked) return@setOnCheckedChangeListener
                hevcEnabled = checked
                appendLog(
                    "HEVC (H.265) ${if (hevcEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                updateResolutionMenu()
            }
        }
        hevcRow.addView(
            hevcSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            hevcRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        val softwareHevcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        softwareHevcRow.addView(
            menuText(getString(R.string.hevc_software_decoder), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val softwareHevcSwitch = Switch(this).apply {
            isChecked = hevcSoftwareDecoderEnabled
            contentDescription = getString(R.string.use_software_hevc_decoder)
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
            )
            setOnCheckedChangeListener { _, checked ->
                if (hevcSoftwareDecoderEnabled == checked) return@setOnCheckedChangeListener
                hevcSoftwareDecoderEnabled = checked
                appendLog(
                    "HEVC software decoder ${if (hevcSoftwareDecoderEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                updateResolutionMenu()
            }
        }
        softwareHevcRow.addView(
            softwareHevcSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            content.addView(
                softwareHevcRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(16) },
            )
        }

        content.addView(
            buildSafeAreaSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            settingsCategoryHeader(getString(R.string.window)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )
        content.addView(
            buildFullscreenSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(
            settingsCategoryHeader(getString(R.string.diagnostics)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )
        content.addView(
            buildDebugLogsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            content.addView(
                settingsCategoryHeader(getString(R.string.android_9_compatibility)),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(40) },
            )
            content.addView(
                menuText(
                    getString(R.string.settings_android9_compat),
                    16f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }

        val preview = menuText("", 17f, MENU_SECONDARY)
        content.addView(
            preview,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        val save = Button(this).apply {
            text = getString(R.string.save_and_reconnect)
            isAllCaps = false
            textSize = 17f
            setTextColor(MENU_BUTTON_TEXT)
            backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
            minHeight = dp(52)
            setOnClickListener { saveSettingsAndReconnect() }
        }
        content.addView(
            save,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(46) },
        )

        val exitApplicationButton = Button(this).apply {
            text = getString(R.string.exit_application)
            isAllCaps = false
            textSize = 17f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(MENU_DANGER)
            minHeight = dp(52)
            setOnClickListener { exitApplication() }
        }
        content.addView(
            exitApplicationButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(Button(this).apply {
            text = getString(R.string.language_app_language)
            isAllCaps = false
            setOnClickListener { AppLocale.showPicker(this@CarPlayHostActivity) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        panel.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        panel.addView(
            Button(this).apply {
                text = "X"
                isAllCaps = false
                textSize = 22f
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(MENU_TRACK_OFF)
                contentDescription = getString(R.string.discard_changes_and_exit_settings)
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { cancelSettingsEdits() }
            },
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(16)
                topMargin = dp(16)
            },
        )
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                minOf(resources.displayMetrics.widthPixels, MAX_SETTINGS_MENU_WIDTH_PX),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        overlay.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val desiredWidth = minOf(view.width, MAX_SETTINGS_MENU_WIDTH_PX)
            val params = panel.layoutParams
            if (params.width != desiredWidth) {
                params.width = desiredWidth
                panel.layoutParams = params
            }
        }

        resolutionValueView = resolutionValue
        resolutionPreviewView = preview
        this.hotspotStatusView = hotspotStatusView
        updateHotspotStatusBlock()
        updateResolutionMenu()
        return overlay
    }

    private fun persistMenuSettings() {
        AirPlayPersistence.saveWirelessEnabled(this, wirelessEnabled)
        AirPlayPersistence.saveMfiTarget(this, mfiTarget)
        AirPlayPersistence.saveMfiI2cPath(this, mfiI2cPath)
        AirPlayPersistence.saveRemoteMfiServer(this, remoteMfiServer)
        AirPlayPersistence.saveRemoteMfiToken(this, remoteMfiToken)
        AirPlayPersistence.saveWirelessHotspotMode(this, wirelessHotspotMode)
        AirPlayPersistence.saveExistingWifiCredentials(this, existingWifiSsid, existingWifiPassphrase)
        AirPlayPersistence.saveManualHotspotSsid(this, manualHotspotSsid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, manualHotspotPassphrase)
        AirPlayPersistence.saveManualHotspotBand(this, manualHotspotBand)
        AirPlayPersistence.saveManualHotspotChannel(this, manualHotspotChannel)
        AirPlayPersistence.saveManualHotspotSecurity(this, manualHotspotSecurity)
        AirPlayPersistence.saveLocationReportingEnabled(this, locationReportingEnabled)
        AirPlayPersistence.saveAutoStartOnBoot(this, autoStartOnBoot)
        AirPlayPersistence.saveAdvancedAudioChannelMapping(this, advancedAudioChannelMapping)
        AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
        AirPlayPersistence.saveFps(this, fps)
        AirPlayPersistence.saveWidthPhysicalMm(this, widthPhysicalMm)
        AirPlayPersistence.savePhysicalSizeBasis(this, physicalSizeBasis)
        AirPlayPersistence.saveHevcEnabled(this, hevcEnabled)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(this, hevcSoftwareDecoderEnabled)
        AirPlayPersistence.saveManufacturer(this, manufacturer)
        AirPlayPersistence.saveModel(this, model)
        AirPlayPersistence.saveOemLabel(this, oemLabel)
        AirPlayPersistence.saveDebugLogsEnabled(this, debugLogsEnabled)
        AirPlayPersistence.saveRightHandDrive(this, rightHandDrive)
        AirPlayPersistence.saveHideTopBar(this, hideTopBar)
        AirPlayPersistence.saveHideBottomBar(this, hideBottomBar)
        AirPlayPersistence.saveSafeAreaDrawOutside(this, safeAreaDrawOutside)
    }

    private fun captureSettingsBaseline(): SettingsBaseline {
        val safeAreaSize = currentActivitySize()
        val customIconBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the current AirPlay icon for settings rollback", error)
            null
        }
        return SettingsBaseline(
            safeAreaSize = safeAreaSize,
            safeAreaRect = safeAreaSize?.let {
                AirPlayPersistence.loadSafeAreaRect(this, it.width, it.height)
            },
            customIconBytes = customIconBytes,
        )
    }

    private fun restoreSettingsBaseline() {
        val baseline = settingsBaseline ?: return
        loadPersistedSettings()
        baseline.safeAreaSize?.let { size ->
            baseline.safeAreaRect?.let { rect ->
                AirPlayPersistence.saveSafeAreaRect(
                    this,
                    size.width,
                    size.height,
                    rect,
                    commit = true,
                )
            } ?: AirPlayPersistence.clearSafeAreaRect(
                this,
                size.width,
                size.height,
                commit = true,
            )
        }
        try {
            baseline.customIconBytes?.let { bytes ->
                AirPlayPersistence.saveCustomAirPlayIcon(this, bytes)
            } ?: AirPlayPersistence.clearCustomAirPlayIcon(this)
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore the previous AirPlay icon", error)
        }
        settingsBaseline = null
        locationPermissionAvailable = hasFineLocationPermission()
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) getString(R.string.hotspot_state_stopped) else getString(R.string.hotspot_state_off))
        syncMfiSettingsControls()
        updateManualHotspotFields()
        updateAirPlayIconPreview()
        updateSafeAreaSummary()
        updateHotspotStatusBlock()
        updateResolutionMenu()
        updateDebugOverlays()
        applyFullscreenMode()
        refreshDisplaySizeAfterLayout()
    }

    private fun buildMfiTargetSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val targetChoice = settingsChoiceRow(
            label = getString(R.string.mfi_certificate_signing_target),
            options = listOf(
                MfiTarget.USB_CH341 to getString(R.string.usb_ch341),
                MfiTarget.I2C to getString(R.string.i2c),
                MfiTarget.REMOTE to getString(R.string.remote),
            ),
            selected = mfiTarget,
        ) { target ->
            if (mfiTarget == target) return@settingsChoiceRow
            mfiTarget = target
            updateMfiTargetFields()
            appendLog("MFI target: ${mfiTargetLabel(target)}; applies when settings close")
        }
        mfiTargetGroup = (targetChoice as ViewGroup).getChildAt(1) as RadioGroup
        section.addView(
            targetChoice,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val i2cFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    getString(R.string.i2c_device),
                    mfiI2cPath,
                    onInputCreated = { mfiI2cPathInput = it },
                ) { value ->
                    mfiI2cPath = value
                    mfiErrorView?.visibility = View.GONE
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(getString(R.string.linux_device_path_for_example_dev_i2c_1), 14f, MENU_SECONDARY),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            i2cFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiI2cFields = i2cFields

        val remoteFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    getString(R.string.server_address),
                    remoteMfiServer,
                    onInputCreated = { remoteMfiServerInput = it },
                ) { value ->
                    remoteMfiServer = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                settingsInputRow(
                    getString(R.string.token_optional),
                    remoteMfiToken,
                    password = true,
                    onInputCreated = { remoteMfiTokenInput = it },
                ) { value ->
                    remoteMfiToken = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            addView(
                menuText(
                    getString(R.string.settings_mfi_address_hint),
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            remoteFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiRemoteFields = remoteFields
        val error = menuText("", 14f, MENU_DANGER).apply {
            visibility = View.GONE
        }
        section.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        mfiErrorView = error
        updateMfiTargetFields()
        return section
    }

    private fun updateMfiTargetFields() {
        mfiI2cFields?.visibility = if (mfiTarget == MfiTarget.I2C) View.VISIBLE else View.GONE
        mfiRemoteFields?.visibility = if (mfiTarget == MfiTarget.REMOTE) View.VISIBLE else View.GONE
        mfiErrorView?.visibility = View.GONE
    }

    private fun syncMfiSettingsControls() {
        mfiTargetGroup?.let { group ->
            val button = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filterIsInstance<RadioButton>()
                .firstOrNull { it.tag == mfiTarget }
            button?.let { group.check(it.id) }
        }
        if (mfiI2cPathInput?.text?.toString() != mfiI2cPath) {
            mfiI2cPathInput?.setText(mfiI2cPath)
        }
        if (remoteMfiServerInput?.text?.toString() != remoteMfiServer) {
            remoteMfiServerInput?.setText(remoteMfiServer)
        }
        if (remoteMfiTokenInput?.text?.toString() != remoteMfiToken) {
            remoteMfiTokenInput?.setText(remoteMfiToken)
        }
        updateMfiTargetFields()
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.LOCAL -> getString(R.string.local_offline)
        MfiTarget.USB_CH341 -> getString(R.string.usb_ch341)
        MfiTarget.I2C -> getString(R.string.i2c)
        MfiTarget.REMOTE -> getString(R.string.remote)
    }

    private fun buildIdentitySettingsSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            settingsInputRow(getString(R.string.manufacturer), manufacturer) { value ->
                manufacturer = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            settingsInputRow(getString(R.string.model), model) { value ->
                model = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsInputRow(getString(R.string.oem_label), oemLabel) { value ->
                oemLabel = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    private fun settingsCategoryHeader(title: String): TextView =
        menuText(title, 16f, MENU_ACCENT, bold = true)

    private fun buildLocationReportingSection(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val row = LinearLayout(this@CarPlayHostActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                menuText(getString(R.string.report_location_to_iphone), 20f, MENU_SECONDARY),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val switch = Switch(this@CarPlayHostActivity).apply {
                isChecked = locationReportingEnabled
                contentDescription = getString(R.string.report_android_location_to_the_iphone)
                showText = false
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
                )
                setOnCheckedChangeListener { _, checked ->
                    onLocationReportingChanged(checked)
                }
            }
            locationReportingSwitch = switch
            row.addView(
                switch,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(
                    getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
        }

    private fun onLocationReportingChanged(checked: Boolean) {
        if (locationReportingEnabled == checked) return
        locationReportingEnabled = checked
        appendLog(
            "Location reporting ${if (locationReportingEnabled) "enabled" else "disabled"}; " +
                "applies when settings close",
        )
        updateResolutionMenu()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
    }

    private fun buildDebugLogsSection(): View =
        settingsSwitchRow(
            label = getString(R.string.debug_logs),
            checked = debugLogsEnabled,
            description = getString(R.string.show_on_screen_debug_logs),
        ) { checked ->
            debugLogsEnabled = checked
            appendLog("Debug logs ${if (debugLogsEnabled) "enabled" else "disabled"}")
            updateDebugOverlays()
        }

    private fun buildStepSliderSection(
        title: String,
        values: List<Int>,
        selectedValue: Int,
        label: (Int) -> String,
        onValueChanged: (Int) -> Unit,
    ): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText(title, 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val selectedIndex = values.indexOf(selectedValue)
            .takeIf { it >= 0 }
            ?: 0
        val valueView = menuText(label(values[selectedIndex]), 22f, MENU_ACCENT, bold = true)
        header.addView(
            valueView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val seekBar = SeekBar(this).apply {
            max = (values.size - 1).coerceAtLeast(0)
            progress = selectedIndex
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = values.getOrNull(progress) ?: return
                        valueView.text = label(value)
                        if (fromUser) onValueChanged(value)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        section.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildAirPlayIconSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.airplay_icon), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(MENU_TRACK_OFF)
            }
        }
        row.addView(
            preview,
            LinearLayout.LayoutParams(dp(72), dp(72)),
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        actions.addView(
            Button(this).apply {
                text = getString(R.string.choose_image)
                isAllCaps = false
                setOnClickListener {
                    externalActivityInProgress = true
                    imagePicker.launch("image/*")
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        actions.addView(
            Button(this).apply {
                text = getString(R.string.default_icon)
                isAllCaps = false
                setOnClickListener {
                    AirPlayPersistence.clearCustomAirPlayIcon(this@CarPlayHostActivity)
                    updateAirPlayIconPreview()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        row.addView(
            actions,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(16) },
        )
        section.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        val status = menuText("", 14f, MENU_SECONDARY)
        section.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        iconPreviewView = preview
        iconStatusView = status
        updateAirPlayIconPreview()
        return section
    }

    private fun buildDrivingSideSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.driving_side), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val left = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.left_hand_drive)
            setTextColor(Color.WHITE)
            isChecked = !rightHandDrive
        }
        val right = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.right_hand_drive)
            setTextColor(Color.WHITE)
            isChecked = rightHandDrive
        }
        group.addView(left)
        group.addView(right)
        group.setOnCheckedChangeListener { _, checkedId ->
            rightHandDrive = checkedId == right.id
            updateResolutionMenu()
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildFullscreenSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.fullscreen), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.hide_top_bar),
                checked = hideTopBar,
                description = getString(R.string.hide_the_status_bar),
            ) { checked ->
                hideTopBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.hide_bottom_bar),
                checked = hideBottomBar,
                description = getString(R.string.hide_the_navigation_bar),
            ) { checked ->
                hideBottomBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    private fun buildSafeAreaSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.safe_area), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val summary = menuText("", 15f, MENU_ACCENT)
        section.addView(
            summary,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(
            Button(this).apply {
                text = getString(R.string.set)
                isAllCaps = false
                setOnClickListener { openSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        buttons.addView(
            Button(this).apply {
                text = getString(R.string.reset)
                isAllCaps = false
                setOnClickListener { resetSafeAreaForCurrentSize() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        section.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.draw_outside_safe_area),
                checked = safeAreaDrawOutside,
                description = getString(R.string.allow_carplay_ui_outside_the_safe_area),
            ) { checked ->
                safeAreaDrawOutside = checked
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        safeAreaSummaryView = summary
        updateSafeAreaSummary()
        return section
    }

    private fun buildSafeAreaEditor(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val editor = SafeAreaEditorView(this)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.addView(
            menuText(getString(R.string.safe_area), 24f, Color.WHITE, bold = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(8))
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        controls.addView(
            Button(this).apply {
                text = getString(R.string.cancel)
                isAllCaps = false
                setOnClickListener { closeSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(
            Button(this).apply {
                text = getString(R.string.save)
                isAllCaps = false
                setOnClickListener { saveSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        overlay.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        safeAreaEditorView = editor
        return overlay
    }

    private fun settingsInputRow(
        label: String,
        value: String,
        password: Boolean = false,
        numeric: Boolean = false,
        onInputCreated: ((EditText) -> Unit)? = null,
        onChanged: (String) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            EditText(this@CarPlayHostActivity).apply {
                setText(value)
                textSize = 18f
                setTextColor(Color.WHITE)
                setHintTextColor(MENU_SECONDARY)
                backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
                minHeight = dp(48)
                isSingleLine = true
                inputType = when {
                    numeric -> InputType.TYPE_CLASS_NUMBER
                    password -> InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                addTextChangedListener(afterTextChanged(onChanged))
                onInputCreated?.invoke(this)
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(12) },
        )
    }

    private fun settingsSwitchRow(
        label: String,
        checked: Boolean,
        description: String,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            Switch(this@CarPlayHostActivity).apply {
                isChecked = checked
                contentDescription = description
                showText = false
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
                )
                setOnCheckedChangeListener { _, value -> onChanged(value) }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun afterTextChanged(onChanged: (String) -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(text: Editable?) {
                onChanged(text?.toString().orEmpty())
            }
        }

    private fun buildHotspotModeSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.wi_fi_session), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val group = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val modes = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(WirelessHotspotMode.WIFI_P2P to getString(R.string.wi_fi_p2p_5_ghz))
            }
            add(WirelessHotspotMode.MANUAL to getString(R.string.built_in_car_hotspot))
            add(WirelessHotspotMode.EXISTING_WIFI to getString(R.string.existing_wifi_title))
        }
        var selectedId = View.NO_ID
        for ((mode, label) in modes) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 18f
                setTextColor(MENU_SECONDARY)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                tag = mode
                isChecked = wirelessHotspotMode == mode
            }
            if (wirelessHotspotMode == mode) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val selected = radioGroup.findViewById<RadioButton>(checkedId)
                ?.tag as? WirelessHotspotMode
                ?: return@setOnCheckedChangeListener
            if (wirelessHotspotMode == selected) return@setOnCheckedChangeListener
            wirelessHotspotMode = selected
            hotspotStatus = HotspotStatus(state = if (wirelessEnabled) getString(R.string.hotspot_state_stopped) else getString(R.string.hotspot_state_off))
            updateHotspotStatusBlock()
            updateManualHotspotFields()
            appendLog(
                "Wi-Fi session mode: ${hotspotModeLabel(wirelessHotspotMode)}; " +
                    "applies when settings close",
            )
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val manualFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        manualFields.addView(
            settingsInputRow(getString(R.string.hotspot_ssid), manualHotspotSsid) { value ->
                manualHotspotSsid = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        manualFields.addView(
            settingsChoiceRow(
                label = getString(R.string.band),
                options = listOf(
                    ManualHotspotBand.AUTO to getString(R.string.auto),
                    ManualHotspotBand.GHZ_2_4 to getString(R.string.s_2_4_ghz),
                    ManualHotspotBand.GHZ_5 to getString(R.string.s_5_ghz),
                ),
                selected = manualHotspotBand,
            ) { value ->
                manualHotspotBand = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = getString(R.string.channel_0_auto),
                value = manualHotspotChannel.toString(),
                numeric = true,
            ) { value ->
                manualHotspotChannel = value.toIntOrNull() ?: -1
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = getString(R.string.hotspot_password),
                value = manualHotspotPassphrase,
                password = true,
            ) { value ->
                manualHotspotPassphrase = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsChoiceRow(
                label = getString(R.string.security),
                options = listOf(
                    ManualHotspotSecurity.OPEN to getString(R.string.open),
                    ManualHotspotSecurity.WPA2 to getString(R.string.wpa2),
                    ManualHotspotSecurity.WPA3_TRANSITION to getString(R.string.wpa3_transition),
                    ManualHotspotSecurity.WPA3 to getString(R.string.wpa3),
                ),
                selected = manualHotspotSecurity,
            ) { value ->
                manualHotspotSecurity = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        val error = menuText("", 14f, Color.rgb(0xff, 0x7a, 0x7a)).apply {
            visibility = View.GONE
        }
        manualFields.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        section.addView(
            manualFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        manualHotspotFields = manualFields
        manualHotspotErrorView = error
        val existingFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(menuText(getString(R.string.existing_wifi_instructions), 14f, MENU_SECONDARY))
            addView(settingsInputRow(getString(R.string.existing_wifi_ssid), existingWifiSsid) {
                existingWifiSsid = it
                existingWifiErrorView?.visibility = View.GONE
            })
            addView(settingsInputRow(getString(R.string.existing_wifi_password), existingWifiPassphrase, password = true) {
                existingWifiPassphrase = it
                existingWifiErrorView?.visibility = View.GONE
            })
        }
        existingWifiErrorView = menuText("", 14f, MENU_DANGER).apply { visibility = View.GONE }
        existingFields.addView(existingWifiErrorView)
        section.addView(existingFields)
        existingWifiFields = existingFields
        updateManualHotspotFields()
        return section
    }

    private fun updateManualHotspotFields() {
        existingWifiFields?.visibility = if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) View.VISIBLE else View.GONE
        existingWifiErrorView?.visibility = View.GONE
        val visible = wirelessHotspotMode == WirelessHotspotMode.MANUAL
        manualHotspotFields?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) manualHotspotErrorView?.visibility = View.GONE
    }

    private fun validateMfiSettings(): Boolean {
        val error = when {
            mfiTarget == MfiTarget.I2C && mfiI2cPath.isBlank() ->
                getString(R.string.i2c_device_path_is_required)
            mfiTarget == MfiTarget.REMOTE && remoteMfiServer.isBlank() ->
                getString(R.string.remote_server_address_is_required)
            mfiTarget == MfiTarget.REMOTE &&
                !remoteMfiServer.trim().startsWith("http://") &&
                !remoteMfiServer.trim().startsWith("https://") ->
                getString(R.string.remote_server_address_must_start_with_http_or_https)
            '\u0000' in mfiI2cPath -> getString(R.string.i2c_device_path_contains_u_0000)
            '\u0000' in remoteMfiServer -> getString(R.string.remote_server_address_contains_u_0000)
            '\u0000' in remoteMfiToken -> getString(R.string.remote_token_contains_u_0000)
            else -> null
        }
        mfiErrorView?.text = error.orEmpty()
        mfiErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun validateManualHotspotSettings(): Boolean {
        if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
            val error = com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(existingWifiSsid, existingWifiPassphrase)
            existingWifiErrorView?.text = error?.let { getString(it.messageResource()) }.orEmpty()
            existingWifiErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
            return error == null
        }
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) return true
        val error = when {
            manualHotspotSsid.isBlank() -> getString(R.string.hotspot_ssid_is_required)
            manualHotspotSsid.encodeToByteArray().size > 32 ->
                getString(R.string.hotspot_ssid_must_be_at_most_32_utf_8_bytes)
            '\u0000' in manualHotspotSsid -> getString(R.string.hotspot_ssid_contains_u_0000)
            manualHotspotChannel !in 0..196 -> getString(R.string.channel_must_be_0_or_1_196)
            manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel) ->
                getString(R.string.channel_is_not_valid_for_the_selected_band)
            '\u0000' in manualHotspotPassphrase -> getString(R.string.hotspot_password_contains_u_0000)
            manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.isNotEmpty() ->
                getString(R.string.password_must_be_empty_when_security_is_open)
            manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.length !in 8..63 ->
                getString(R.string.wpa2_wpa3_password_must_be_8_63_characters)
            else -> null
        }
        manualHotspotErrorView?.text = error.orEmpty()
        manualHotspotErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> getString(R.string.wi_fi_p2p_5_ghz)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.localonlyhotspot)
        WirelessHotspotMode.MANUAL -> getString(R.string.manual_hotspot)
        WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_title)
    }

    private fun menuText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
    }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = getString(R.string.starting))
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = getString(R.string.ready),
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(state = getString(R.string.waiting_for_paired_iphone))
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(state = getString(R.string.connecting_bluetooth))
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(state = getString(R.string.running))
            CarPlayStatus.WirelessActive ->
                hotspotStatus.copy(state = getString(R.string.active))
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(state = getString(R.string.starting_airplay_service))
            is CarPlayStatus.Failed -> hotspotStatus.copy(state = getString(R.string.error))
            else -> return
        }
        updateHotspotStatusBlock()
    }

    private fun updateHotspotStatusBlock() {
        if (!wirelessEnabled) {
            hotspotStatusView?.text = getString(R.string.wireless_hotspot_off)
            return
        }
        val status = hotspotStatus
        hotspotStatusView?.text = buildString {
            append(getString(R.string.hotspot_wireless_prefix)).append(status.state)
            status.ssid?.let { append(getString(R.string.hotspot_ssid_prefix)).append(it) }
            status.backend?.let { append(getString(R.string.hotspot_backend_prefix)).append(it) }
            status.band?.let { append(getString(R.string.hotspot_band_prefix)).append(it) }
            status.channel?.let {
                append(getString(R.string.hotspot_channel_prefix)).append(if (it == 0) getString(R.string.auto_label) else it.toString())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> settingsChoiceRow(
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        onSelected: (T) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this@CarPlayHostActivity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        var selectedId = View.NO_ID
        for ((value, text) in options) {
            val button = RadioButton(this@CarPlayHostActivity).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 17f
                setTextColor(MENU_SECONDARY)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                tag = value
                isChecked = value == selected
            }
            if (value == selected) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val value = radioGroup.findViewById<RadioButton>(checkedId)?.tag as? T ?: return@setOnCheckedChangeListener
            onSelected(value)
        }
        addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun updateResolutionMenu() {
        resolutionValueView?.text = CarPlayDisplayScale.label(displayScaleTenths)
        val native = activeDisplaySize ?: currentActivitySize()
        val resolution = if (native == null) {
            getString(R.string.handshake_resolution_waiting_for_display)
        } else {
            val negotiated = CarPlayDisplayScale.apply(
                AirPlayDisplayConfig(
                    widthPixels = native.width,
                    heightPixels = native.height,
                    widthPhysicalMm = widthPhysicalMm,
                    fps = fps,
                ),
                displayScaleTenths,
            )
            "${getString(R.string.resolution_handshake_prefix)}${native.width} x ${native.height} -> " +
                "${negotiated.widthPixels} x ${negotiated.heightPixels}"
        }
        val transport = if (!hevcEnabled) {
            "H.264"
        } else {
            "HEVC (H.265, ${if (hevcSoftwareDecoderEnabled) "software" else "hardware"})"
        }
        val fullscreen = buildString {
            append(if (hideTopBar) getString(R.string.fullscreen_top_hidden) else getString(R.string.fullscreen_top_shown))
            append(", ")
            append(if (hideBottomBar) getString(R.string.fullscreen_bottom_hidden) else getString(R.string.fullscreen_bottom_shown))
        }
        resolutionPreviewView?.text = buildString {
            append(resolution).append('\n')
            append(getString(R.string.preview_identity)).append(normalizedManufacturer()).append(" / ")
                .append(normalizedModel()).append('\n')
            append(getString(R.string.preview_oem_label)).append(oemLabel.ifBlank { getString(R.string.preview_empty) }).append('\n')
            append(getString(R.string.preview_frame_rate)).append(fps).append(" fps\n")
            append(getString(R.string.preview_detected_maximum))
                .append(maximumDetectedWidthPixels).append(" x ")
                .append(maximumDetectedHeightPixels).append(" px\n")
            append(getString(R.string.preview_physical_reference))
                .append(
                    when (physicalSizeBasis) {
                        AirPlayPhysicalSizeBasis.WIDTH -> getString(R.string.basis_widest_width)
                        AirPlayPhysicalSizeBasis.HEIGHT -> getString(R.string.basis_longest_height)
                    },
                )
                .append(" = ").append(widthPhysicalMm).append(" mm\n")
            native?.let { size ->
                val physical = resolvePhysicalSize(size)
                append(getString(R.string.preview_carplay_physical_size))
                    .append(physical.widthMm).append(" x ")
                    .append(physical.heightMm).append(" mm\n")
            }
            append(getString(R.string.preview_driving_side)).append(if (rightHandDrive) getString(R.string.driving_side_right) else getString(R.string.driving_side_left)).append('\n')
            append(getString(R.string.preview_fullscreen)).append(fullscreen).append('\n')
            append(getString(R.string.preview_video_transport)).append(transport).append('\n')
            append(getString(R.string.preview_location_reporting))
                .append(if (locationReportingEnabled) getString(R.string.enabled_value) else getString(R.string.disabled_value))
                .append('\n')
            if (advancedAudioChannelMappingSupported) {
                append(getString(R.string.preview_audio_channel_mapping))
                    .append(if (advancedAudioChannelMapping) getString(R.string.mapping_aaos_buses) else getString(R.string.mapping_mobile_compatible))
                    .append('\n')
            }
            append(safeAreaSummary())
        }
    }

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {        val physical = resolvePhysicalSize(size)
        val knobPrimary = AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = size.width,
            heightPixels = size.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
            // Tell CarPlay to use its native knob/focus model on Android TV and other non-touch
            // hosts. Touch-capable head units remain touchscreen-primary.
            primaryInputDevice = if (knobPrimary) 3 else 1,
        )
        appendLog(
            "CarPlay primary input=${if (knobPrimary) "knob" else "touch"} " +
                "tv=${AndroidTvInputMode.isTelevision(this)} " +
                "touchscreen=${resources.configuration.touchscreen}",
        )
        val resolutionDisplay = CarPlayDisplayScale.apply(baseDisplay, displayScaleTenths)
        // The canvas is always the panel size. The "icon and text size" presets this used to enlarge
        // for were never exposed in this build and the value was never restored from disk, so the
        // whole enlargement path - decoder capability probing included - only ever ran on defaults.
        val display = resolutionDisplay.copy(
            safeArea = AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = resolutionDisplay.widthPixels,
                displayHeightPixels = resolutionDisplay.heightPixels,
            ),
            safeAreaDrawOutside = safeAreaDrawOutside,
        )
        // A fixed dock declares one whole-screen view area per edge, so the dock can move between
        // the driver's side and the bottom live, with updateViewArea on the event channel (tested
        // upstream: the iPhone moves at once, no reconnect). AUTOMATIC keeps the single
        // whole-display area this app has always declared and leaves the edge to the iPhone.
        val dock = CarPlayDock.load(this).also { sessionDock = it }
        sessionViewAreas = dock.edge?.let {
            listOf(
                AirPlayViewArea(
                    display.widthPixels, display.heightPixels,
                    dockEdge = AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE,
                ),
                AirPlayViewArea(
                    display.widthPixels, display.heightPixels,
                    dockEdge = AirPlayInfoPlist.DOCK_EDGE_BOTTOM,
                ),
            )
        }
        sessionViewAreaIndex =
            sessionViewAreas?.indexOfFirst { it.dockEdge == dock.edge }?.coerceAtLeast(0) ?: 0
        val declared = sessionViewAreas
            ?.let { display.copy(viewAreas = it, initialViewArea = sessionViewAreaIndex) }
            ?: display
        val requestSummary = "Display request surface=${size.width}x${size.height} " +
            "resolution=${displayScaleTenths * 10}% " +
            "canvas=${display.widthPixels}x${display.heightPixels} fps=$fps " +
            "codec=${if (hevcEnabled) "HEVC" else "H.264"} softwareHevc=$hevcSoftwareDecoderEnabled"
        val effectiveSummary = "Display effective canvas=${display.widthPixels}x${display.heightPixels} " +
            "physical=${physical.widthMm}x${physical.heightMm}mm safeArea=${display.safeArea} " +
            "drawOutside=${display.safeAreaDrawOutside} " +
            "dock=${dock.name.lowercase()} viewAreas=${sessionViewAreas?.size ?: 1}"
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.begin(
            this, requestSummary, "Decoder capability enlargement check not required", effectiveSummary,
        )
        appendLog(requestSummary)
        appendLog(effectiveSummary)
        return AirPlayConfig(
            deviceName = "DiPlay",
            deviceId = DiPlayBootstrap.deviceId(airPlayIdentity),
            btMac = DiPlayBluetooth.localAddress(this) ?: DiPlayBootstrap.deviceId(airPlayIdentity),
            sourceVersion = "950.7.1",
            main = declared,
            cluster = clusterDisplayConfig(),
            // The instruction-card display is advertised ONLY in FLY_CARD mode: there the card
            // window needs its own small stream. Every other mode keeps the single-cluster
            // /info declaration, so their behaviour is byte-for-byte unchanged.
            clusterCard = if (CarPlayClusterDisplay.isFlyCard(AirPlayPersistence.loadClusterContent(this))) {
                CarPlayClusterDisplay.cardConfig()
            } else {
                null
            },
            rightHandDrive = rightHandDrive,
            hevc = hevcEnabled,
            microphone = microphoneAvailable,
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon()),
            videoInCar = com.shilapi.xcertplay.hud.BydOutputSettings.videoWhileParkedActive(this),
            // Audio always stays on CarPlay, whatever the wheel-key switch says.
            //
            // Driver's design (2026-10-19): the head unit sends a steering-wheel media key twice -
            // once to this app, once over Bluetooth AVRCP to the phone - so with both paths live a
            // single press skipped two tracks. Dropping the audio route here (the earlier "B"
            // build) silenced CarPlay and moved guidance to the car's Bluetooth, which cost the
            // navigation duck and the volume-key routing for no reason: the duplicate is the key,
            // not the audio. Keeping the audio declared means music, ducking and guidance volume
            // all behave like factory CarPlay, and the switch only stops us forwarding the key.
            disableAudioOutput = false,
        )
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        // Shown in CarPlay's app list as the "back to the car" button.
        resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }

    private fun updateAirPlayIconPreview() {
        val preview = iconPreviewView ?: return
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)
        var customBitmap: Bitmap? = null
        if (custom != null) {
            customBitmap = BitmapFactory.decodeFile(custom.absolutePath)
            if (customBitmap == null) {
                AirPlayPersistence.clearCustomAirPlayIcon(this)
            }
        }
        val bitmap = customBitmap ?: BitmapFactory.decodeResource(resources, R.raw.placeholder_icon)
        preview.setImageBitmap(bitmap)
        iconStatusView?.text =
            if (customBitmap != null) getString(R.string.custom_1_1_icon) else getString(R.string.default_placeholder_icon)
    }

    private fun currentActivitySize(): DisplaySize? {
        val view = videoView
        if (view != null && view.width > 0 && view.height > 0) {
            return DisplaySize(view.width, view.height)
        }
        return activeDisplaySize
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun safeAreaSummary(): String {
        val size = currentActivitySize() ?: return getString(R.string.safe_area_waiting_for_activity_size)
        val mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        return if (mapping == null) {
            "${getString(R.string.safe_area_full_screen_at)}${size.width} x ${size.height}"
        } else {
            getString(
                R.string.safe_area_mapping_summary,
                mapping.width, mapping.height, mapping.left, mapping.top, size.width, size.height,
            )
        }
    }

    private fun updateSafeAreaSummary() {
        safeAreaSummaryView?.text = safeAreaSummary()
    }

    private fun openSafeAreaEditor() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area editor is unavailable before display layout")
            return
        }
        val editorView = safeAreaEditorView ?: return
        val initial = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
            ?: AirPlaySafeArea.default(size.width, size.height)
        safeAreaEditSize = size
        safeAreaEditorActive = true
        // Keep the current activity size; changing system bars here would remap the safe area.
        settingsMenu?.visibility = View.GONE
        safeAreaEditor?.visibility = View.VISIBLE
        editorView.setRect(initial, size.width, size.height)
        appendLog(
            "Safe area editor opened for ${size.width}x${size.height}; " +
                "drag the four boundaries",
        )
    }

    private fun closeSafeAreaEditor() {
        if (!safeAreaEditorActive) return
        safeAreaEditorActive = false
        safeAreaEditSize = null
        safeAreaEditor?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area editor closed")
    }

    private fun saveSafeAreaEditor() {
        val size = safeAreaEditSize ?: currentActivitySize() ?: return
        val rect = safeAreaEditorView?.currentRectForSource() ?: return
        AirPlayPersistence.saveSafeAreaRect(this, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        closeSafeAreaEditor()
    }

    private fun resetSafeAreaForCurrentSize() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area reset is unavailable before display layout")
            return
        }
        AirPlayPersistence.clearSafeAreaRect(this, size.width, size.height)
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area reset to full screen for ${size.width}x${size.height}")
    }

    private fun refreshDisplaySizeAfterLayout() {
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun createMediaSink(
        videoWidth: Int,
        videoHeight: Int,
        controllerGeneration: Int,
    ): AndroidMediaSink {
        // Capture this session's log: late decoder shutdown must not write into a new session.
        val diagnosticLog = sessionLog
        return AndroidMediaSink(
            surface = null,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(this),
            mediaChannel = AirPlayPersistence.loadMediaAudioChannel(this),
            navigationChannel = AirPlayPersistence.loadNavigationAudioChannel(this),
            navDuckPercent = { AirPlayPersistence.loadNavDuckPercent(this) },
            context = this,
            navigationStreamType = navigationStreamType,
            onScreenStreamActiveChanged = { type, active ->
                onScreenStreamStateChanged(controllerGeneration, type, active)
            },
            onScreenStreamOutputSize = { type, width, height ->
                if (type == SCREEN_TYPE_ALT_CARD) onClusterCardVideoSize(width, height)
            },
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(this),
            onAudioDiagnostic = { message ->
                if (message.startsWith("Microphone: ")) {
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                } else {
                    diagnosticLog?.append(formattedLogLine(message, System.currentTimeMillis()))
                }
            },
            onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,
            onNavigationGuidanceChanged = ::onNavigationGuidanceChanged,
        )
    }

    /**
     * While navigation guidance is playing, route the volume keys to the configured
     * navigation channel so the steering-wheel keys adjust guidance volume instead of
     * always adjusting media. Restores media routing when guidance stops.
     */
    @Volatile private var navigationGuidanceActive = false

    /**
     * Ends the guidance volume window.
     *
     * The flag comes from the navigation stream's start/stop edges, and on this head unit that
     * stream can stay open far longer than a prompt - the driver was left unable to raise the
     * music for minutes because every volume key was routed to guidance, which also made the
     * guidance volume itself drift down (field report 2026-10-20). A prompt is seconds long, so
     * the routing is only trusted for this window and then handed back to media on its own.
     */
    private var guidanceWindowArmed = false

    private val endGuidanceVolumeWindow = Runnable {
        if (isDestroyed || isFinishing) return@Runnable
        guidanceWindowArmed = false
        if (!navigationGuidanceActive) return@Runnable
        navigationGuidanceActive = false
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        appendLog("Volume control stream: media（导航音量窗口结束，恢复媒体音量键）")
    }

    private fun onNavigationGuidanceChanged(active: Boolean) {
        navigationGuidanceActive = active
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            val navChannel = AirPlayPersistence.loadNavigationAudioChannel(this)
            if (active && navChannel > 0) {
                setVolumeControlStream(navChannel)
                appendLog("Volume control stream: navigation($navChannel)")
                // Armed once per prompt burst: restarting the window on every start edge let a
                // stream that keeps flapping hold the keys on guidance indefinitely.
                if (!guidanceWindowArmed) {
                    guidanceWindowArmed = true
                    mainHandler.postDelayed(endGuidanceVolumeWindow, GUIDANCE_VOLUME_WINDOW_MS)
                }
            } else {
                guidanceWindowArmed = false
                mainHandler.removeCallbacks(endGuidanceVolumeWindow)
                setVolumeControlStream(AudioManager.STREAM_MUSIC)
                appendLog("Volume control stream: media")
            }
        }
    }

    /**
     * Media route snapshot taken when a session comes up.
     *
     * Written for the "first connection leaves the car's media volume at 0" report (2026-10-18),
     * which the first samples disproved: the head unit sat at streamMusic=15/15 with musicActive=
     * false, i.e. the volume was already maximum and the phone simply kept its music inside
     * CarPlay. Kept because it is the cheapest way to tell "no audio is flowing" from "audio is
     * flowing but quiet" the next time the cabin goes silent.
     */
    private fun watchMediaAudioRoute() {
        val enabled = AirPlayPersistence.loadMediaViaCarBluetooth(this)
        appendLog(
            "媒体路由: 方向盘媒体键交给车机蓝牙=$enabled " +
                "CarPlay不宣告音频=$enabled",
        )
        if (!enabled) return
        val am = getSystemService(AudioManager::class.java) ?: return
        for (delay in listOf(0L, 2_000L, 6_000L)) {
            mainHandler.postDelayed({
                if (isDestroyed || isFinishing) return@postDelayed
                val max = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
                    .getOrDefault(-1)
                val cur = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }
                    .getOrDefault(-1)
                appendLog(
                    "媒体路由 (+${delay}ms): streamMusic=$cur/$max " +
                        "musicActive=${runCatching { am.isMusicActive }.getOrDefault(false)} " +
                        "a2dpOn=${runCatching { am.isBluetoothA2dpOn }.getOrDefault(false)} " +
                        "mode=${runCatching { am.mode }.getOrDefault(-1)}",
                )
            }, delay)
        }
    }

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
        )

    /**
     * "连接后自动投屏": fired once per session from onSessionActive. Delays a couple of
     * seconds so the head-unit's incall service and the projection display are ready, then
     * projects to the dashboard with the content selected in "仪表盘内容" (loadClusterMirrorMain:
     * whole-screen mirror or navigation stream). Honours the cluster projection master switch
     * and never re-fires for the same session; a later three-finger swipe still works normally.
     */
    private fun maybeAutoProjectOnConnect() {
        // "自动飞屏" master switch: when unchecked, no auto projection at all.
        if (!AirPlayPersistence.loadAutoFlyScreen(this)) {
            appendLog("Auto fly: 未勾选“自动飞屏”，跳过自动投屏")
            return
        }
        if (autoProjectTriggered) {
            appendLog("Auto project: 本会话已自动投屏过，跳过")
            return
        }
        autoProjectTriggered = true
        val mirror = currentClusterMirrorMode()
        appendLog("Auto project: 连接成功，稍后自动投屏 mirror=$mirror")
        val token = autoProjectToken
        // One second of settle, then the readiness gate decides. It used to be a flat 5 s, which the
        // gate had already superseded: the fixed delay was what raced the head-unit bring-up, and
        // the gate's own conditions (projection window bound, stock Amap up) are the real readiness
        // signal. Leaving both in place meant every connect paid 5 s of pure latency on top - the
        // field report "自动投屏需要很长一段时间". If the preconditions are missing the gate waits
        // (bounded 25 s) instead, so this is not a return to the race it was written for.
        mainHandler.postDelayed({
            if (token != autoProjectToken) return@postDelayed
            if (shuttingDown.get() || handshakeResetInProgress || isFinishing || isDestroyed) {
                appendLog("Auto project: 取消（关闭/重连中）")
                return@postDelayed
            }
            if (activeAirPlaySession == null) {
                appendLog("Auto project: 取消（会话已断开）")
                return@postDelayed
            }
            awaitAutoProjectReady(token, mirror, waitedMs = 0L)
        }, autoProjectSettleMs)
    }

    /**
     * Cold-start readiness gate. The fixed "5 s after the session went active" trigger raced the
     * head-unit bring-up: on a cold boot the incall service may still be starting (the bridge now
     * waits for its binder itself) and Feiyu OS only keeps the cluster projection channel alive
     * while the stock car Amap runs - firing before that made the first activation silently
     * dropped, which is the field-reported "cold-start auto projection occasionally fails, a
     * later manual swipe works". Wait (bounded) for the projection window to be bound; then
     * project. If it never arrives we project anyway (best effort - never worse than before).
     * The stock-Amap wait is gone: field testing (2026-10-18) showed the projection works with
     * the head unit's own Amap not running, so there is nothing left to wait for.
     */
    private fun awaitAutoProjectReady(token: Any, mirror: Boolean, waitedMs: Long) {
        if (token != autoProjectToken) return
        if (shuttingDown.get() || handshakeResetInProgress || isFinishing || isDestroyed) return
        if (activeAirPlaySession == null) {
            appendLog("Auto project: 取消（会话已断开）")
            return
        }
        if (!AirPlayPersistence.loadAutoFlyScreen(this)) {
            appendLog("Auto project: 取消（自动飞屏已取消勾选）")
            return
        }
        val windowReady = clusterPresentation != null
        if (windowReady) {
            if (waitedMs > 0L) {
                appendLog("Auto project: 已就绪（等待 ${waitedMs}ms 窗口=$windowReady）")
            }
            autoProjectTo(mirror, "auto")
            return
        }
        if (waitedMs >= autoProjectReadyTimeoutMs) {
            // Preconditions never arrived, so this attempt may well be dropped. The retry ladder
            // re-asserts silently rather than re-sending the announcing sequence, and a genuinely
            // lost activation is recovered by the driver's swipe.
            appendLog(
                "Auto project: 等待就绪超时 ${waitedMs}ms（窗口=$windowReady），仍尝试投屏",
            )
            autoProjectTo(mirror, "auto")
            return
        }
        if (waitedMs == 0L) {
            appendLog("Auto project: 等待车机就绪（窗口=$windowReady）")
        }
        // Poll fast while the preconditions are expected to arrive any moment; after that the
        // remaining wait is a genuinely slow head unit, where a tighter poll only burns wakeups.
        val pollMs = if (waitedMs < autoProjectReadyFastWindowMs) {
            autoProjectReadyFastPollMs
        } else {
            autoProjectReadyPollMs
        }
        mainHandler.postDelayed(
            { awaitAutoProjectReady(token, mirror, waitedMs + pollMs) },
            pollMs,
        )
    }

    /** Projects now at the given mode (0=whole mirror, 1=nav stream), reusing the swipe path. */
    private fun autoProjectTo(mirror: Boolean, direction: String) {
        // Re-check the "自动飞屏" master switch: the 5 s entry delay means the user may have
        // toggled it off between the session-activation gate and this delayed execution.
        if (!AirPlayPersistence.loadAutoFlyScreen(this)) {
            appendLog("Auto project: 自动飞屏已取消勾选，取消自动投屏")
            return
        }
        if (shuttingDown.get() || handshakeResetInProgress) {
            appendLog("Auto project: skipped shuttingDown=${shuttingDown.get()} handshakeReset=$handshakeResetInProgress")
            return
        }
        if (clusterSwipePosition == (if (mirror) 0 else 1) && clusterActivationAttempted) {
            // Already in the requested mode this session: nothing to do.
            appendLog("Auto project: 已在目标模式 mirror=$mirror，跳过")
            return
        }
        clusterSwipePosition = if (mirror) 0 else 1
        appendLog("Auto project: 请求仪表盘显示${if (mirror) "整机界面" else "导航流"} (pos=$clusterSwipePosition)")
        // Connection time only shows or hides the window that was built for this session. The
        // start-up watcher already sent the sequence if the channel was closed, and it keeps
        // sampling: if the channel is still not open now, this session simply has no dashboard.
        // Sending the sequence from here is what made every reconnect announce again.
        if (!watcherChannelOpen && !InCallClusterBridge.reportsProjecting()) {
            clusterPresentation?.setMapVisible(false)
            appendLog("Auto project: 投屏通道未开，本次会话不投屏")
            return
        }
        if (clusterPresentation == null) {
            runCatching { ensureClusterPresentation() }
        }
        clusterPresentation?.setMapVisible(true)
        applyClusterMode(mirror, direction)
    }

    /**
     * True while an auto-projection is still working through its retry ladder.
     *
     * The full open sequence is the one thing that makes incall announce "全屏导航已开启", so a
     * manual swipe that lands inside the ladder must cancel it (a swipe is proof the driver can
     * see the dashboard, i.e. the projection is up and only the announcement is left). The flag
     * is set when the ladder is armed and cleared on its last attempt, on any manual swipe, and
     * on session end / restart.
     */
    private var autoProjectLadderActive = false

    /**
     * Auto-project retry ladder.
     *
     * There is no reliable success signal to verify against: the projection display is registered
     * before activation (so "window bound" proves nothing) and both streams are decoded into our
     * own surfaces regardless of what the head unit does (so "stream active" proves nothing
     * either - the previous verify always passed for exactly this reason and never retried).
     * What DOES work is the driver's manual swipe, which simply re-sends the sequence later. So
     * the ladder automates that: a light ext-screen re-assert first (it cannot re-trigger the
     * incall projection voice), then a full open sequence, both bounded, and the whole ladder is
     * cancelled the instant the driver takes over (a manual swipe resets autoProjectToken) or the
     * session/switch changes. Can be turned off with "自动投屏重试" in the dashboard settings.
     */
    private fun scheduleAutoProjectRetry(mirror: Boolean, attempt: Int) {
        val delay = autoProjectRetryDelaysMs.getOrNull(attempt) ?: run {
            autoProjectLadderActive = false
            return
        }
        autoProjectLadderActive = true
        val token = autoProjectToken
        mainHandler.postDelayed({
            if (token != autoProjectToken) {
                autoProjectLadderActive = false
                return@postDelayed
            }
            if (shuttingDown.get() || handshakeResetInProgress || isFinishing || isDestroyed) {
                autoProjectLadderActive = false
                return@postDelayed
            }
            if (activeAirPlaySession == null) {
                autoProjectLadderActive = false
                appendLog("Auto project: 重试取消（会话已断开）")
                return@postDelayed
            }
            // The ladder no longer sends anything: the start-up watcher owns activation, and every
            // extra signal here is what announced "全屏镜像/导航已开启" more than once per session.
            // All it does now is notice a channel that opened late and show the window.
            val open = watcherChannelOpen ||
                runCatching { InCallClusterBridge.reportsProjecting() }.getOrDefault(false)
            if (open) {
                autoProjectLadderActive = false
                if (clusterPresentation == null) runCatching { ensureClusterPresentation() }
                clusterPresentation?.setMapVisible(true)
                applyClusterMode(mirror, "auto")
                appendLog("Auto project: 投屏通道已开，显示窗口（不发序列）")
                return@postDelayed
            }
            scheduleAutoProjectRetry(mirror, attempt + 1)
        }, delay)
    }

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            private val diagnosticLog = sessionLog

            /**
             * The phone's own audio-interruption commands. iOS sends these when one of its sounds
             * (a camera shutter, a notification, Siri) should be heard over the car's playback,
             * and the accessory is expected to lower its audio for the requested window. They used
             * to be ignored, so the only thing that ever ducked our music was the head unit's focus
             * callback - and with no matching restore from it, taking a photo could leave the music
             * quiet for the rest of the drive.
             */
            override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
                when (type) {
                    "duckAudio" -> {
                        val durationMs = (params["durationMs"] as? Number)?.toLong() ?: 2_000L
                        appendLog("CarPlay audio: duckAudio durationMs=$durationMs")
                        sink?.duckMediaFor(durationMs)
                    }
                    "unduckAudio" -> {
                        appendLog("CarPlay audio: unduckAudio")
                        sink?.unduckMedia()
                    }
                }
            }

            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    mainHandler.removeCallbacks(airPlayStallHint)
                    activeAirPlaySession = session
                    CarPlayBackgroundSession.active = true
                    // A new session is NOT proof that the dashboard picture is back: iAP2 comes up
                    // seconds before any video flows, and a reconnect that never delivers frames
                    // left the panel frozen on the previous picture. Restart the hand-back timer
                    // instead of cancelling it; the 111 stream coming back cancels it for real.
                    mainHandler.removeCallbacks(hideDashboardAfterDisconnect)
                    if (clusterProjectionStarted) {
                        mainHandler.postDelayed(hideDashboardAfterDisconnect, disconnectHideDelayMs)
                    }
                    reconnectAttempts = 0
                    logThemeState(ThemeModeDiagnostics.Source.SESSION_ACTIVE, resources.configuration)
                    syncAirPlayDarkMode(ThemeModeDiagnostics.Source.SESSION_ACTIVE)
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                    // A new connection is a fresh start: the hand-back from the previous session
                    // no longer applies.
                    userHandedBackProjection = false
                    lastKnownProjecting = false
                    watchMediaAudioRoute()
                    maybeAutoProjectOnConnect()
                    // A connection made while navigation is already running has no transition to
                    // react to: seed the auto-switch once so it still lands on nav-only.
                    mainHandler.postDelayed({
                        if (!shuttingDown.get() && activeAirPlaySession != null &&
                            com.shilapi.xcertplay.hud.BydNavigationOutputs.navigationActive()
                        ) {
                            onNavigationStateEvent(true)
                        }
                    }, 1_500L)
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    if (activeAirPlaySession === session) activeAirPlaySession = null
                    CarPlayBackgroundSession.active = false
                    // A new session is a new connection: allow auto-project to fire again after
                    // any reconnect. restartCarPlay() also resets these, but some reconnect paths
                    // early-return before that reset (handshakeReset/menuOpen), which would leave
                    // autoProjectTriggered=true forever and silently disable auto-project on every
                    // later reconnect. Resetting here covers all paths unconditionally. The swipe
                    // position and activation flag are reset too: otherwise a reconnect while the
                    // old position was 0/1 makes the next auto-project hit the "already in target
                    // mode" branch and skip re-activation while the old window is dead (observed
                    // as "reconnect stuck on nav stream only").
                    autoProjectTriggered = false
                    autoProjectToken = Any()
                    autoProjectLadderActive = false
                    clusterSwipePosition = -1
                    clusterPositionFromAuto = false
                    clusterActivationAttempted = false
                    // A released ext-screen must not survive a reconnect: the next open re-runs
                    // the full sequence anyway, and a stale flag would only force it again.
                    clusterExtScreenReleased = false
                    // Hand the dashboard back if the phone does not return: see the runnable.
                    mainHandler.removeCallbacks(hideDashboardAfterDisconnect)
                    mainHandler.postDelayed(hideDashboardAfterDisconnect, disconnectHideDelayMs)
                    // Clear the stream set unconditionally too: the "already streaming" check
                    // (clusterStreamActive) must never inherit types from the ended session. If
                    // the clear happened only after the menuOpen early-return, a session that
                    // ended while the settings menu was open would leave stale stream types,
                    // making a later nav-only projection think 111 was already active.
                    activeScreenStreamTypes.clear()
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    setConnectionStage(getString(R.string.carplay_session_ended_reconnecting))
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    // Clear the stream set unconditionally (same reason as onSessionEnded):
                    // a transport error while the settings menu is open must not leave stale
                    // stream types for the next session.
                    activeScreenStreamTypes.clear()
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    setConnectionStage(getString(R.string.transport_error_reconnecting))
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onDebugLog(message: String) {
                if (DiagnosticRedactor.redact(message) == null) return
                if (message.startsWith(CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX + " ")) {
                    // Retain old-controller teardown evidence without accepting its UI/session state.
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                    return
                }
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    DisplayDiagnosticSnapshot.record(this@CarPlayHostActivity, displayDiagnosticAttempt, message)
                    if (menuOpen) return@runOnUiThread
                    if (message.startsWith(PROTOCOL_TRACE_PREFIX)) {
                        appendFileLog(message)
                    } else {
                        appendLog(message)
                    }
                }
            }
        }

    /**
     * Shown when the phone has been told to start CarPlay but never connects. Nothing on our side
     * can fix that state - it lives on the iPhone - so the panel names the one step that does.
     */
    private val airPlayStallHint = Runnable {
        if (activeAirPlaySession == null && !isFinishing && !isDestroyed) {
            setConnectionStage("The iPhone did not start AirPlay")
        }
    }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = { status ->
        if (!menuOpen && controllerGeneration == restartGeneration) {
            updateHotspotStatus(status)
            val description = status.describe()
            setConnectionStage(description)
            when (status) {
                is CarPlayStatus.Failed -> {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFailureToastElapsed > 20_000L) {
                        lastFailureToastElapsed = now
                        val hint = if (AirPlayPersistence.loadWirelessEnabled(this)) {
                            getString(R.string.connection_failed_retrying, description)
                        } else {
                            getString(R.string.usb_connection_failed_hint, description)
                        }
                        Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
                    }
                    if (status.wifiResetRequired) {
                        wifiRecoveryButton?.visibility = View.VISIBLE
                    } else {
                        wifiRecoveryButton?.visibility = View.GONE
                        reconnectAfterLoss(description)
                    }
                }
                CarPlayStatus.WaitingForIphone -> {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUsbWaitToastElapsed > 60_000L) {
                        lastUsbWaitToastElapsed = now
                        Toast.makeText(this, R.string.usb_waiting_for_iphone_hint, Toast.LENGTH_LONG).show()
                    }
                }
                // The iPhone accepted carplay-start-session but never opened AirPlay. Field case
                // 2026-10-19: the accessory entry on the phone was stale and only "forget this
                // vehicle" fixed it. Say so instead of leaving the panel on "opening CarPlay".
                CarPlayStatus.RunningWireless -> {
                    mainHandler.removeCallbacks(airPlayStallHint)
                    mainHandler.postDelayed(airPlayStallHint, AIRPLAY_STALL_HINT_MILLIS)
                }
                CarPlayStatus.WirelessActive -> mainHandler.removeCallbacks(airPlayStallHint)
                else -> Unit
            }
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.currentAttempt(this)
        controller = snapshot.controller
        sink = snapshot.sink
        sessionDisplay = snapshot.display
        sessionDock = snapshot.display.viewAreas
            ?.getOrNull(snapshot.display.viewAreaIndex)?.dockEdge
            ?.let { edge -> CarPlayDock.entries.firstOrNull { it.edge == edge } }
        sessionViewAreas = snapshot.display.viewAreas
        sessionViewAreaIndex = snapshot.display.viewAreaIndex
        MapMirrors.reapply()
        CarPlayBackgroundSession.store(snapshot.controller, snapshot.sink, snapshot.width, snapshot.height,
            this, snapshot.display) { completion ->
            runOnUiThread {
                // Hand the dashboard back before shutting down, not after. shutdown() only
                // closes the controller and finish() then destroys the presentation, so without
                // this the ext-screen stays in our hands and the cluster goes black instead of
                // returning to the car's own dials. A deliberate disconnect has nothing to wait
                // for, so it bypasses the delay that exists for reconnecting sessions.
                releaseDashboardForDisconnect(immediate = true)
                mainHandler.removeCallbacks(hideDashboardAfterDisconnect)
                shutdown(false, "DiPlay disconnect", completion)
                finish()
            }
        }
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
        }
        videoView?.let { updateVideoLayout(it.width, it.height) }
        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(restartGeneration, type, active)
        }
        snapshot.sink.setScreenStreamOutputSizeListener { type, width, height ->
            if (type == SCREEN_TYPE_ALT_CARD) onClusterCardVideoSize(width, height)
        }
        currentSurface?.let(::attachSurface)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                getString(R.string.carplay_service_already_running)
            } else {
                getString(R.string.carplay_session_already_running)
            },
        )
        updateDebugOverlays()
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || controller != null) return
        val controllerGeneration = restartGeneration
        val config = createRuntimeConfig()
        val airPlayConfig = createAirPlayConfig(size)
        val locationProvider: Iap2LocationProvider? =
            when {
                !config.locationReportingEnabled -> null
                config.identification.vehicleSpeedEnabled -> VehicleSpeedLocationProvider(
                    AndroidCarPlayLocationProvider(this),
                    com.shilapi.xcertplay.hud.BydNavigationOutputs.wheelSpeed(applicationContext),
                )
                else -> AndroidCarPlayLocationProvider(this)
            }
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${CarPlayDisplayScale.label(displayScaleTenths)}) " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"}" +
                "${if (config.identification.vehicleSpeedEnabled) "+wheel-speed" else ""} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${CarPlayDisplayScale.label(displayScaleTenths)} " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val renderer = createMediaSink(
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            controllerGeneration = controllerGeneration,
        )
        sink = renderer
        currentSurface?.let(::attachSurface)
        // Same mode decision as attachSurface: a reconnect must keep the dashboard on the
        // user-selected mode. Binding the cluster surface unconditionally to the navigation
        // stream here overwrote the mirror binding and froze the dashboard to nav-only.
        if (DiLink51ClusterLayout.supported()) {
            clusterSurface?.let { surface ->
                if (currentClusterMirrorMode()) {
                    renderer.setMirrorSurface(SCREEN_TYPE_MAIN, CLUSTER_MAIN_MIRROR, surface)
                } else {
                    renderer.setSurface(SCREEN_TYPE_ALT, surface)
                }
            }
        } else {
            // Generic path: re-bind BOTH resident surfaces so 110 and 111 stay active in incall
            // (this is what suppresses the "全屏导航已开启" announcement on mode switches).
            mirrorClusterSurface?.let { renderer.setMirrorSurface(SCREEN_TYPE_MAIN, CLUSTER_MAIN_MIRROR, it) }
            navClusterSurface?.let { renderer.setSurface(SCREEN_TYPE_ALT, it) }
            // FLY_CARD: the card window's stream is resident too.
            cardClusterSurface?.let { renderer.setSurface(SCREEN_TYPE_ALT_CARD, it) }
        }
        MapMirrors.reapply()
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
            locationProvider = locationProvider,
            vehicleStatusProvider = if (com.shilapi.xcertplay.hud.BydOutputSettings.batteryToIphoneActive(this)) {
                com.shilapi.xcertplay.hud.BydNavigationOutputs.batteryStatus(applicationContext)
            } else {
                null
            },
        )
        controller = next
        CarPlayMediaKeys.attach(this, next)
        // Album art for the dashboard song card. CarPlay's artwork transfers already land in
        // CarPlayMediaKeys, so the card just mirrors whatever is current there.
        CarPlayMediaKeys.setArtworkListener { bitmap ->
            (clusterLayers.values + listOfNotNull(clusterPresentation)).distinct()
                .forEach { it.setSongArtwork(bitmap) }
        }
        if (airPlayConfig.videoInCar) CarPlayVideo.attach(this, next)
        val display = CarPlaySessionDisplay(airPlayConfig.main.widthPixels, airPlayConfig.main.heightPixels,
            displayRotation(), hideTopBar, hideBottomBar, size.width, size.height,
            airPlayConfig.main.viewAreas, airPlayConfig.main.initialViewArea)
        sessionDisplay = display
        videoView?.let { updateVideoLayout(it.width, it.height) }
        CarPlayBackgroundSession.store(next, renderer, size.width, size.height, this, display) { completion ->
            runOnUiThread {
                // Hand the dashboard back before shutting down, not after. shutdown() only
                // closes the controller and finish() then destroys the presentation, so without
                // this the ext-screen stays in our hands and the cluster keeps showing the last
                // decoded frame instead of returning to the car's own dials (field report
                // 2026-10-18: the home screen's disconnect button left the picture frozen on
                // the panel). A deliberate disconnect has nothing to wait for, so it bypasses
                // the delay that exists for reconnecting sessions.
                releaseDashboardForDisconnect(immediate = true)
                mainHandler.removeCallbacks(hideDashboardAfterDisconnect)
                shutdown(terminateProcess = false, reason = "DiPlay disconnect", completion = completion)
                finish()
            }
        }
        // In-place hot restart for cluster settings changes: keep the projection window and
        // both resident stream surfaces (110/111), so the head unit never re-announces
        // "全屏导航已开启" when a setting is applied (restartCarPlay does not dismiss the window).
        CarPlayBackgroundSession.setRestart {
            runOnUiThread {
                if (!shuttingDown.get() && !menuOpen && !handshakeResetInProgress) {
                    restartCarPlay("Cluster settings changed")
                }
            }
        }
        // The settings page moves the dock live while this session runs; like the restart hook,
        // this only reads session state and writes to the event channel.
        CarPlayBackgroundSession.setDockAction { next -> applyCarPlayDock(next) }
        try {
            ContextCompat.startForegroundService(this, Intent(this, DiPlaySessionService::class.java))
            next.start()
        } catch (error: RuntimeException) {
            appendLog("Connection could not start: ${error.javaClass.simpleName}")
            shutdown(false, "foreground service could not start")
            setConnectionStage(getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per))
        }
    }

    private fun refreshConfiguration(
        newConfig: Configuration = resources.configuration,
        source: ThemeModeDiagnostics.Source,
    ) {
        if (lastConfiguration == newConfig) {
            logThemeState(source, newConfig)
            return
        }
        // resources.configuration is mutated in place, so keep a copy to compare against.
        lastConfiguration = Configuration(newConfig)
        nightModeDiagnosticSource = source
        try {
            nightModeOrNull(newConfig.uiMode)?.let { nightModeController.systemChanged(it) }
        } finally {
            nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
        }
        logThemeState(source, newConfig)
    }

    private fun logThemeState(source: ThemeModeDiagnostics.Source, configuration: Configuration) {
        themeDiagnostics.observe(
            source, configuration.uiMode, darkMode, activeAirPlaySession != null, SystemClock.elapsedRealtime(),
        )?.let(::appendLog)
    }

    private fun syncAirPlayDarkMode(source: ThemeModeDiagnostics.Source) {
        val session = activeAirPlaySession
        val night = darkMode
        if (session == null) {
            appendLog("THEME_DIAGNOSTIC request source=${source.label} applied=${if (night) "dark" else "light"} sessionActive=false")
            return
        }
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                // A successful write does not prove the iPhone changed its appearance.
                appendLog(
                    "THEME_DIAGNOSTIC send source=${source.label} applied=${if (night) "dark" else "light"} commandWritten=$sent",
                )
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                val failureClass = error.javaClass.simpleName.take(64)
                    .filter { it.isLetterOrDigit() || it == '_' || it == '$' }
                    .ifEmpty { "unknown" }
                appendLog("THEME_DIAGNOSTIC send source=${source.label} failureClass=$failureClass")
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        if (!File(filesDir, AUDIO_CAPTURE_MARKER).isFile) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == pendingDisplaySize) return
        mainHandler.removeCallbacks(applyDisplaySize)
        if (size == activeDisplaySize && !displayLayoutChanged()) {
            pendingDisplaySize = null
            // A rotation back can cancel the last pending resize after teardown has finished.
            maybeStartCarPlay()
            return
        }
        pendingDisplaySize = size
        mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun applyDisplaySize(size: DisplaySize) {
        val display = sessionDisplay
        val layoutChanged = displayLayoutChanged()
        if (shuttingDown.get()) return
        if (size == activeDisplaySize && !layoutChanged) {
            // Teardown may have removed the session that made this same-size rotation pending.
            maybeStartCarPlay()
            return
        }
        val previous = activeDisplaySize
        activeDisplaySize = size
        recordDetectedMaximum(size)
        updateResolutionMenu()
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else if (controller == null && display == null) {
            appendLog(
                "Display updated before CarPlay startup: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
            maybeStartCarPlay()
        } else if (display != null && !layoutChanged &&
            size.width <= display.windowWidth && size.height <= display.windowHeight) {
            // Keep camera shrink/restore cycles within the original window connected. If the
            // session started in a camera window, growth beyond it needs a full-size canvas.
            val message = "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}; " +
                "keeping CarPlay session canvas=${display.width}x${display.height}"
            appendLog(message)
            Log.i(TAG, message)
            videoView?.let { updateVideoLayout(it.width, it.height) }
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = videoView?.display?.rotation ?: windowManager.defaultDisplay.rotation

    private fun displayLayoutChanged(): Boolean {
        val display = sessionDisplay ?: return false
        // A narrow window can become taller than it is wide without the screen rotating.
        return display.rotation != displayRotation() ||
            display.hideTopBar != hideTopBar || display.hideBottomBar != hideBottomBar
    }

    private fun contentRect(viewWidth: Int, viewHeight: Int): CarPlayVideoLayout {
        val display = sessionDisplay ?: return CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        return CarPlayVideoLayout.fit(display.width, display.height, viewWidth, viewHeight)
    }

    private fun updateVideoLayout(viewWidth: Int, viewHeight: Int) {
        val view = videoView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val content = contentRect(viewWidth, viewHeight)
        view.setTransform(Matrix().apply {
            setScale(content.width / viewWidth, content.height / viewHeight)
            postTranslate(content.left, content.top)
        })
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (shuttingDown.get()) return
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) {
            if (!adoptBackgroundSession()) mainHandler.postDelayed({ maybeStartCarPlay() }, 500)
            return
        }
        if (controller == null && adoptBackgroundSession()) return
        val size = activeDisplaySize ?: run {
            appendLog("Startup: waiting for display size before CarPlay start")
            return
        }
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            menuOpen ||
            handshakeResetInProgress ||
            pendingDisplaySize != null ||
            controller != null
        ) {
            if (!transportReady) appendLog("Startup: transport not ready (vpn=$vpnReady wireless=$wirelessEnabled)")
            if (!locationReady) appendLog("Startup: location not ready")
            if (!microphonePermissionResolved) appendLog("Startup: microphone not resolved")
            return
        }
        startCarPlay(size)
    }

    private fun reconnectAfterLoss(reason: String) {
        // Only the owner of the background session may restart it. When no session has ever
        // been stored (owner == null), this activity is the only live host, so it may proceed.
        if (!CarPlayBackgroundSession.isOwner(this) && CarPlayBackgroundSession.hasSession()) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        if (reconnectScheduled) return
        reconnectScheduled = true
        val generation = restartGeneration
        val delayMillis = if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            // Cold-start field logs show the iPhone's iAP2 RFCOMM service takes ~15-20 s to
            // register after power-up: an 8 s connect timeout fails fast, then a short backoff
            // (2 s, then 4 s, capped at 8 s) retries densely so the session lands on the warmed-up
            // stack within a couple of seconds of it becoming ready, instead of stalling on a
            // 16-30 s gap.
            (RECONNECT_DELAY_MILLIS * (1L shl reconnectAttempts.coerceAtMost(1))).coerceAtMost(8_000L)
        }
        reconnectAttempts += 1
        appendLog("$reason; retrying in ${delayMillis}ms")
        mainHandler.postDelayed(
            {
                reconnectScheduled = false
                if (
                    shuttingDown.get() ||
                    menuOpen ||
                    handshakeResetInProgress ||
                    generation != restartGeneration
                ) {
                    return@postDelayed
                }
                restartCarPlay("Reconnecting after $reason")
            },
            delayMillis,
        )
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) {
        if (!CarPlayBackgroundSession.isOwner(this) && CarPlayBackgroundSession.hasSession()) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        // The cluster picture source is fixed when the dashboard window is built (CarPlay only
        // learns the URL through the window's display config), and the restart below deliberately
        // keeps that window alive so stream 111 is not re-activated. So a source change would keep
        // streaming the old URL - the driver's "switching the option did nothing until I
        // reconnected by hand" (field 2026-10-24). Drop the window here when the saved source no
        // longer matches the one it was built with, and the rebuilt session asks for the new URL.
        val savedContent = AirPlayPersistence.loadClusterContent(this)
        if (clusterWindowContent != null && clusterWindowContent != savedContent) {
            appendLog(
                "Cluster: 画面来源已改为 $savedContent，重建仪表窗口以请求新地址" +
                    "（原 $clusterWindowContent）",
            )
            dismissClusterPresentation()
        }
        clusterWindowContent = null
        // A full restart tears down the CarPlay stack but MUST keep the cluster projection
        // window: on the generic (Chang'an) path both stream surfaces (110 mirror + 111 nav)
        // stay bound to the incall sink while the window lives, and the rebuilt session's
        // attachSurface() re-binds them to the new decoder. Dismissing the window here would
        // unbind stream 111; the rebuilt window re-attaches it, which re-activates 111 and
        // makes the head unit announce "全屏导航已开启" on every reconnect (field report
        // 10-08). The swipe position and activation flags are still reset below.
        clusterSwipePosition = -1
        clusterPositionFromAuto = false
        clusterActivationAttempted = false
        clusterExtScreenReleased = false
        autoProjectTriggered = false
        autoProjectToken = Any()   // cancel any pending auto-project delayed task
        autoProjectLadderActive = false
        val size = activeDisplaySize
            ?: CarPlayBackgroundSession.snapshot()?.let { DisplaySize(it.width, it.height) }
            ?: run {
                // No confirmed display size yet (e.g. disconnect during bring-up). Stay on the
                // connection stage; the display-detection callback will start CarPlay again.
                appendLog("$reason; display size unknown, waiting for display detection")
                setConnectionStage(getString(R.string.waiting_for_display_detection, reason))
                return
            }
        appendLog(reason)
        activeScreenStreamTypes.clear()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        handshakeResetInProgress = true
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController, keepOwner = true)
        controller = null
        sink = null
        sessionDisplay = null
        val diagnosticLog = sessionLog
        teardownExecutor.execute {
            val started = System.nanoTime()
            oldController?.close()
            val completed = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            AsyncDiagnosticLog.append(
                diagnosticLog,
                "${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} generation=$generation " +
                    "restart teardownWaitCompleted=$completed " +
                    "elapsedMs=${((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0)}",
            )
            oldSink?.close()
            runOnUiThread {
                if (!shuttingDown.get() && generation == restartGeneration) {
                    handshakeResetInProgress = false
                    // The display can rotate again while the old stack is closing. Use the
                    // latest accepted size, and wait for any pending resize to settle first.
                    maybeStartCarPlay()
                }
            }
        }
    }

    /**
     * Moves the running session's dock to [next] when the session declared an area for that edge
     * (a change between the two fixed edges); false when it cannot be done live, which is the
     * caller's cue to reconnect. The session keeps the selection until its event channel can
     * carry it, so a false only means the channel is not up yet.
     */
    private fun applyCarPlayDock(next: CarPlayDock): Boolean {
        val areas = sessionViewAreas ?: return false
        val target = areas.indexOfFirst { it.dockEdge == next.edge }
        if (target < 0) return false
        if (target == sessionViewAreaIndex) return true
        val sent = controller?.showViewArea(target) ?: false
        if (sent) {
            sessionViewAreaIndex = target
            appendLog("CarPlay dock moved to ${next.name.lowercase()} (view area $target)")
        } else {
            appendLog("CarPlay dock move to ${next.name.lowercase()} not sent yet; " +
                "the session sends it when the event channel is ready")
        }
        return sent
    }

    private fun showDiPlayHome(page: String = "home") {
        controller?.sendTouch(emptyList())
        // The settings page runs in its own activity, which puts the CarPlay task in the
        // background; hold the session across that trip so the driver gets settings, not a
        // reconnect panel.
        CarPlayBackgroundSession.holdForSettings()
        startActivity(Intent(this, DiPlayActivity::class.java)
            .putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun openSettingsMenu() = showDiPlayHome("settings")

    private fun saveSettingsAndReconnect() {
        if (!menuOpen) return
        if (!validateMfiSettings()) return
        if (!validateManualHotspotSettings()) return
        persistMenuSettings()
        settingsBaseline = null
        finishSettingsMenu("Settings saved")
    }

    private fun cancelSettingsEdits() {
        if (!menuOpen) return
        restoreSettingsBaseline()
        finishSettingsMenu("Settings changes discarded")
    }

    private fun finishSettingsMenu(prefix: String) {
        if (!menuOpen) return
        menuOpen = false
        settingsMenu?.visibility = View.GONE
        gestureOverlay?.visibility = View.VISIBLE
        updateDebugOverlays()
        logLines.clear()
        appendLog(
            "$prefix; starting a fresh handshake at " +
                "${CarPlayDisplayScale.label(displayScaleTenths)} with " +
                (if (hevcEnabled) "HEVC (H.265)" else "H.264") +
                ", MFI ${mfiTargetLabel(mfiTarget)}" +
                ", Wi-Fi session ${hotspotModeLabel(wirelessHotspotMode)}",
        )
        if (handshakeResetInProgress) {
            startAfterHandshakeReset = true
        } else {
            maybeStartCarPlay()
        }
    }

    private fun exitApplication() {
        if (shuttingDown.get()) return
        restoreSettingsBaseline()
        finishAndRemoveTask()
        shutdown(terminateProcess = true, reason = "settings exit application")
    }

    private fun shutdown(terminateProcess: Boolean, reason: String, completion: () -> Unit = {}) {
        if (!shuttingDown.compareAndSet(false, true)) { completion(); return }
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        sessionDisplay = null
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.close()
            airPlayCommandExecutor.shutdown()
            if (terminateProcess) {
                applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java))
            }
            Log.i(TAG, "shutdown complete clean=$clean")
            applicationContext.stopService(Intent(applicationContext, DiPlaySessionService::class.java))
            teardownExecutor.shutdown()
            mainHandler.post { completion() }
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun attachSurface(surface: Surface) {
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        // Dashboard projection is always on now, so the only question is whether a projection
        // Surface exists yet; without one the alt stream stays bound to the main surface.
        val hasClusterSurface = clusterSurface != null || mirrorClusterSurface != null || navClusterSurface != null
        if (hasClusterSurface) {
            if (DiLink51ClusterLayout.supported()) {
                clusterSurface?.let {
                    if (currentClusterMirrorMode()) {
                        sink?.setMirrorSurface(SCREEN_TYPE_MAIN, CLUSTER_MAIN_MIRROR, it)
                    } else {
                        sink?.setSurface(SCREEN_TYPE_ALT, it)
                    }
                }
            } else {
                // Generic path: keep both resident surfaces bound (mirror 110 + nav 111).
                mirrorClusterSurface?.let { sink?.setMirrorSurface(SCREEN_TYPE_MAIN, CLUSTER_MAIN_MIRROR, it) }
                navClusterSurface?.let { sink?.setSurface(SCREEN_TYPE_ALT, it) }
                cardClusterSurface?.let { sink?.setSurface(SCREEN_TYPE_ALT_CARD, it) }
            }
        } else {
            sink?.setSurface(SCREEN_TYPE_ALT, surface)
        }
    }

    /**
     * Top-level gesture interception. Runs before the whole view tree, so three-finger
     * dashboard swipes and the four-finger settings swipe work in every screen state:
     * connection panel before projection, live CarPlay, or a half-dead session where a
     * child view would otherwise swallow the touch. Ordinary touches fall through.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!menuOpen && dispatchGesture(event)) return true
        return super.dispatchTouchEvent(event)
    }

    /**
     * End-of-gesture decision from the whole press→release trajectory:
     * - horizontal swipe (dominant X, >= distance, |dy| within slop) → dashboard mode switch
     * - downward swipe (dominant Y, >= distance, finger count matches the settings choice) → DiPlay settings
     * Nothing fires mid-move, so a down-swipe can never collide with the horizontal switch.
     */
    private fun finishGestureIfNeeded(): Boolean {
        if (!gestureSequenceActive) return false
        val dx = gestureEndX - gestureStartX
        val dy = gestureEndY - gestureStartY
        val fingers = gestureStartFingers
        gestureSequenceActive = false
        gestureTracking = false
        // Read the persisted preference directly instead of trusting the cached field: any
        // stale/mismatched cache would silently change which swipe opens the settings page.
        val requiredFingers = AirPlayPersistence.loadSettingsGestureFingers(this)
        val distance = dp(THREE_FINGER_SWIPE_DISTANCE_DP)
        // Direction-dominant thresholds instead of a fixed dp slop: real three-finger swipes
        // on the head unit often carry a downward drift of 60-100dp, which a 48dp slop
        // rejected as "no action" (visible in the logs: dx=-775 dy=80 no action). The 2:1
        // horizontal aspect keeps diagonal gestures inert while accepting slightly slanted
        // horizontal swipes. The down-swipe only requires vertical dominance (dy >= |dx|),
        // so a settings pull does not have to be perfectly vertical either; the dead zone
        // between dy=|dx| and |dx|=2*dy keeps the two gestures clearly separated.
        if (fingers >= GESTURE_MIN_FINGERS &&
            Math.abs(dx) >= distance &&
            Math.abs(dx) >= 2 * Math.abs(dy)
        ) {
            appendLog(
                "Gesture release: horizontal ${if (dx < 0f) "left" else "right"} " +
                    "dx=$dx dy=$dy fingers=$fingers",
            )
            swipeClusterMode(if (dx < 0f) "left" else "right")
            return true
        }
        if (fingers >= minOf(requiredFingers, GESTURE_MIN_FINGERS) &&
            dy >= distance &&
            dy >= GESTURE_VERTICAL_DOMINANCE * Math.abs(dx)
        ) {
            appendLog(
                "Gesture release: down-swipe opens settings dx=$dx dy=$dy " +
                    "fingers=$fingers required=$requiredFingers",
            )
            openSettingsMenu()
            return true
        }
        appendLog(
            "Gesture release: no action dx=$dx dy=$dy fingers=$fingers " +
                "required=$requiredFingers min=$GESTURE_MIN_FINGERS",
        )
        return false
    }

    private fun dispatchGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= GESTURE_MIN_FINGERS && !gestureSequenceActive) {
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartFingers = event.pointerCount
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    gestureEndX = gestureStartX
                    gestureEndY = gestureStartY
                    controller?.sendTouch(emptyList())
                    appendLog("Gesture tracking started ($gestureStartFingers fingers)")
                    return true
                }
                if (gestureSequenceActive && event.pointerCount > gestureStartFingers) {
                    // A fourth finger lands after the three-finger threshold started tracking:
                    // remember the peak count so a 4-finger settings swipe is not judged as 3.
                    gestureStartFingers = event.pointerCount
                    appendLog("Gesture tracking peak fingers=$gestureStartFingers")
                    return true
                }
            }
        }
        if (gestureSequenceActive) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE && gestureTracking &&
                event.pointerCount >= GESTURE_MIN_FINGERS
            ) {
                gestureEndX = pointerCentroid(event, horizontal = true)
                gestureEndY = pointerCentroid(event, horizontal = false)
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                finishGestureIfNeeded()
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                if (event.pointerCount < GESTURE_MIN_FINGERS) finishGestureIfNeeded()
                else gestureTracking = false
                return true
            }
            return true
        }
        return false
    }

    private fun onGestureTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= GESTURE_MIN_FINGERS && !gestureSequenceActive) {
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartFingers = event.pointerCount
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    gestureEndX = gestureStartX
                    gestureEndY = gestureStartY
                    controller?.sendTouch(emptyList())
                    appendLog("Gesture tracking started ($gestureStartFingers fingers)")
                    return true
                }
                if (gestureSequenceActive && event.pointerCount > gestureStartFingers) {
                    // A fourth finger lands after the three-finger threshold started tracking:
                    // remember the peak count so a 4-finger settings swipe is not judged as 3.
                    gestureStartFingers = event.pointerCount
                    appendLog("Gesture tracking peak fingers=$gestureStartFingers")
                    return true
                }
            }
        }
        if (gestureSequenceActive) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE && gestureTracking &&
                event.pointerCount >= GESTURE_MIN_FINGERS
            ) {
                gestureEndX = pointerCentroid(event, horizontal = true)
                gestureEndY = pointerCentroid(event, horizontal = false)
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                finishGestureIfNeeded()
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                if (event.pointerCount < GESTURE_MIN_FINGERS) finishGestureIfNeeded()
                else gestureTracking = false
                return true
            }
            return true
        }
        return false
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= GESTURE_MIN_FINGERS && !gestureSequenceActive) {
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartFingers = event.pointerCount
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    gestureEndX = gestureStartX
                    gestureEndY = gestureStartY
                    controller?.sendTouch(emptyList())
                    appendLog("Gesture tracking started ($gestureStartFingers fingers)")
                    return true
                }
                if (gestureSequenceActive && event.pointerCount > gestureStartFingers) {
                    // A fourth finger lands after the three-finger threshold started tracking:
                    // remember the peak count so a 4-finger settings swipe is not judged as 3.
                    gestureStartFingers = event.pointerCount
                    appendLog("Gesture tracking peak fingers=$gestureStartFingers")
                    return true
                }
            }
        }

        if (gestureSequenceActive) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE && gestureTracking &&
                event.pointerCount >= GESTURE_MIN_FINGERS
            ) {
                gestureEndX = pointerCentroid(event, horizontal = true)
                gestureEndY = pointerCentroid(event, horizontal = false)
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                finishGestureIfNeeded()
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                if (event.pointerCount < GESTURE_MIN_FINGERS) finishGestureIfNeeded()
                else gestureTracking = false
                return true
            }
            return true
        }

        val content = contentRect(view.width, view.height)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOutsideContent = !content.contains(event.x, event.y)
        }
        if (touchOutsideContent) {
            // Ignore the entire touch sequence when it starts in a letterbox bar.
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchOutsideContent = false
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // Exported: "the first tap on a CarPlay button does nothing" reports need to show
                // whether the tap was dropped here (letterbox) or forwarded to the phone.
                appendLog(
                    "Touch: down ignored (outside video area) at (" +
                        "${event.x.toInt()},${event.y.toInt()}) content=(" +
                        "${content.left.toInt()},${content.top.toInt()}," +
                        "${content.width.toInt()}x${content.height.toInt()})",
                )
            }
            return true
        }
        val contacts = CarPlayTouchMapper.contacts(event, content)
        val queued = controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        // The down/up pair of a plain single-finger tap goes to the exported log: it is the only
        // way to tell "our side never sent it" from "the phone ignored it" without logcat.
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
            val first = contacts.firstOrNull()
            appendLog(
                "Touch: " + (if (event.actionMasked == MotionEvent.ACTION_DOWN) "down" else "up") +
                    " -> ${((first?.x ?: 0.0) * 100).toInt()}%,${((first?.y ?: 0.0) * 100).toInt()}% " +
                    "content=${content.width.toInt()}x${content.height.toInt()} " +
                    "session=${controller != null} queued=$queued",
            )
        }
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    /**
     * "导航时自动切换仪表视图": one automatic three-finger equivalent per navigation transition.
     *
     * Driven by the iAP2 0x5201 route state (BydNavigationOutputs.setNavigationStateListener), not
     * by a one-second poll: the transition itself is the event. A short confirmation window guards
     * against the start/end flip-flop the field shows (t=0x01/0x03 inside two seconds), and a real
     * transition hands control back to the auto-switch, so a manual swipe during navigation is
     * never undone until the next transition.
     *
     * The mode it applies is in-memory; applyClusterMode(persist=false) keeps the driver's saved
     * dashboard display choice untouched.
     */
    private fun onNavigationStateEvent(navigating: Boolean) {
        if (shuttingDown.get() || isFinishing || isDestroyed || handshakeResetInProgress) return
        pendingNavigationState = navigating
        mainHandler.removeCallbacks(navStateConfirmation)
        mainHandler.postDelayed(navStateConfirmation, NAV_STATE_CONFIRM_MS)
    }

    private val navStateConfirmation = Runnable {
        val navigating = pendingNavigationState ?: return@Runnable
        if (shuttingDown.get() || isFinishing || isDestroyed || handshakeResetInProgress || menuOpen) {
            return@Runnable
        }
        // The route state is noisy around a transition: only act when it is still the same value
        // after the confirmation window.
        if (com.shilapi.xcertplay.hud.BydNavigationOutputs.navigationActive() != navigating) {
            appendLog("Nav auto: 确认窗口内状态又变了，忽略这次切换 navigating=$navigating")
            return@Runnable
        }
        applyAutoNavState(navigating)
    }

    private fun applyAutoNavState(navigating: Boolean) {
        if (!AirPlayPersistence.loadClusterAutoNavMode(this)) {
            if (clusterPositionFromAuto) {
                // The auto-switch left the position machine at 0 or 1. At 1 a left swipe is the
                // end of the machine and does nothing, which read as "three-finger stopped
                // working" after turning the switch off. Leave 0 at 0 so a left swipe advances to
                // nav-only; move 1 back to -1 so a left swipe goes to the mirror. A position the
                // driver set manually is never touched.
                clusterSwipePosition = if (clusterSwipePosition == 1) -1 else 0
                clusterPositionFromAuto = false
            }
            autoNavModePending = false
            manualClusterOverride = false
            lastAutoNavState = null
            return
        }
        if (activeAirPlaySession == null) return
        // A real transition starts a new automatic cycle: the driver's manual choice from the
        // previous navigation no longer applies.
        manualClusterOverride = false
        lastAutoNavState = navigating
        val channelOpen = watcherChannelOpen ||
            runCatching { InCallClusterBridge.reportsProjecting() }.getOrDefault(false)
        if (!channelOpen) {
            // Record the desired mode on a hidden window; the projection callback shows it once
            // incall is actually projecting. Never force it visible here (small preview box).
            autoNavModePending = true
            clusterSwipePosition = if (navigating) 1 else 0
            clusterPositionFromAuto = true
            if (clusterPresentation == null) runCatching { ensureClusterPresentation() }
            clusterPresentation?.setMode(!navigating)
            appendLog(
                "Nav auto: 投屏通道未开，预置${if (navigating) "仅导航" else "整机镜像"}，" +
                    "通道就绪后显示",
            )
            return
        }
        autoNavModePending = false
        autoProjectForNavigation(navigating)
    }

    private fun autoProjectForNavigation(navigating: Boolean) {
        if (shuttingDown.get() || isFinishing || isDestroyed) return
        // The driver handed the panel back from the wheel; a navigation change must not take it
        // again behind their back. The next swipe or connection clears this.
        if (userHandedBackProjection) {
            appendLog("Nav auto: 用户已归还仪表，跳过自动切换 navigating=$navigating")
            return
        }
        val mirror = !navigating
        clusterSwipePosition = if (mirror) 0 else 1
        clusterPositionFromAuto = true
        if (clusterPresentation == null) runCatching { ensureClusterPresentation() }
        val presentation = clusterPresentation ?: run {
            appendLog("Nav auto: 无仪表投影显示，暂不切换")
            return
        }
        presentation.setMapVisible(true)
        applyClusterMode(
            mirror,
            if (navigating) "nav-auto-start" else "nav-auto-end",
            persist = false,
        )
        appendLog(
            "Nav auto: 导航${if (navigating) "开始" else "结束"}，" +
                "切换为${if (mirror) "整机镜像" else "仅导航"}（仅本次使用，未保存设置）",
        )
    }

    /**
     * Three-finger left/right swipe is a forward/backward position machine like CarPlay boxes:
     * left advances the dashboard mode (initial → navigation stream → whole-interface mirror),
     * right goes back; past the ends nothing happens, and before any left swipe a right swipe
     * has no effect. The switch is hot: only the sink binding changes, the session and stream
     * 111 stay up, so there is no reconnect.
     */
    private fun swipeClusterMode(direction: String) {
        if (shuttingDown.get() || handshakeResetInProgress) {
            appendLog(
                "Cluster swipe $direction: skipped shuttingDown=${shuttingDown.get()} " +
                    "handshakeReset=$handshakeResetInProgress pos=$clusterSwipePosition",
            )
            return
        }
        if (initialClusterMirror == null) initialClusterMirror = AirPlayPersistence.loadClusterMirrorMain(this)
        // A swipe is the driver asking for the dashboard again: leave the hand-back state.
        if (userHandedBackProjection) {
            userHandedBackProjection = false
            appendLog("Cluster swipe $direction: 用户重新接管仪表，允许显示")
        }
        // Left swipe (right-to-left) is FORWARD: not projected -> whole screen (pos 0) ->
        // navigation stream only (pos 1) -> boundary. Right swipe is BACK: pos 1 -> pos 0 ->
        // off (-1) -> boundary, and does nothing before the first projection.
        val nextPos = when (direction) {
            "left" -> when {
                clusterSwipePosition <= -1 -> 0
                clusterSwipePosition >= 1 -> return
                else -> clusterSwipePosition + 1
            }
            else -> when {
                clusterSwipePosition <= -1 -> return
                else -> clusterSwipePosition - 1
            }
        }
        clusterSwipePosition = nextPos
        if (direction != "auto") {
            // The driver took over: the position machine now belongs to them, and auto-switch
            // waits for the next navigation transition instead of undoing the choice.
            clusterPositionFromAuto = false
            manualClusterOverride = true
            // The driver took over: cancel any pending auto-project retry ladder, so it can never
            // fight a manual swipe (it would re-send the open sequence on top of the driver's
            // choice). autoProjectTriggered stays true: auto projection does not re-fire until
            // the next session.
            autoProjectToken = Any()
            // A swipe also proves the projection is up, which is the only success signal there
            // is: the ladder's remaining full-sequence retry would only add an announcement.
            if (autoProjectLadderActive) {
                autoProjectLadderActive = false
                appendLog("Auto project: 司机已手动操作，停止重试阶梯（投影应已生效）")
            }
        }
        if (nextPos == -1) {
            // Hand the dashboard back by hiding our window. Nothing else: no broadcast, no
            // activation, no teardown. The channel and the window live for the whole session, so
            // the next swipe only has to show it again.
            clusterPresentation?.setMapVisible(false)
            appendLog("Cluster swipe $direction: 隐藏仪表盘投屏窗口 (pos=-1)")
            return
        }
        // pos 0 = whole-screen mirror, pos 1 = navigation stream only.
        val mirror = nextPos == 0
        appendLog("Cluster swipe $direction: 请求仪表盘显示${if (mirror) "整机界面" else "导航流"} (pos=$nextPos)")
        // The window exists for the whole session and only ever shows or hides. Whether it can be
        // seen at all depends on one thing: the projection channel the start-up watcher sampled.
        // No path below may send the activation sequence - that is what announced on every swipe.
        // The wheel's close-projection only hands the panel back; it does not close the channel.
        // A window that already exists is therefore proof enough to show it again - requiring a
        // fresh "projecting" report here is what made the swipe appear dead after a hand-back.
        val channelReady = clusterPresentation != null || watcherChannelOpen ||
            runCatching { InCallClusterBridge.reportsProjecting() }.getOrDefault(false)
        if (!channelReady) {
            clusterPresentation?.setMapVisible(false)
            appendLog("Cluster swipe $direction: 投屏通道未开，不显示窗口 (pos=$nextPos)")
            return
        }
        if (clusterPresentation == null) ensureClusterPresentation()
        clusterPresentation?.setMapVisible(true)
        applyClusterMode(mirror, direction)
    }

    // Applies the requested dashboard mode once a projection display is available.
    private fun applyClusterMode(mirror: Boolean, direction: String, persist: Boolean = true) {
        // The picture is back: drop the disconnect state in one place, so every caller that
        // re-shows the window (auto-project, a swipe, a returning stream) ends up consistent.
        if (clusterWindowHidden) {
            clusterWindowHidden = false
            appendLog("Cluster: 窗口重新显示，恢复仪表投屏（静默）")
        }
        mainHandler.removeCallbacks(releaseDashboardAfterLongLoss)
        // Persist the mode only when the dashboard projection is actually available. The navigation
        // auto-switch passes persist=false: it is an in-memory override for this run and must not
        // overwrite the driver's saved CarPlay-dashboard display choice.
        if (persist) AirPlayPersistence.saveClusterMirrorMain(this, mirror)
        if (mirror) {
            // Whole-screen mirror: nothing to arm - the overview toggle belongs to the
            // nav-only mode, and tapping it here would flip the console Amap into overview.
            // NOTE: the previous code sent EXTRA_CROSS_MAP=0 (10019 broadcast) when returning
            // to whole-screen mirror so the 111 stream would come back detailed. Field feedback:
            // this 10019 broadcast is exactly what makes the head-unit announce "全屏镜像已开启"
            // (the incall service announces on projection-source switches), and it does nothing
            // for the iPhone-side Amap anyway (protocol-level CROSS_MAP is consumed by incall
            // only, not by iOS Amap). Removed to kill the voice announcement; the nav-only
            // detail mode is now left to the driver tapping 全览 on the console Amap manually.
            appendLog("Cluster swipe $direction: 整机镜像（已停用 EXTRA_CROSS_MAP=0 以消除全屏镜像语音提示）")
        }
        // Hot switch: reuse the existing cluster window. Generic path: both streams (110 mirror
        // + 111 nav) are permanently resident in the sink, so the switch only flips view
        // visibility (no stream re-activation -> no "全屏导航已开启" announcement). DiLink 5.1
        // single-TextureView path keeps the old rebind-and-switch behaviour.
        if (clusterPresentation == null) ensureClusterWindowQuiet()
        clusterPresentation?.let { p ->
            p.setMapVisible(shouldShowClusterWindow())
            if (!shouldShowClusterWindow()) {
                appendLog("Cluster: 车机尚未确认投屏通道，先不显示（确认后自动显示）")
            }
            scheduleClusterFrameCheck()
            if (DiLink51ClusterLayout.supported()) {
                p.mirrorOutputSurface?.let { s -> onClusterSurface(s, force = true) }
            } else {
                p.setMode(mirror)
            }
            p.setStreamActive(clusterStreamActive())
            if (!mirror && !clusterStreamActive()) {
                // Navigation stream (111) has no frames yet: iPhone is not navigating.
                // Keep the binding; the window fills in as soon as guidance data arrives.
                appendLog("Cluster swipe $direction: 导航流未激活，等待手机端导航数据")
                Toast.makeText(
                    this,
                    "已切换为导航流，等待手机导航数据...",
                    Toast.LENGTH_SHORT,
                ).show()
            } else if (!mirror) {
                // Nav-only mode. The automatic overview tap is removed (driver taps 全览 on
                // the console Amap themselves when they want the detailed follow map).
                appendLog("Cluster swipe $direction: 已切换为导航流，等待手机导航数据...")
            }
        }
        Toast.makeText(
            this,
            "仪表盘已切换为${if (mirror) "整机界面" else "导航流"}",
            Toast.LENGTH_SHORT,
        ).show()
        // Re-apply the dashboard transform so the whole-screen mirror left-rail crop
        // (and the manual target frame) take effect for the newly selected stream.
        if (!shuttingDown.get()) {
            val presentation = clusterPresentation
            if (presentation != null) {
                // Diagnostics: record the exact crop inputs so a later export can tell whether
                // the crop was applied with the right values or silently skipped.
                appendLog(
                    "Cluster crop applied: mirror=$mirror leftCropPx=" +
                        AirPlayPersistence.loadClusterMirrorLeftCropPx(this) +
                        " rect=(" + AirPlayPersistence.loadClusterCropLeftPercent(this) + "," +
                        AirPlayPersistence.loadClusterCropTopPercent(this) + "," +
                        AirPlayPersistence.loadClusterCropWidthPercent(this) + "," +
                        AirPlayPersistence.loadClusterCropHeightPercent(this) + ")",
                )
                runCatching { presentation.refreshCrop() }
            } else {
                appendLog("Cluster crop skipped: 窗口不存在 (mirror=$mirror)")
            }
        }
    }

    // Best-effort cold-start activation of the dashboard projection via the Chang'an
    // instrument-cluster bridge (full UNI-V sequence: bind DoubleInteractiveService +
    // AUTONAVI 10019/10116/10117 broadcasts + tx21/8/1/2 + CarProperty). Retries
    // findDisplay a few times in case the display registers asynchronously.
    /**
     * Whether the dashboard window may be visible right now.
     *
     * The head unit tells us through its callback when the panel is really projecting our
     * ext-screen; before that it lays the ext-screen out as its small preview box at the right of
     * the panel. Showing the window earlier is exactly what put CarPlay's picture in that square
     * (measured 2026-10-20: window shown 20:01:59, projection confirmed 20:02:39 - 40 s of square).
     * A projection that is already confirmed, or a session that started projecting before this
     * process, shows immediately.
     */
    private fun shouldShowClusterWindow(): Boolean {
        if (clusterProjectionAbandoned) return false
        if (!clusterProjectionStarted) return true
        return InCallClusterBridge.reportsProjecting()
    }

    /**
     * True when the sampled projection display reports a real, landscape dashboard panel.
     *
     * The ext-screen exists before activation, but its size is not trustworthy until incall has
     * mapped the real panel: a preview box (or a 0x0 mode during reconfiguration) must not be used
     * to decide that the channel is ready. Sampled with the same getRealSize() the window uses,
     * not Display.mode, so the two cannot disagree.
     */
    private fun clusterDisplaySizeReady(): Boolean {
        val display = watcherDisplay ?: return false
        val size = runCatching { ClusterMapPresentation.sizeOf(display) }.getOrNull() ?: return false
        watcherWidth = size.x
        watcherHeight = size.y
        // A mode that disagrees with the real size means the display is still being reconfigured
        // (or is still the pre-activation placeholder); either way the sequence must wait.
        val mode = runCatching { display.mode }.getOrNull()
        if (mode != null && (mode.physicalWidth != size.x || mode.physicalHeight != size.y)) return false
        return size.x >= CLUSTER_MIN_READY_WIDTH_PX &&
            size.y >= CLUSTER_MIN_READY_HEIGHT_PX &&
            size.x > size.y
    }

    /**
     * Samples the projection channel for the whole life of the activity.
     *
     * This is the ONLY place allowed to send the activation sequence. It keeps sampling, and while
     * it has not decided yet it also keeps waiting: [WATCHER_READY_WAIT_MS] after start-up, if the
     * channel is still closed, it sends the sequence exactly once and never again. If the channel
     * reports open at any point inside that window, nothing is sent at all. Every other part of the
     * app only shows or hides the window - re-sending from there is what made the head unit
     * announce "全屏镜像/导航已开启" on swipes and reconnects.
     *
     * `InCallClusterBridge.reportsProjecting()` is a plain read of the state incall reported through
     * its callback; it sends nothing, which is why the loop can call it every period.
     *
     * The first dashboard display it sees is kept, so later window builds use a display that was
     * sampled while incall was idle instead of one taken mid-reconfiguration.
     */
    private fun startClusterChannelWatcher() {
        if (watcherThread != null || DiLink51ClusterLayout.supported()) return
        if (shuttingDown.get() || isFinishing || isDestroyed) return
        watcherThread = Thread({
            val startedAtMs = SystemClock.elapsedRealtime()
            // Backoff schedule, and a hard cap on how many times this may fire.
            //
            // The first send is the normal one; the rest only cover a channel that is still coming
            // up. It MUST be bounded: a head unit that never enters projection mode (the driver is
            // not using the dashboard at all) would otherwise be flooded with the full sequence
            // forever, and each sequence is dozens of binder broadcasts - at one every five seconds
            // the whole system started stuttering, audio included. The field log for 2026-10-22
            // shows "第 163 次", thirteen minutes of it, with broadcast 10019 lines every 100 ms.
            val retryDelaysMs = longArrayOf(WATCHER_FIRST_SEND_DELAY_MS, 15_000L, 30_000L, 60_000L)
            var attempts = 0
            var lastSentAtMs = 0L
            while (!shuttingDown.get() && !isFinishing && !isDestroyed) {
                // The driver closed projection from the wheel: the channel is still theirs and
                // ours to use later, but re-sending the sequence here is what reopened the
                // dashboard they just closed. Wait for a swipe or a new connection.
                if (userHandedBackProjection) {
                    quietSleep(WATCHER_PERIOD_MS)
                    continue
                }
                // 1) Sample the channel. Read-only: incall's own report, nothing goes out here.
                val open = runCatching { InCallClusterBridge.reportsProjecting() }.getOrDefault(false)
                watcherChannelOpen = open

                // 2) Sample the dashboard display. Runs every period regardless of the channel
                //    state, and never waits on anything.
                if (watcherDisplay == null) {
                    runCatching { ClusterMapPresentation.findDisplay(this) }.getOrNull()?.let { display ->
                        watcherDisplay = display
                        val size = ClusterMapPresentation.sizeOf(display)
                        watcherWidth = size.x
                        watcherHeight = size.y
                        appendLog(
                            "Cluster watcher: 记录仪表 display=${display.displayId} " +
                                "${watcherWidth}x${watcherHeight}",
                        )
                    }
                }
                // A projection display exists before activation, but incall maps the ext-screen to
                // a small placeholder box until the full sequence has been accepted. Sending the
                // sequence from that state was the "cold start projects only a small block on the
                // right" report. Wait until the display reports a real landscape panel; the retry
                // clock is not advanced while we are waiting, so the first send happens as soon as
                // the size becomes sane.
                val sizeReady = clusterDisplaySizeReady()

                // 3) The channel is up: the budget starts over, so a channel the driver closes by
                //    hand later is picked up again from the first (shortest) delay.
                val now = SystemClock.elapsedRealtime()
                if (open) {
                    attempts = 0
                    lastSentAtMs = 0L
                } else if (attempts < retryDelaysMs.size) {
                    val delay = if (lastSentAtMs == 0L) {
                        retryDelaysMs[0] - (now - startedAtMs)
                    } else {
                        retryDelaysMs[attempts] - (now - lastSentAtMs)
                    }
                    if (delay <= 0L) {
                        if (!sizeReady) {
                            // Do not consume an attempt and do not send: wait for the real panel
                            // size. Log at most every few seconds so a head unit that never
                            // reports a sane size cannot flood the report.
                            if (now - lastClusterSizeWaitLogMs >= CLUSTER_SIZE_WAIT_LOG_MS) {
                                lastClusterSizeWaitLogMs = now
                                appendLog(
                                    "Cluster watcher: 仪表尺寸未就绪 " +
                                        "${watcherWidth}x${watcherHeight}，暂缓完整激活序列",
                                )
                            }
                        } else {
                            attempts++
                            lastSentAtMs = now
                            clusterPrewarmAtMs = now
                            appendLog(
                                "Cluster watcher: 投屏通道未就绪，发完整激活序列" +
                                    "（第 $attempts/${retryDelaysMs.size} 次，仪表 ${watcherWidth}x${watcherHeight}）",
                            )
                            mainHandler.post {
                                if (!shuttingDown.get() && !isFinishing && !isDestroyed) {
                                    InCallClusterBridge.openProjection(this)
                                }
                            }
                        }
                    }
                }
                quietSleep(WATCHER_PERIOD_MS)
            }
        }, "cluster-channel-watcher").apply { isDaemon = true; start() }
    }

    /** Sleeps without letting an interrupt escape the watcher loop. */
    private fun quietSleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Gives up on this activation when the channel is still not ready a few seconds after the phone
     * connected. Nothing is shown in that case: a preview box must never reach the panel, and the
     * driver opens the dashboard from the wheel when he wants it.
     */
    private fun awaitClusterProjectionReady(mirror: Boolean, direction: String, waitedMs: Long) {
        if (shuttingDown.get() || isFinishing || isDestroyed) return
        if (!clusterProjectionStarted) return
        if (InCallClusterBridge.reportsProjecting()) {
            onClusterProjectionConfirmed()
            return
        }
        if (waitedMs >= PROJECTION_CONFIRM_TIMEOUT_MS) {
            clusterProjectionAbandoned = true
            clusterPresentation?.setMapVisible(false)
            appendLog(
                "Cluster: 连接后 ${PROJECTION_CONFIRM_TIMEOUT_MS}ms 内投屏通道未就绪，放弃本次投屏" +
                    "（可用方向盘手动开启）",
            )
            return
        }
        mainHandler.postDelayed(
            { awaitClusterProjectionReady(mirror, direction, waitedMs + 500L) },
            500L,
        )
    }

    /**
     * incall just confirmed that the panel is ours: show the window that has been waiting for it.
     */
    private fun onClusterProjectionConfirmed() {
        if (shuttingDown.get() || isFinishing || isDestroyed) return
        lastKnownProjecting = true
        if (userHandedBackProjection) {
            appendLog("Cluster: 投屏已就绪，但用户已归还仪表，保持隐藏（三指可再次打开）")
            return
        }
        if (clusterWindowHidden) return
        val presentation = clusterPresentation ?: return
        presentation.setMapVisible(true)
        scheduleClusterFrameCheck()
        if (clusterProjectionAbandoned) {
            clusterProjectionAbandoned = false
            appendLog("Cluster: 投屏通道迟到就绪（已放弃等待过），现在显示")
        } else {
            appendLog("Cluster: 车机确认投屏通道已开，显示仪表窗口")
        }
    }

    /**
     * The steering wheel's "close projection" asks us to give the panel back; it does NOT close
     * the projection channel. Hide our window and stop the watcher from re-activating, otherwise
     * every close is answered by a fresh activation sequence and the dashboard cannot be closed.
     *
     * The 0 that shows up while our own sequence is ramping is not a hand-back, hence the
     * projecting -> not projecting edge plus a short confirmation.
     */
    private fun onClusterProjectionWithdrawn() {
        if (shuttingDown.get() || isFinishing || isDestroyed) return
        // "Was projecting" is not only incall's last report: a window that is on screen and not
        // hidden proves the panel was ours even if the confirmation callback never arrived.
        val wasProjecting = lastKnownProjecting || (clusterPresentation != null && !clusterWindowHidden)
        lastKnownProjecting = false
        if (!wasProjecting) return
        // Suspend the auto-activation IMMEDIATELY: the watcher samples every 500 ms, so waiting
        // for the confirmation window let it send a sequence first and re-open the dashboard once
        // (field 2026-10-26: "偶尔还是会复投一次"). The confirmation below only undoes the flag
        // when the drop was really part of an activation ramp.
        userHandedBackProjection = true
        mainHandler.removeCallbacks(projectionWithdrawConfirmation)
        mainHandler.postDelayed(projectionWithdrawConfirmation, PROJECTION_WITHDRAW_CONFIRM_MS)
    }

    private val projectionWithdrawConfirmation = Runnable {
        if (shuttingDown.get() || isFinishing || isDestroyed) return@Runnable
        if (InCallClusterBridge.reportsProjecting()) {
            // The panel came back by itself: this was the 0 inside our own activation ramp, not
            // the driver closing it. Undo the hand-back.
            userHandedBackProjection = false
            lastKnownProjecting = true
            appendLog("Cluster: 投影状态自行恢复，撤销归还判定")
            return@Runnable
        }
        clusterPresentation?.setMapVisible(false)
        clusterSwipePosition = -1
        clusterPositionFromAuto = false
        appendLog("Cluster: 车机收回仪表（方向盘关闭投屏），停止自动激活；三指或重连可再次打开")
    }

    /**
     * Checks shortly after showing the dashboard window that it really covers the panel.
     *
     * The driver's rule (2026-10-20): a small box on the dashboard is never acceptable - a
     * dashboard that shows nothing is. incall sometimes lays our (already existing) ext-screen
     * window out as its small preview box at the right of the panel and keeps it there, so the
     * window is hidden instead of shown. Nothing is torn down: the next swipe or wheel press shows
     * it again and re-checks, and by then incall usually re-lays it out properly.
     */
    private val clusterFrameCheck = Runnable { hideClusterWindowIfPlaceholder() }

    private fun scheduleClusterFrameCheck() {
        mainHandler.removeCallbacks(clusterFrameCheck)
        mainHandler.postDelayed(clusterFrameCheck, 700L)
    }

    private fun hideClusterWindowIfPlaceholder() {
        if (DiLink51ClusterLayout.supported()) return
        if (shuttingDown.get() || isFinishing || isDestroyed) return
        val presentation = clusterPresentation ?: return
        val display = ClusterMapPresentation.findDisplay(this) ?: return
        val expected = ClusterMapPresentation.sizeOf(display)
        val frame = presentation.windowFrame()
        // Not laid out yet: leave it alone rather than hide a window that is still settling.
        if (frame.x <= 0 || frame.y <= 0) return
        val full = frame.x >= expected.x * 9 / 10 && frame.y >= expected.y * 9 / 10
        if (full) return
        presentation.setMapVisible(false)
        appendLog(
            "Cluster: 仪表窗口只有 ${frame.x}x${frame.y}（应为 ${expected.x}x${expected.y}），" +
                "已隐藏，避免仪表出现小方块；方向盘或三指可重新打开",
        )
    }

    private fun tryActivateClusterProjection(
        mirror: Boolean,
        direction: String,
        keepWindow: Boolean = false,
        forceSequence: Boolean = false,
    ) {
        // The channel can already be ready from the start-up prewarm: then there is nothing to
        // activate, only to show - no second open sequence and no second announcement.
        if (!forceSequence && clusterPresentation != null && !clusterWindowHidden &&
            InCallClusterBridge.reportsProjecting()
        ) {
            appendLog("Cluster activation: 投屏通道已就绪（启动预热），直接显示")
            clusterProjectionStarted = true
            clusterProjectionAbandoned = false
            applyClusterMode(mirror, direction)
            return
        }
        // A prewarm sequence went out moments ago: re-sending the full one would make the head
        // unit announce "全屏镜像/导航已开启" a second time for the same activation.
        if (!forceSequence && clusterPrewarmAtMs > 0L &&
            SystemClock.elapsedRealtime() - clusterPrewarmAtMs < PREWARM_SILENT_WINDOW_MS
        ) {
            appendLog("Cluster activation: 刚预热过，直接显示（不发重申）")
            clusterProjectionStarted = true
            clusterProjectionAbandoned = false
            applyClusterMode(mirror, direction)
            awaitClusterProjectionReady(mirror, direction, waitedMs = 0L)
            return
        }
        // A window hidden by a disconnect needs no activation at all: incall never left projection
        // mode, the window and its decoders are still bound, and re-sending the open sequence is
        // exactly what makes the head unit announce "全屏镜像/导航已开启" - the defect the driver
        // reported. Only a really missing window falls through to the sequence below.
        // forceSequence skips this shortcut: it is set when incall itself reported that the
        // projection is NOT ours any more (driver closed the channel on the head unit, adb-mode
        // detour), where the hidden window is exactly the case that needs a real re-open.
        if (!forceSequence && clusterWindowHidden && clusterPresentation != null &&
            ClusterMapPresentation.findDisplay(this) != null
        ) {
            appendLog("Cluster activation: 窗口仅被隐藏，静默恢复 (pos=${if (mirror) 0 else 1})")
            applyClusterMode(mirror, direction)
            return
        }
        // We are about to send the full open sequence, which takes the ext-screen back. If a
        // disconnect had handed it to the head unit, the flag must be dropped here or every later
        // "released?" check would think the panel is still the head unit's and re-run this sequence
        // again on the next swipe.
        if (clusterExtScreenReleased) {
            appendLog("Cluster activation: 仪表盘曾归还车机，重发完整激活序列重新接管")
            clusterExtScreenReleased = false
        }
        clusterProjectionStarted = true
        clusterProjectionAbandoned = false
        // The window was created hidden (see ensureClusterPresentation): the head unit is about to
        // be put into projection mode, so it may become visible now.
        clusterPresentation?.setMapVisible(true)
        // Drop any window that was created on the pre-activation display: until the head-unit
        // switches to projection mode that display shows nothing on the physical dashboard, and
        // keeping the old window would let later hot-swaps reuse it instead of the activated one.
        // Manual swipes pass keepWindow=true so the window survives (no black flash) while the
        // activation sequence is re-sent - re-arming an incall session that auto-project may
        // have fired before the stock Amap was ready.
        if (!keepWindow && clusterPresentation != null) {
            appendLog("Cluster activation: 丢弃激活前的旧投影窗口 (recreate after activation)")
            dismissClusterPresentation()
        }
        val bridgeAvailable = InCallClusterBridge.isAvailable(this)
        appendLog(
            "Cluster activation: bridgeAvailable=$bridgeAvailable（本路径不再发激活序列）",
        )
        // No open sequence from here. The start-up channel watcher is the only sender; every call
        // that used to land here now just shows the window, which is what removed the repeated
        // "全屏镜像/导航已开启" announcements.
        // The UNI-V open sequence itself takes ~1.5 s (delays 80..7600 ms + source ladder
        // 250..3500 ms), so wait longer than the previous 800 ms before looking for the display.
        finishActivation(mirror, direction, attempt = 1)
    }

    private fun finishActivation(mirror: Boolean, direction: String, attempt: Int) {
        mainHandler.postDelayed({
            if (shuttingDown.get() || handshakeResetInProgress) return@postDelayed
            val display = ClusterMapPresentation.findDisplay(this)
            if (display != null) {
                clusterActivationAttempted = true
                // NOTE: the projection display is registered before activation on the UNI-V, so
                // finding it does NOT prove the head-unit switched to projection mode (the physical
                // dashboard may still be blank). This log line only means "window bound; activation
                // sequence was sent". Whether the incall session accepted it is visible only on the
                // dashboard itself or via the head-unit's own logs.
                appendLog(
                    "Cluster activation: 投影窗口已绑定 display=${display.displayId} " +
                        "(激活序列已发送，incall 是否接受需看仪表盘实际画面) mirror=$mirror",
                )
                applyClusterMode(mirror, direction)
                // From here the channel has a few seconds to call itself ready. Nothing is shown
                // until it does; after the grace window the attempt is dropped (see
                // awaitClusterProjectionReady).
                awaitClusterProjectionReady(mirror, direction, waitedMs = 0L)
            } else if (attempt < 3) {
                appendLog("Cluster activation: 激活后未找到仪表投影 display，重试 $attempt")
                finishActivation(mirror, direction, attempt + 1)
            } else {
                appendLog("Cluster activation: 激活后仍未找到仪表投影 display")
                Toast.makeText(
                    this,
                    "仪表盘尚未投屏，请先投屏后使用三指切换",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }, 1600L)
    }

    // Normalized position of Amap CarPlay's "全览" (overview) toggle button, bottom of the
    // console map's right-hand button column. Tapping it flips the console to overview, which
    // makes Amap render the detailed follow map on the 111 dashboard stream; the dashboard
    // keeps that detailed mode even after the console leaves Amap (user-verified). This
    // reproduces the UNI-V box's "navigation stream" behaviour.
    // NOTE: the automatic overview tap is removed together with the "仪表盘显示详细地图"
    // feature - the driver taps 全览 on the console Amap manually now.
    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
                // Stream is up: create the hidden dashboard window so a three-finger swipe
                // can show it instantly, even before the first projection.
                if (clusterPresentation == null) ensureClusterWindowQuiet()
            } else {
                activeScreenStreamTypes.remove(type)
            }
            if (type == SCREEN_TYPE_ALT_CARD) {
                // FLY_CARD's instruction-card window exists only while this stream is really up;
                // the presentation destroys it when the stream goes away (no idle black slab).
                Log.i(ClusterMapPresentation.TAG, "cluster card stream active=$active")
                clusterPresentation?.setCardStreamActive(active)
            }
            if (type == SCREEN_TYPE_ALT || (currentClusterMirrorMode() && type == SCREEN_TYPE_MAIN)) {
                Log.i(ClusterMapPresentation.TAG, "cluster stream active=$active")
                appendLog("Cluster map: stream active=$active")
                clusterPresentation?.setStreamActive(active)
                // Frames are the only truthful "the phone is still here" signal: while they flow the
                // hand-back is cancelled, and when they stop it is scheduled. The session callbacks
                // alone could not see this - iAP2 reconnects in seconds while the picture stays
                // frozen on the last decoded frame (field report 2026-10-18).
                mainHandler.removeCallbacks(hideDashboardAfterDisconnect)
                if (!active && clusterProjectionStarted) {
                    mainHandler.postDelayed(hideDashboardAfterDisconnect, disconnectHideDelayMs)
                }
                if (active) {
                    // Frames are back: a window hidden by a disconnect is simply shown again. incall
                    // never left projection mode, so there is nothing to activate and nothing to
                    // announce.
                    mainHandler.removeCallbacks(releaseDashboardAfterLongLoss)
                    if (clusterWindowHidden) {
                        clusterWindowHidden = false
                        clusterPresentation?.setMapVisible(shouldShowClusterWindow())
                        clusterPresentation?.setMode(currentClusterMirrorMode())
                        scheduleClusterFrameCheck()
                        appendLog("Cluster map: 画面恢复，静默显示仪表窗口（未重发激活序列）")
                    }
                }
                // Centre-card and launcher mirrors track the navigation cluster stream only.
                if (!currentClusterMirrorMode()) {
                    MapMirrors.setStreamActive(active)
                    if (active) {
                        mainHandler.removeCallbacks(hideIdleCenterMap)
                        if (!CenterMapOverlay.shown) CenterMapOverlay.scheduleShow()
                    } else {
                        mainHandler.postDelayed(hideIdleCenterMap, CENTER_MAP_IDLE_MILLIS)
                    }
                }
            }
            updateDebugOverlays()
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            setConnectionStage(message)
            appendLog(message)
        }
    }

    private fun setConnectionStage(message: String) {
        latestStage = message
        stageStatusView?.text = friendlyStage(message)
        updateDebugOverlays()
    }

    private fun updateDebugOverlays() {
        statusScrollView?.visibility = View.GONE
        connectionPanel?.visibility = if (activeScreenStreamTypes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun friendlyStage(message: String): String = when {
        message.contains("did not start AirPlay", true) -> getString(R.string.iphone_accepted_but_did_not_start_carplay)
        message.contains("Turn on Wi-Fi", true) -> getString(R.string.turn_on_wi_fi_in_the_head_unit_s_settings_to_connect)
        message.contains("Allow precise Location", true) -> getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p)
        message.contains("Allow Nearby devices", true) -> getString(R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per)
        message.contains("createGroup failed", true) -> getString(R.string.the_head_unit_couldn_t_start_carplay_wi_fi_check_wi_fi_and)
        message.contains("needs a reset", true) -> getString(R.string.a_previous_wi_fi_direct_connection_is_still_running_reset)
        message.contains("socket", true) || message.contains("RFCOMM", true) -> getString(R.string.your_iphone_isn_t_available_unlock_it_and_check_bluetooth)
        message.contains("unsupported", true) || message.contains("not supported", true) -> getString(R.string.this_head_unit_may_not_support_wireless_carplay_try_a_usb)
        message.contains("denied", true) || message.contains("permission", true) -> getString(R.string.allow_the_connection_permission_to_continue)
        message.contains("Failed", true) || message.contains("error", true) -> getString(R.string.connection_interrupted_retrying)
        message.contains("Waiting for iPhone", true) || message.contains("Discovering iPhone", true) -> getString(R.string.connect_your_iphone_with_a_usb_cable)
        message.contains("paired", true) -> getString(R.string.looking_for_your_paired_iphone)
        message.contains("Bluetooth", true) -> getString(R.string.connecting_to_your_iphone)
        message.contains("reconnect", true) || message.contains("ended", true) -> getString(R.string.reconnecting_to_your_iphone)
        message.contains("active", true) || message.contains("running", true) -> getString(R.string.opening_carplay)
        else -> getString(R.string.getting_carplay_ready)
    }

    private fun appendLog(message: String) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        sessionLog?.append(formattedLogLine(safe, System.currentTimeMillis()))
    }

    private fun appendFileLog(message: String) {
        sessionLog?.append(formattedLogLine(message, System.currentTimeMillis()))
    }

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val logFile = File(File(filesDir, "logs"), "diplay.log")
        val activeLog = SessionLogFile(logFile)
        runCatching {
            activeLog.reset(
                "DiPlay log started " +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "pid=${Process.myPid()} path=${logFile.absolutePath}",
            )
        }
        sessionLog = activeLog
        // The cluster bridge logs its activation steps and the head unit's callback values only to
        // logcat by default; routing them here puts them in the exported report.
        InCallClusterBridge.diagnosticSink = { message -> appendLog(message) }
    }

    private fun refreshLogView(nowMillis: Long) {
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        while (logLines.firstOrNull()?.timestampMillis?.let { it <= cutoff } == true) {
            logLines.removeFirst()
        }
        statusView?.text = logLines.joinToString("\n") { it.text }
        scrollLogsToBottom()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstOrNull()?.let { oldest ->
            val delay = (oldest.timestampMillis + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun scrollLogsToBottom() {
        statusScrollView?.post {
            statusScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun applyFullscreenMode() {
        val hideTop = hideTopBar
        val hideBottom = hideBottomBar
        WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hideTop) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> getString(R.string.preparing_mfi_authentication)
        CarPlayStatus.WaitingForMfi -> getString(R.string.waiting_for_mfi_coprocessor)
        CarPlayStatus.RequestingMfiPermission -> getString(R.string.requesting_mfi_usb_permission)
        CarPlayStatus.MfiReady -> getString(R.string.mfi_authentication_ready)
        CarPlayStatus.StartingHotspot -> getString(if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI)
            R.string.existing_wifi_attaching else R.string.starting_wireless_hotspot)
        is CarPlayStatus.HotspotReady ->
            if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
                getString(R.string.existing_wifi_ready, ssid, band, channel, address)
            } else getString(R.string.status_hotspot_ready, backend, ssid, band, if (channel == 0) getString(R.string.auto_value) else channel.toString())
        CarPlayStatus.WaitingForPairedIphone -> getString(R.string.waiting_for_paired_iphone)
        CarPlayStatus.ConnectingBluetooth -> getString(R.string.connecting_bluetooth)
        CarPlayStatus.RunningWireless -> getString(R.string.wireless_carplay_control_running)
        CarPlayStatus.WirelessActive -> getString(R.string.wireless_carplay_active)
        CarPlayStatus.DiscoveringIphone -> getString(R.string.discovering_iphone)
        CarPlayStatus.WaitingForIphone -> getString(R.string.waiting_for_iphone_over_usb)
        CarPlayStatus.RequestingIphonePermission -> getString(R.string.requesting_iphone_usb_permission)
        CarPlayStatus.WaitingForReenumeration -> getString(R.string.status_waiting_reenumeration)
        CarPlayStatus.SelectingConfiguration -> getString(R.string.selecting_carplay_configuration)
        CarPlayStatus.OpeningDataPaths -> getString(R.string.opening_usb_data_paths)
        CarPlayStatus.Pairing -> getString(R.string.pairing_with_iphone)
        CarPlayStatus.ConnectingControl -> getString(R.string.connecting_iap2_control)
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) getString(R.string.starting_airplay_service) else getString(R.string.status_attaching_ncm)
        CarPlayStatus.RunningControl -> getString(R.string.carplay_control_running)
        CarPlayStatus.ControlEnded -> getString(R.string.carplay_control_window_ended)
        is CarPlayStatus.Failed -> getString(R.string.status_failed, message)
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        /** Framework navigation stream (AUDIO_STREAM_NAVIGATION); not exposed by AudioManager. */
        const val NAVIGATION_STREAM_TYPE = 14
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        /** The instruction-card screen; internal id assigned by AirPlaySession (wire type 111). */
        const val SCREEN_TYPE_ALT_CARD = 112
        // Mirror key: the whole CarPlay main screen on the instrument cluster window.
        const val CLUSTER_MAIN_MIRROR = "cluster-main"
        private const val CENTER_MAP_IDLE_MILLIS = 3_000L // a reconnect is quicker; a session end is not
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val CONFIGURATION_POLL_INTERVAL_MILLIS = 2_000L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        /** How long the panel waits for AirPlay before naming the iPhone-side fix. */
        const val AIRPLAY_STALL_HINT_MILLIS = 60_000L
        const val AUDIO_CAPTURE_MARKER = "audio-capture.enabled"
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val PROTOCOL_TRACE_PREFIX = "TRACE "
        const val GESTURE_MIN_FINGERS = 3
        /**
         * How long the volume keys stay on the guidance stream after a prompt starts. A prompt is
         * a few seconds; the navigation stream on this head unit can stay open much longer, and
         * routing the keys for that whole time leaves the driver unable to change the music.
         */
        const val GUIDANCE_VOLUME_WINDOW_MS = 12_000L
        /**
         * How long the dashboard waits for incall's "projecting" confirmation after the phone
         * connected, before this attempt is dropped. The open sequence alone takes up to ~11 s on
         * this head unit and incall needed 40-50 s in the field, so a longer wait would only make
         * the driver stare at an empty panel; the wheel opens the dashboard by hand.
         */
        const val PROJECTION_CONFIRM_TIMEOUT_MS = 5_000L
        /**
         * How long the start-up prewarm waits before it decides. incall reports its current state
         * within tens of milliseconds of the callback registration; a force-stop + relaunch must
         * not send the announcing sequence to a panel that is still ours.
         */
        const val PREWARM_SETTLE_MS = 1_500L
        /** How often the start-up watcher samples the projection channel. */
        const val WATCHER_PERIOD_MS = 500L
        /** Route-state confirmation window: 0x5201 flips start/end within a second in the field. */
        const val NAV_STATE_CONFIRM_MS = 500L
        /** Grace before a "not projecting" report is treated as the driver handing the panel back. */
        const val PROJECTION_WITHDRAW_CONFIRM_MS = 600L
        /**
         * A real dashboard panel is at least this large and landscape. Before activation incall
         * can present a small preview box on the ext-screen; that must never be mistaken for the
         * panel this window will fill.
         */
        const val CLUSTER_MIN_READY_WIDTH_PX = 800
        const val CLUSTER_MIN_READY_HEIGHT_PX = 400
        /** How often the watcher may repeat "size not ready" in the report. */
        const val CLUSTER_SIZE_WAIT_LOG_MS = 5_000L
        /**
         * Freeze detection. Aligned with the five-second video stats window: three consecutive
         * windows below [FREEZE_KBPS] means the picture has not changed for ~15 s while frames keep
         * arriving. [FREEZE_ACTIVE_KBPS] proves repainting, so a normal static map (which settles
         * around 300-800 kbps) does not trip it.
         */
        const val FREEZE_CHECK_PERIOD_MS = 5_000L
        const val FREEZE_WINDOWS = 3
        const val FREEZE_KBPS = 250L
        const val FREEZE_ACTIVE_KBPS = 800L
        const val FREEZE_STALE_MS = 15_000L
        const val FREEZE_COOLDOWN_MS = 20_000L
        /** First attempts only ask the phone to repaint; after that the session is rebuilt. */
        const val FREEZE_REFRESH_ATTEMPTS = 2
        /**
         * The watcher's first send waits this long for incall to report an already-open channel.
         * The report arrives within tens of milliseconds when the channel is up, so this window
         * only has to cover a channel that incall is still bringing up.
         */
        const val WATCHER_FIRST_SEND_DELAY_MS = 5_000L
        /**
         * After a start-up prewarm, a connect within this window re-asserts silently instead of
         * running the announcing sequence a second time.
         */
        const val PREWARM_SILENT_WINDOW_MS = 25_000L
        // The swipe-down finger count that opens DiPlay settings is user-configurable in
        // DiPlay settings (3/4/5, persistence default 4) and is loaded into
        // settingsGestureFingers; two fingers stay reserved for map pinch-zoom.
        //
        // 56 dp instead of 72: on this head unit a palm-down swipe rarely travels far before the
        // fingers lift, and the old threshold turned most attempts into "no action" (field report
        // 2026-10-20: "the four-finger pull-down is not reliable enough").
        const val THREE_FINGER_SWIPE_DISTANCE_DP = 56
        /**
         * How much more vertical than horizontal a settings pull has to be. 0.62 instead of a
         * strict "dy >= |dx|": a four-finger pull on this panel leaves the fingertips at slightly
         * different heights, so the centroid drifts sideways by half the down-travel and the old
         * test rejected most real swipes. A horizontal dashboard swipe still needs |dx| >= 2*|dy|,
         * so the two gestures do not overlap.
         */
        const val GESTURE_VERTICAL_DOMINANCE = 0.62f
        /** Max vertical travel allowed for a three-finger horizontal swipe; a large
         * vertical swipe (or a steep diagonal) must NOT trigger the dashboard switch. */
        const val GESTURE_VERTICAL_SLOP_DP = 48
        // Normalized (0..1 of the 1920x720 main screen) position of Amap CarPlay's
        // fullscreen/overview toggle button, top-right of the console map. Auto-tapped when
        // the three-finger swipe switches the dashboard to the navigation stream, so Amap
        // renders the detailed follow map there (UNI-V-box behaviour).
        const val MAX_SETTINGS_MENU_WIDTH_PX = 1200
        val MENU_BACKGROUND = Color.rgb(12, 16, 19)
        val MENU_SECONDARY = Color.rgb(170, 180, 190)
        val MENU_ACCENT = Color.rgb(127, 205, 154)
        val MENU_ACCENT_TRACK = Color.rgb(78, 143, 102)
        val MENU_TRACK_OFF = Color.rgb(64, 74, 80)
        val MENU_BUTTON_TEXT = Color.rgb(8, 17, 11)
        val MENU_DANGER = Color.rgb(190, 45, 45)
        val NO_VIDEO_BACKGROUND = Color.rgb(0x16, 0x16, 0x18)
    }

    private data class DisplaySize(val width: Int, val height: Int)
    private data class LogEntry(val timestampMillis: Long, val text: String)
    private data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )
}

internal data class CarPlaySessionDisplay(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hideTopBar: Boolean,
    val hideBottomBar: Boolean,
    // Compare unscaled startup window dimensions, not the scaled video canvas.
    val windowWidth: Int,
    val windowHeight: Int,
    // The view areas the session declared (one per dock edge with a fixed dock), so an activity
    // that adopts the live session can still move the dock without reconnecting.
    val viewAreas: List<AirPlayViewArea>? = null,
    val viewAreaIndex: Int = 0,
)

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
internal object CarPlayBackgroundSession {
    @Volatile var active = false
    private var stopAction: (((() -> Unit)) -> Unit)? = null
    private var restartAction: (() -> Unit)? = null
    private var dockAction: ((CarPlayDock) -> Boolean)? = null
    private var stopping = false
    private var owner: Any? = null
    @Synchronized fun isOwner(candidate: Any): Boolean = owner === candidate
    @Synchronized fun hasSession(): Boolean = stopAction != null || stopping
    private val stopWaiters = mutableListOf<() -> Unit>()

    /** Where the hold diagnostics go; the host activity points this at its own log. */
    @Volatile var logger: ((String) -> Unit)? = null

    /**
     * True while the settings page is in front of CarPlay.
     *
     * Opening settings from the CarPlay screen starts DiPlayActivity, which moves the CarPlay task
     * into the background. Something in that transition ends the session (the driver sees the
     * connect panel instead of the settings page, and the dashboard is handed back - field report
     * 2026-10-20), so a stop request is ignored while this page is open. A disconnect the driver
     * asks for from the settings page calls [releaseSettingsHold] first.
     */
    private var settingsHoldAtMs = 0L

    private const val SETTINGS_HOLD_MAX_MS = 120_000L

    fun holdForSettings() {
        settingsHoldAtMs = SystemClock.elapsedRealtime()
        logger?.invoke("Session: settings page opened, session held")
    }

    fun releaseSettingsHold() {
        if (settingsHoldAtMs != 0L) {
            settingsHoldAtMs = 0L
            logger?.invoke("Session: settings page left, session hold released")
        }
    }

    private fun settingsHoldActive(): Boolean {
        val started = settingsHoldAtMs
        if (started == 0L) return false
        // A hold that was never released (app killed on the settings page) must not block a
        // disconnect forever.
        if (SystemClock.elapsedRealtime() - started > SETTINGS_HOLD_MAX_MS) {
            settingsHoldAtMs = 0L
            return false
        }
        return true
    }

    /**
     * Hot-restarts the running session in place (keeps the cluster projection window alive),
     * used by the settings page so a cluster settings change re-applies without tearing down
     * the activity - which would dismiss the window and re-activate stream 111, making the
     * head unit announce "全屏导航已开启" (field report 10-08).
     */
    @Synchronized fun restartCarPlay() {
        restartAction?.invoke()
    }

    @Synchronized fun setRestart(action: (() -> Unit)?) {
        restartAction = action
    }

    /**
     * Asks the running session to move CarPlay's dock to [dock], which only works between the two
     * fixed edges (the session declared a view area per edge). False means the caller should
     * reconnect instead - to or from the automatic dock, or without a live session.
     */
    @Synchronized fun applyCarPlayDock(dock: CarPlayDock): Boolean =
        dockAction?.invoke(dock) == true

    @Synchronized fun setDockAction(action: ((CarPlayDock) -> Boolean)?) {
        dockAction = action
    }

    fun stop(completion: () -> Unit = {}) {
        if (settingsHoldActive()) {
            val where = Throwable().stackTrace.drop(1).take(7)
                .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
            logger?.invoke("Session: stop ignored while the settings page is open ($where)")
            completion()
            return
        }
        val action: (((() -> Unit)) -> Unit)?
        synchronized(this) {
            if (stopping) { stopWaiters.add(completion); return }
            action = stopAction
            if (action != null) { stopping = true; stopWaiters.add(completion) }
        }
        if (action == null) { completion(); return }
        action.invoke {
            val callbacks = synchronized(this) {
                stopping = false
                stopWaiters.toList().also { stopWaiters.clear() }
            }
            callbacks.forEach { it() }
        }
    }

    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
        val display: CarPlaySessionDisplay,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0
    private var display: CarPlaySessionDisplay? = null

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        val currentDisplay = display ?: return null
        return Snapshot(currentController, currentSink, width, height, currentDisplay)
    }

    @Synchronized
    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int,
        owner: Any, display: CarPlaySessionDisplay, stop: (() -> Unit) -> Unit) {
        this.stopAction = stop
        this.owner = owner
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
        this.display = display
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null, keepOwner: Boolean = false) {
        if (expected != null && controller !== expected) return
        controller = null
        sink = null
        restartAction = null
        dockAction = null
        if (!keepOwner) { stopAction = null; owner = null }
        active = false
        width = 0
        height = 0
        display = null
    }
}
