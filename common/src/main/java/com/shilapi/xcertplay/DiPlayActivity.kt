// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import com.freddyq.diplay.glass.GlassUi
import com.freddyq.diplay.glass.R as GlassR

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.compat.closeCompat
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydFieldSource
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.hud.BydVehicleCapabilities
import com.shilapi.xcertplay.hud.BydVehicleField
import com.shilapi.xcertplay.hud.BydVehicleFieldStore
import com.shilapi.xcertplay.hud.BydVehicleProbeOutcome
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.EvChargingConnectors
import com.shilapi.xcertplay.transport.UsbDiagnostics
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var pendingCarHotspotSetup = false
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var navigationStreamType = 14
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var rootScroll: ScrollView? = null
    private var renderedPage: String? = null
    private var pendingScrollY: Int? = null
    /** Group mask applied to sections built right now; late/async builders leave it at GROUP_ALL. */
    private var activeSectionGroups = GROUP_ALL
    /** Rail selection on the settings page (survives re-renders, including ADB flow re-renders). */
    private var settingsGroup = GROUP_FREQUENT
    /**
     * Sections are collected here while the settings page is built and then distributed over the
     * two content columns, so switching groups re-flows the same view objects (no duplicate
     * widgets writing the same preference) and the layout can balance the columns by row count.
     */
    private var settingsSectionSink: MutableList<View>? = null
    /** The detached column sections are built into; only its children go to [settingsSectionSink]. */
    private var settingsStaging: LinearLayout? = null
    private var settingsLeft: LinearLayout? = null
    private var settingsRight: LinearLayout? = null
    /** Settings only: the right column's own scroll view, so the rail and header stay pinned. */
    private var settingsContentScroll: ScrollView? = null
    /** Settings text scale: the head unit is read from arm's length, 15sp rows looked like fine print. */
    private var textScale = 1f
    private var bydVehicleAdvancedExpanded = false
    private var adbAccessState: BydAdbAccess.State? = null
    private var adbCheckInProgress = false
    private var adbCheckMayAsk = false
    private var adbCheckFailed = false
    private var vehicleProbeAuthorizationInProgress = false
    private var vehicleProbeInProgress = false
    private var vehicleProbeOutcome: BydVehicleProbeOutcome? = null
    private var adbCheckGeneration = 0
    private var adbStatus: TextView? = null
    private var bydAdbControls: LinearLayout? = null
    private var adbSwitchChangePending = false
    private var pausedForAdbSwitchChange = false
    private var updatingAdbSwitches = false
    private val adbSwitches = mutableMapOf<Int, Pair<Switch, () -> Boolean>>()
    private var hotspotStartupResult: CarHotspotTethering.Result? = null
    @Volatile private var startupHotspotCancelled = false
    @Volatile private var vehicleProbeGeneration = 0
    @Volatile private var vehicleValidationGeneration = 0
    private val vehicleOperationLock = Any()
    private var automaticVehicleValidationStarted = false
    private var automaticVehicleValidationInProgress = false
    private var automaticVehicleValidationPending = false
    private var pendingVehicleReplacement: BydVehicleCapabilities? = null
    // The saved snapshot [pendingVehicleReplacement] was compared with.
    private var pendingVehicleReplacementExpected: BydVehicleCapabilities? = null
    private var pendingVehicleLostFields: Set<BydVehicleField> = emptySet()
    private var defaultVehicleStatus: BydAdbAccess.Status? = null
    private var vehicleDataReconnectPending = false
    private val automaticVehicleValidation = Runnable {
        automaticVehicleValidationPending = false
        validateSavedVehicleConfigurationAutomatically()
    }
    private data class VehicleProbeAttempt(
        val outcome: BydVehicleProbeOutcome,
        val heldCandidate: BydVehicleCapabilities? = null,
        val lostFields: Set<BydVehicleField> = emptySet(),
        val snapshotChanged: Boolean = false,
        val allowedOnlyOnce: Boolean = false,
    )

    private data class VehicleValidationAttempt(
        val status: BydAdbAccess.Status,
        val outcome: BydVehicleProbeOutcome? = null,
        val heldCandidate: BydVehicleCapabilities? = null,
        val lostFields: Set<BydVehicleField> = emptySet(),
        val snapshotChanged: Boolean = false,
        val savedFieldsReadable: Boolean = false,
    )
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) {
            applyLocationReporting(true)
        } else {
            render()
            permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
        }
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DiPlayAppExit.allowEntry(this, intent, launcher = true)) { finishAndRemoveTask(); return }
        DiPlayStartupTrace.log(this, "DiPlayActivity.onCreate begin")
        languagePreferenceAtCreate = AppLocale.preference(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
            hide(WindowInsetsCompat.Type.statusBars())
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        bydVehicleAdvancedExpanded = savedInstanceState?.getBoolean("byd_vehicle_advanced") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        // The authentication bootstrap reads certificate files off disk and parses them. Nothing on
        // the first page needs it (only connecting does), so it runs after the frame is drawn and
        // connect() re-checks it synchronously.
        window.decorView.post {
            DiPlayStartupTrace.log(this, "bootstrap start")
            setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
                android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
                getString(R.string.setup_error_auth)
            }
            DiPlayStartupTrace.log(this, "bootstrap done")
            if (setupError != null && page == "home") render()
        }
        render()
        DiPlayStartupTrace.log(this, "render done (page=$page)")
        // First frame: the moment the driver actually sees something. The listener removes itself
        // on the first draw, and this guard also covers callbacks already dispatched for that frame
        // (a field log showed 1482 copies of the line on one entry).
        window.decorView.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            private var reported = false

            override fun onDraw() {
                if (reported) return
                reported = true
                runCatching {
                    window.decorView.viewTreeObserver.removeOnDrawListener(this)
                }
                DiPlayStartupTrace.log(this@DiPlayActivity, "first frame drawn")
            }
        })
        scheduleAutomaticVehicleValidation()
        handleWirelessRecovery()
        // Moved off the first-frame path: it probes the HUD output service and reads the battery,
        // neither of which the driver needs before the page is visible.
        window.decorView.post {
            runCatching { com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext) }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") { page = "home"; render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (!DiPlayAppExit.allowEntry(this, intent, launcher = true)) { finishAndRemoveTask(); return }
        page = intent.getStringExtra("page") ?: "home"; render()
        automaticVehicleValidationStarted = false
        scheduleAutomaticVehicleValidation()
        handleWirelessRecovery()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        outState.putBoolean("byd_vehicle_advanced", bydVehicleAdvancedExpanded)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    private fun openOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        if (runCatching { startActivity(intent) }.isFailure) {
            android.widget.Toast.makeText(this, R.string.center_map_no_permission_screen, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        CenterMapOverlay.onDiPlayScreenShown()
    }

    override fun onStop() {
        startupHotspotCancelled = true
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && !adbSwitchChangePending && !pausedForAdbSwitchChange &&
            (page == "home" || page == "settings" || page == "connection")) render()
        pausedForAdbSwitchChange = false
        val hotspotApp = applicationContext
        val managedHotspot = com.freddyq.diplay.customization.VehicleCustomizations.onApplicationForeground(
            this, com.freddyq.diplay.customization.ApplicationForegroundControl {
                com.freddyq.diplay.customization.ApplicationForeground(
                    AirPlayPersistence.loadWirelessEnabled(hotspotApp),
                    AirPlayPersistence.loadWirelessHotspotMode(hotspotApp) == WirelessHotspotMode.MANUAL,
                    CarPlayBackgroundSession.active,
                )
            },
        )
        if (initialLaunch) {
            initialLaunch = false
            if (!managedHotspot) startCarHotspotOnLaunch()
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
    }
    override fun onPause() {
        pausedForAdbSwitchChange = adbSwitchChangePending
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(automaticVehicleValidation)
        adbCheckGeneration++
        synchronized(vehicleOperationLock) {
            vehicleProbeGeneration++
            vehicleValidationGeneration++
        }
        adbCheckInProgress = false
        vehicleProbeAuthorizationInProgress = false
        vehicleProbeInProgress = false
        automaticVehicleValidationInProgress = false
        super.onDestroy()
    }

    private fun render() {
        // A restore still waiting for layout keeps its target: the old page was never laid out.
        val previousScrollY = (pendingScrollY ?: rootScroll?.scrollY)?.takeIf { renderedPage == page }
        status = null; connectButton = null; disconnectButton = null; lastRunning = null
        bydAdbControls = null
        adbSwitches.clear()
        adbStatus = null
        settingsSectionSink = null
        settingsLeft = null
        settingsRight = null
        settingsContentScroll = null
        textScale = 1f
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        rootScroll = scroll
        val content = column().apply { setPadding(dp(32), dp(24), dp(32), dp(32)) }
        scroll.addView(content)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_carplay); contentDescription = getString(R.string.carplay) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        header.addView(label(getString(GlassR.string.glass_brand), 23, TEXT, true).apply { setPadding(dp(12), 0, dp(16), 0) }, LinearLayout.LayoutParams(0, dp(56), 1f))
        header.addView(button(if (page == "home") getString(R.string.car_home) else getString(R.string.back), false) {
            if (page == "home") startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            else { page = "home"; render() }
        }, LinearLayout.LayoutParams(dp(130), dp(56)))
        content.addView(header)
        content.addView(space(24))
        // Settings keeps its rail and page header pinned while the right column scrolls on its own.
        // That needs the body OUTSIDE this ScrollView: nested ScrollViews would let the outer one
        // drag the rail away, which is exactly what the driver complained about.
        var settingsBody: LinearLayout? = null
        // Never let a single UI bug take down the whole settings/home page (the head-unit has
        // no logcat, so a crash is otherwise invisible). Render failures show a recoverable
        // error card and write the stack into the diagnostic log (exported diplay.log).
        try {
            when (page) {
                "connection" -> connectionSetup(content)
                "settings" -> settingsBody = settings(content)
                "about" -> about(content)
                else -> home(content)
            }
        } catch (t: Throwable) {
            Log.e("DiPlay", "render page=$page failed", t)
            runCatching {
                val logDir = File(filesDir, "logs"); logDir.mkdirs()
                val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                File(logDir, "diplay.log").appendText(
                    "\n===== RENDER FAIL page=$page $stamp =====\n" + Log.getStackTraceString(t) + "\n",
                )
            }
            content.addView(label("设置页渲染失败（$page），错误详情已写入诊断日志", 16, Color.rgb(255, 150, 150), true)
                .apply { setPadding(0, dp(16), 0, dp(8)) })
            content.addView(label("重启 App 后导出日志即可看到具体错误位置", 14, MUTED))
        }
        val root: ViewGroup = if (settingsBody != null) {
            column().apply {
                addView(scroll, LinearLayout.LayoutParams(-1, -2))
                addView(settingsBody, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        } else {
            scroll
        }
        // Scroll restore belongs to whichever view actually scrolls on this page.
        val restoreTarget = settingsContentScroll ?: scroll
        rootScroll = restoreTarget
        root.background = GlassUi.backdrop()
        setContentView(root)
        renderedPage = page
        refreshStatus()
        pendingScrollY = previousScrollY
        // A stopped window still dispatches pre-draw but skips layout, so wait for a real layout;
        // the listener stays on this view and goes away with it.
        previousScrollY?.let { y ->
            restoreTarget.doOnLayout {
                if (rootScroll === restoreTarget) {
                    restoreTarget.scrollTo(0, y)
                    pendingScrollY = null
                }
            }
        }
    }

    private fun home(content: LinearLayout) {
        val wide = resources.configuration.screenWidthDp >= 850
        val body = column()
        val left = column()
        left.addView(label(getString(GlassR.string.glass_eyebrow), 12, ACCENT, true).apply { letterSpacing = .16f })
        left.addView(label(getString(GlassR.string.glass_home_title), if (wide) 36 else 30, TEXT, true).apply { setPadding(0, dp(10), 0, dp(8)) })
        left.addView(label(getString(GlassR.string.glass_home_description), 17, MUTED))
        val card = card()
        card.addView(label(getString(R.string.wireless_carplay), 12, ACCENT, true).apply { letterSpacing = .12f })
        status = label(getString(R.string.ready_when_you_are), 24, TEXT, true).apply { setPadding(0, dp(10), 0, dp(16)) }
        card.addView(status)
        connectButton = button(getString(R.string.connect_phone), true) {
            if (CarPlayBackgroundSession.hasSession()) launchCarPlayScreen()
            else connect(true)
        }
        card.addView(connectButton, matchButton())
        val connectionHint = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
            WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_hint)
            WirelessHotspotMode.MANUAL -> getString(R.string.hotspot_hint_manual)
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.hotspot_hint_local)
            else -> getString(R.string.hotspot_hint_p2p)
        }
        card.addView(label(connectionHint, 15, MUTED).apply { setPadding(0, dp(14), 0, 0) })
        val startupProblem = hotspotStartupResult?.takeIf {
            it != CarHotspotTethering.Result.READY && it != CarHotspotTethering.Result.CANCELLED &&
                CarHotspotSettings.shouldEnable(this, true, AirPlayPersistence.loadWirelessHotspotMode(this)) &&
                com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) != true
        }
        if (startupProblem != null) {
            card.addView(label(hotspotResultText(startupProblem), 15, WARNING))
            card.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(10, 56))
        } else if (carHotspotOff()) {
            card.addView(label(getString(R.string.msg_car_hotspot_off, AirPlayPersistence.loadManualHotspotSsid(this)), 15, WARNING).apply { setPadding(0, dp(14), 0, 0) })
            card.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(10, 56))
        }
        card.addView(button(getString(R.string.choose_iphone), false) { choosePhone() }, matchButton(16, 56))
        disconnectButton = button(getString(R.string.disconnect), false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.releaseSettingsHold()
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }.apply { visibility = View.GONE }
        card.addView(disconnectButton, matchButton(10, 56))
        val right = card().apply { gravity = Gravity.CENTER_HORIZONTAL }
        right.addView(label(getString(GlassR.string.glass_quick_actions), 12, ACCENT, true).apply {
            letterSpacing = .1f; setPadding(0, 0, 0, dp(16))
        }, LinearLayout.LayoutParams(-1, -2))
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val branding = column().apply {
            gravity = Gravity.CENTER
            addView(logo, LinearLayout.LayoutParams(dp(72), dp(72)))
        }
        right.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton())
        right.addView(label(getString(R.string.plug_your_iphone_into_a_usb_data_port_allow_carplay_when_y), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(24)) })
        right.addView(button(getString(R.string.settings), false) { page = "settings"; render() }, matchButton())
        right.addView(label(getString(GlassR.string.glass_settings_hint), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(20)) })
        right.addView(button(DiPlayAppExit.title(this), false) { DiPlayAppExit.request(this) }, matchButton(12, 56))
        DiPlayAppExit.description(this)?.let { right.addView(label(it, 13, MUTED)) }
        right.addView(label("${getString(homeVersionPrefix())}${version()}", 12, MUTED).apply { letterSpacing = .08f; setPadding(0, dp(16), 0, 0) })
        if (wide) {
            // Both rows share column widths. The USB button starts at the wireless
            // card's top edge, independently of hero wrapping or font scaling.
            fun columns(first: View, second: View, stretchSecond: Boolean = false) = row().apply {
                gravity = Gravity.TOP
                addView(first, LinearLayout.LayoutParams(0, -2, 1.6f))
                addView(space(24), LinearLayout.LayoutParams(dp(24), 1))
                addView(second, LinearLayout.LayoutParams(0, if (stretchSecond) -1 else -2, 1f))
            }
            body.addView(columns(left, branding, true))
            body.addView(space(26))
            body.addView(columns(card, right))
        } else {
            body.addView(left)
            body.addView(space(26))
            body.addView(card)
            body.addView(space(26))
            body.addView(branding)
            body.addView(space(24))
            body.addView(right)
        }
        setupError?.let { body.addView(label(it, 16, WARNING).apply { setPadding(0, dp(16), 0, 0) }) }
        content.addView(body)
    }

    /**
     * Builds the settings page and returns the body row, which [render] places outside the outer
     * ScrollView: the rail and the page header stay on screen while only the right column scrolls.
     */
    private fun settings(host: LinearLayout): LinearLayout {
        // Read from arm's length: 15sp row titles were fine print on a 1920x720 panel.
        textScale = 1.22f
        host.addView(label(getString(GlassR.string.glass_settings_title), 23, TEXT, true))
        host.addView(label(getString(R.string.apply_reconnects_carplay_for_size_resolution_music_buffer), 11, MUTED).apply { setPadding(0, dp(6), 0, dp(10)) })
        // Rail plus two content columns. Sections are collected first and distributed later, so a
        // preference never has two widgets and the page uses the panel's width instead of leaving a
        // third of it blank.
        // The rail is pinned, but a short panel (or a large font scale) cannot hold six rows plus
        // the page header, so it gets its own scroll: the rail stays put, only its own content
        // scrolls. Never a nested scroll with the page, which would drag the rail away again.
        val rail = column()
        val railScroll = ScrollView(this).apply {
            background = GlassUi.panel(this@DiPlayActivity)
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(dp(212), -1)
        }
        val left = column().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        val right = column().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        settingsLeft = left
        settingsRight = right
        val staging = column()
        settingsStaging = staging
        settingsSectionSink = mutableListOf()
        val content = staging
        fun gap(width: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), -1) }
        val rightScroll = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        settingsContentScroll = rightScroll
        val columns = row().apply { gravity = Gravity.TOP }
        columns.addView(left)
        columns.addView(gap(20))
        columns.addView(right)
        rightScroll.addView(columns)
        val body = row().apply { gravity = Gravity.TOP }
        buildSettingsRail(rail)
        railScroll.addView(rail)
        body.addView(railScroll)
        body.addView(gap(20))
        body.addView(rightScroll)

        activeSectionGroups = GROUP_CONNECT
        section(content, getString(R.string.carplay_controls), R.drawable.ic_dp_display) { card ->
            val gestureFingers = listOf(3, 4, 5)
            choice(card, getString(R.string.settings_gesture_fingers_label),
                gestureFingers.map { getString(R.string.settings_gesture_fingers_option, it) },
                gestureFingers.indexOf(AirPlayPersistence.loadSettingsGestureFingers(this)).coerceAtLeast(0),
                reconnects = false) {
                AirPlayPersistence.saveSettingsGestureFingers(this, gestureFingers[it])
            }
            card.addView(label(getString(R.string.settings_gesture_fingers_hint), 14, MUTED).apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
        activeSectionGroups = GROUP_CONNECT
        section(content, getString(R.string.connection_setup), R.drawable.ic_dp_connection) { card ->
            card.addView(label(getString(R.string.choose_how_to_connect_follow_the_setup_steps_and_save_your), 16, MUTED))
            card.addView(button(getString(R.string.open_connection_setup), false) { page = "connection"; render() }, matchButton(12, 60))
        }
        activeSectionGroups = GROUP_SYSTEM
        section(content, getString(R.string.diagnostics), R.drawable.ic_dp_diagnostics) { card ->
            exportButton = button(if (exportInProgress) getString(R.string.saving_report) else getString(R.string.save_diagnostic_report), false) {
                exportDiagnostics()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            card.addView(button(getString(R.string.choose_save_location), false) { chooseReportDestination() }, matchButton(10, 60))
            // Developer rows stay folded away: the driver only needs the report and its location.
            val developerRows = column().apply {
                visibility = if (DiPlayPreferences.showDeveloperOptions(this@DiPlayActivity)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                addView(button(getString(R.string.usb_diagnostics), false) { runUsbDiagnostics() }, matchButton(10, 60))
                addView(button(getString(R.string.vpn_root_grant), false) { runVpnRootGrant() }, matchButton(10, 60))
                addView(button(getString(R.string.show_diagnostics_on_screen), false) { showDiagnosticsOnScreen() }, matchButton(10, 60))
                addView(button(getString(R.string.clear_logs), false) { clearDiagnosticLogs() }, matchButton(10, 60))
            }
            toggle(
                card,
                getString(R.string.settings_developer_options),
                getString(R.string.settings_developer_options_hint),
                DiPlayPreferences.showDeveloperOptions(this),
            ) { enabled ->
                DiPlayPreferences.saveShowDeveloperOptions(this, enabled)
                developerRows.visibility = if (enabled) View.VISIBLE else View.GONE
            }
            card.addView(developerRows)
            val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getString(R.string.reports_save_to_downloads_diplay) else getString(R.string.reports_save_to_app_dir)
            card.addView(label(destination + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
        activeSectionGroups = GROUP_FREQUENT or GROUP_CONNECT
        section(content, getString(R.string.automatic_connection), R.drawable.ic_dp_automation) { card ->
            toggle(card, getString(R.string.connect_when_diplay_opens), getString(R.string.use_your_last_connection_type_and_selected_iphone), DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            adbToggle(card, R.string.open_after_the_car_starts,
                R.string.availability_depends_on_your_head_unit_s_startup_settings,
                read = { AirPlayPersistence.loadAutoStartOnBoot(this) },
                needsAdb = { CarHotspotSettings.enabled(this) &&
                    AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL },
                permissions = { listOf(CarHotspotSetup.Permission.BOOT_LAUNCH) }) {
                AirPlayPersistence.saveAutoStartOnBoot(this, it)
            }
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
        }
        activeSectionGroups = GROUP_SYSTEM
        bydAdbSettings(content)
        activeSectionGroups = GROUP_DISPLAY
        section(content, getString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
            carPlaySizeControl(card)
            // 50 % and 40 % exist for a small-canvas A/B: a 2560x1600 panel at 50 % is 1280x800,
            // which is where other receivers usually run and where iOS renders an app's first
            // frame fastest.
            val scaleSteps = listOf(10, 8, 6, 5, 4)
            val scaleLabels = listOf(
                getString(R.string.resolution_native),
                getString(R.string.s_80_lighter_load),
                getString(R.string.s_60_lightest_load),
                getString(R.string.resolution_percent_value, 50),
                getString(R.string.resolution_percent_value, 40),
            )
            choice(card, getString(R.string.resolution), scaleLabels,
                scaleSteps.indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveDisplayScaleTenths(this, scaleSteps[it])
            }
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            // Labels are derived from the presets, never written out by hand: the old hard-coded
            // three labels had already drifted from the values once (a row named "500 ms" whose
            // content read 1000 ms), and a stale label here silently mis-directs the driver.
            val bufferLabels = bufferPresets.map { ms ->
                val hint = when (ms) {
                    com.shilapi.xcertplay.media.MediaAudioBuffer.DEFAULT_MILLIS ->
                        getString(R.string.s_buffer_hint_default)
                    bufferPresets.max() -> getString(R.string.s_buffer_hint_stable)
                    bufferPresets.min() -> getString(R.string.s_buffer_hint_low_latency)
                    else -> null
                }
                if (hint == null) getString(R.string.s_ms_value, ms)
                else getString(R.string.s_ms_value_hint, ms, hint)
            }
            choice(card, getString(R.string.music_buffer), bufferLabels,
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
            choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            toggle(card, getString(R.string.efficient_video), getString(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility), AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) }
            toggle(card, getString(R.string.right_hand_drive), getString(R.string.place_carplay_s_controls_closer_to_the_driver), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
            toggle(card, getString(R.string.full_screen), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)) {
                AirPlayPersistence.saveHideTopBar(this, it); AirPlayPersistence.saveHideBottomBar(this, it)
            }
            val nightModes = CarPlayNightMode.entries
            // Labels follow the enum order, so adding or dropping an option can never shift a value.
            val nightLabels = nightModes.map {
                getString(
                    when (it) {
                        CarPlayNightMode.SYSTEM -> R.string.carplay_night_system
                        CarPlayNightMode.DAY -> R.string.carplay_night_day
                        CarPlayNightMode.NIGHT -> R.string.carplay_night_night
                    },
                )
            }
            choice(
                card,
                getString(R.string.carplay_night_mode),
                nightLabels,
                nightModes.indexOf(AirPlayPersistence.loadCarPlayNightMode(this)),
                reconnects = false,
            ) { index ->
                AirPlayPersistence.saveCarPlayNightMode(this, nightModes[index])
            }
            card.addView(label(getString(R.string.carplay_night_hint), 14, MUTED))
            card.addView(label(getString(R.string.carplay_night_time_note), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            val docks = CarPlayDock.entries
            // Labels follow the enum order, so adding or dropping an option can never shift a value.
            val dockLabels = docks.map {
                getString(
                    when (it) {
                        CarPlayDock.AUTOMATIC -> R.string.carplay_dock_automatic
                        CarPlayDock.DRIVER_SIDE -> R.string.carplay_dock_driver_side
                        CarPlayDock.BOTTOM -> R.string.carplay_dock_bottom
                    },
                )
            }
            choice(
                card,
                getString(R.string.carplay_dock),
                dockLabels,
                docks.indexOf(CarPlayDock.load(this)),
                reconnects = false,
            ) { index ->
                val next = docks[index]
                val from = CarPlayDock.load(this)
                CarPlayDock.save(this, next)
                // Between the two fixed edges the running session declares an area per edge, so
                // the dock moves live with updateViewArea. Anything that touches Automatic
                // changes what /info declares and applies on the next connection.
                if (CarPlayDock.movesLive(from, next) && CarPlayBackgroundSession.applyCarPlayDock(next)) {
                    toast(getString(R.string.carplay_dock_moved))
                } else if (CarPlayBackgroundSession.hasSession()) {
                    connect(AirPlayPersistence.loadWirelessEnabled(this))
                }
            }
            card.addView(label(getString(R.string.carplay_dock_hint), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            card.addView(button(getString(R.string.picture_adjustments), false) {
                startActivity(Intent(this, CarPlayHostActivity::class.java)
                    .putExtra("picture_controls", true).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, matchButton(0, 56).apply { bottomMargin = dp(24) })
        }
        activeSectionGroups = GROUP_FREQUENT or GROUP_SOUND
        section(content, getString(R.string.audio_routing)) { card ->
            toggle(card, getString(R.string.contrib_audio_home_toggle_audio_focus), getString(R.string.contrib_audio_home_toggle_audio_focus_desc), AirPlayPersistence.loadAudioFocusEnabled(this)) { AirPlayPersistence.saveAudioFocusEnabled(this, it) }
            if (resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
                toggle(card, getString(R.string.advanced_audio_channel_mapping),
                    getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                    AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
                    AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it)
                }
            }
            mediaChannelControl(card)
            navigationChannelControl(card)
            // Audio always stays on CarPlay; the switch only decides who answers the wheel's
            // media keys. With it on, this app swallows them so the car's Bluetooth (AVRCP)
            // controls the phone once instead of the press being handled twice.
            if (com.freddyq.diplay.customization.VehicleCustomizations.mediaKeyRoute(this) == null) toggle(card, getString(R.string.media_audio_via_bluetooth),
                getString(R.string.media_audio_via_bluetooth_description),
                AirPlayPersistence.loadMediaViaCarBluetooth(this)) {
                AirPlayPersistence.saveMediaViaCarBluetooth(this, it)
                render()
            }
            navDuckPercentControl(card)
        }
        activeSectionGroups = GROUP_CONNECT
        section(content, getString(R.string.location), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.report_location_to_iphone),
                getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                AirPlayPersistence.loadLocationReportingEnabled(this), save = ::onLocationReportingChanged)
            card.addView(label(getString(R.string.location_reporting_reconnects), 14, MUTED))
            card.addView(button(getString(if (bydVehicleAdvancedExpanded)
                R.string.hide_advanced_vehicle_data else R.string.advanced_vehicle_data), false) {
                bydVehicleAdvancedExpanded = !bydVehicleAdvancedExpanded
                render()
            }, matchButton(12, 56))
            if (bydVehicleAdvancedExpanded) {
                advancedVehicleData(card)
            }
        }
        // 仪表盘分区始终渲染：即使当前未检测到投影 display（模拟器、未连接车机），也允许
        // 预先配置裁剪、坐标、车标等；车机上连接后自动生效。全部控件包 runCatching，任一
        // 控件渲染异常仅隐藏该控件，绝不使设置页闪退。
        activeSectionGroups = GROUP_CLUSTER
        runCatching {
            val clusterDisplayPresent = ClusterMapPresentation.findDisplay(this) != null
            section(content, getString(R.string.instrument_cluster_section), R.drawable.ic_dp_navigation) { card ->
                if (!clusterDisplayPresent) {
                    card.addView(label(getString(R.string.cluster_no_display_hint), 14, MUTED).apply {
                        setPadding(0, 0, 0, dp(12))
                    })
                }
                toggle(
                    card,
                    getString(R.string.cluster_auto_nav_view),
                    getString(R.string.cluster_auto_nav_view_desc),
                    AirPlayPersistence.loadClusterAutoNavMode(this),
                ) { enabled ->
                    AirPlayPersistence.saveClusterAutoNavMode(this, enabled)
                }
                choice(card, getString(R.string.instrument_cluster_content),
                    listOf(
                        getString(R.string.instrument_cluster_entire_interface),
                        getString(R.string.instrument_cluster_navigation_stream),
                    ),
                    if (AirPlayPersistence.loadClusterMirrorMain(this)) 0 else 1) { idx ->
                    AirPlayPersistence.saveClusterMirrorMain(this, idx == 0)
                    reconnectForClusterMap()
                }
                // Which cluster content the iPhone streams for the nav-only view. Apple's own views
                // are the only source of lane guidance and the junction zoom image; the "map + cards"
                // entry keeps the locally drawn, movable cards.
                // Three choices: plain map, local movable cards, or native dual-stream navigation.
                // Keep stored MAP selectable and make the whole local navigation group opt-in.
                // Apple's single-stream instruments view was removed on request 2026-10-24 - the
                // full navigation mode covers it and looks better.
                val clusterContentChoices = listOf(
                    CarPlayClusterDisplay.Content.MAP to R.string.cluster_content_map_only,
                    CarPlayClusterDisplay.Content.MAP_WITH_CUSTOM_CARD to R.string.cluster_content_custom,
                    CarPlayClusterDisplay.Content.FLY_CARD to R.string.cluster_content_flycard,
                )
                choice(
                    card,
                    getString(R.string.cluster_content_title),
                    clusterContentChoices.map { getString(it.second) },
                    clusterContentChoices.indexOfFirst {
                        it.first == AirPlayPersistence.loadClusterContent(this)
                    }.coerceAtLeast(0),
                ) { idx ->
                    clusterContentChoices.getOrNull(idx)?.let { (content, _) ->
                        AirPlayPersistence.saveClusterContent(this, content)
                    }
                    reconnectForClusterMap()
                }
                card.addView(label(getString(R.string.cluster_content_hint), 13, MUTED).apply {
                    setPadding(0, 0, 0, dp(10))
                })
                card.addView(button(getString(R.string.cluster_crop_adjust), false) {
                    startActivity(Intent(this, CarPlayHostActivity::class.java)
                        .putExtra("cluster_crop_controls", true).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }, matchButton(0, 56))
                // 整机镜像左侧裁剪：裁掉 CarPlay 主屏左侧栏（dock/工具栏）的固定像素宽度，
                // 仅整机镜像模式生效。独立于其余校准控件渲染，保证该入口始终可见可调；
                // 渲染失败写入诊断日志。注意：标题只能加入行容器，不能先 addView 到 card
                // 再移入行容器（会抛 IllegalStateException: child already has a parent）。
                runCatching {
                    val cropTitle = label(getString(R.string.cluster_mirror_left_crop), 16, TEXT, true)
                    cropTitle.setPadding(0, dp(12), 0, 0)
                    val cropValue = label(
                        "${AirPlayPersistence.loadClusterMirrorLeftCropPx(this@DiPlayActivity)} px",
                        16, ACCENT, true,
                    )
                    val cropHead = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(cropTitle, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(cropValue)
                    }
                    card.addView(cropHead, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    val cropSlider = SeekBar(this).apply {
                        max = 400
                        progress = AirPlayPersistence.loadClusterMirrorLeftCropPx(this@DiPlayActivity).coerceIn(0, 400)
                        // Do NOT set minHeight here: the head-unit ROM strips SeekBar#setMinHeight
                        // and throws NoSuchMethodError, which kills the whole slider row. The row
                        // LayoutParams below already fix the height.
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                                cropValue.text = "$progress px"
                                if (fromUser) {
                                    AirPlayPersistence.saveClusterMirrorLeftCropPx(this@DiPlayActivity, progress)
                                }
                            }

                            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                                // No reconnect on release: the left-rail crop is applied locally by
                                // the cluster TextureView transform (refreshCrop), not by the incall
                                // session, so the saved value takes effect on the next (re)connect /
                                // auto-project. Reconnecting here re-activates the 111 nav stream,
                                // which makes the head unit announce "全屏导航已开启" - a voice the
                                // driver explicitly does not want after adjusting the crop.
                            }
                        })
                    }
                    card.addView(cropSlider, LinearLayout.LayoutParams(-1, dp(44)))
                }.onFailure { e ->
                    clusterUiLog("整机镜像左侧裁剪控件渲染失败: ${e.message ?: e.javaClass.simpleName}")
                }
                // The only auto-projection switch left: the old separate "连接后自动投屏" did the
                // same thing, so the two were merged into this one (see applyDefaultsRevision).
                // Unchecked = no auto projection at all; the three-finger swipe still works.
                toggle(card, getString(R.string.auto_fly_screen),
                    getString(R.string.auto_fly_screen_desc),
                    AirPlayPersistence.loadAutoFlyScreen(this)) {
                    AirPlayPersistence.saveAutoFlyScreen(this, it)
                }
                // 冷启动偶发失败的兜底：投屏重试阶梯。
                toggle(card, getString(R.string.auto_project_retry),
                    getString(R.string.auto_project_retry_desc),
                    AirPlayPersistence.loadAutoProjectRetry(this)) {
                    AirPlayPersistence.saveAutoProjectRetry(this, it)
                }
                // 车辆图标名称（OEM 标签）由 iOS 端按该文字渲染车机图标。
                // 全部用朴素原生控件，任一渲染异常仅隐藏该控件并把原因写入诊断日志。
                runCatching {
                    val oemTitle = label(getString(R.string.oem_label_home), 16, TEXT, true)
                    oemTitle.setPadding(0, dp(12), 0, 0)
                    card.addView(oemTitle)
                    val oemInput = EditText(this).apply {
                        setText(AirPlayPersistence.loadOemLabel(this@DiPlayActivity))
                        textSize = 18f
                        setTextColor(Color.WHITE)
                        setHintTextColor(TEXT)
                        backgroundTintList = ColorStateList.valueOf(ACCENT)
                        minHeight = dp(48)
                        isSingleLine = true
                        setPadding(dp(12), 0, dp(12), 0)
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                            override fun afterTextChanged(s: Editable?) {
                                AirPlayPersistence.saveOemLabel(this@DiPlayActivity, s?.toString()?.trim() ?: "")
                            }
                        })
                    }
                    card.addView(oemInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
                }.onFailure { e ->
                    clusterUiLog("车辆图标名称控件渲染失败: ${e.message ?: e.javaClass.simpleName}")
                }
                // 通用仪表盘控件（原位于“车载导航”分区，仅 BYD 车机可见；长安等车机看不到，
                // 故移入“仪表盘”分区）。
                // The original "dashboard shows" picker is gone: it only chose between Apple's
                // turn card and the map, and this build draws its own cards (see the per-card
                // blocks below), so the two settings duplicated each other. The content is now
                // fixed to the map, which is what the custom cards are drawn on.
                val customCard = CarPlayClusterDisplay.usesCustomTurnCard(AirPlayPersistence.loadClusterContent(this))
                // —— 长安仪表盘卡片设置（始终可见）——
                // 位置设置始终保留入口；整组导航卡片仅在选中自定义卡片内容时显示，
                // 切换成纯地图不会删除保存的位置和各卡片开关。
                // 预览与保存分离：滑块只更新预览/暂存（pending），点击 Apply 才写入持久化并
                // 通过 overlaySettingsListener 立即刷新仪表盘上的真实卡片。
                val pendingXY = mutableMapOf(
                    "eta" to Pair(
                        AirPlayPersistence.loadClusterCardX(this, "eta"),
                        AirPlayPersistence.loadClusterCardY(this, "eta"),
                    ),
                    // The Apple full-navigation card WINDOW (FLY_CARD). Same store as the drawn
                    // cards, different key: the phone paints that card, we only place the window.
                    "apple" to Pair(
                        AirPlayPersistence.loadClusterCardX(this, "apple"),
                        AirPlayPersistence.loadClusterCardY(this, "apple"),
                    ),
                    "exit" to Pair(
                        AirPlayPersistence.loadClusterCardX(this, "exit"),
                        AirPlayPersistence.loadClusterCardY(this, "exit"),
                    ),
                    "turn" to Pair(
                        AirPlayPersistence.loadClusterTurnCardX(this),
                        AirPlayPersistence.loadClusterTurnCardY(this),
                    ),
                    "song" to Pair(
                        AirPlayPersistence.loadClusterCardX(this, "song"),
                        AirPlayPersistence.loadClusterCardY(this, "song"),
                    ),
                )
                // The turn card is drawn by us in every cluster content mode, so its settings must
                // stay reachable whatever "仪表盘画面来源" is set to. Only the upstream BYD path still
                // ties the card to the content choice.
                val turnCardSettings = !DiLink51ClusterLayout.supported() || customCard
                if (turnCardSettings) {
                    // One inset block per dashboard card: its show switch, its own size and its own
                    // position together. The size sliders used to sit in one run and every card's
                    // X/Y rows in another, so "车道指引卡片" was three rows scattered between two
                    // other cards' rows and its size was somewhere else entirely.
                    cardGroup(card, getString(R.string.cluster_card_turn_title)) { group ->
                        cardScaleRow(group, getString(R.string.card_scale_turn), "turn")
                        // Background strength, same control the song card has (2026-10-25).
                        percentRow(
                            group,
                            getString(R.string.cluster_card_background_alpha),
                            AirPlayPersistence.loadClusterCardAlpha(this, "turn"),
                        ) { percent ->
                            AirPlayPersistence.saveClusterCardAlpha(this, "turn", percent)
                        }
                        toggle(group, getString(R.string.turn_card_show),
                            getString(R.string.cluster_card_show_desc),
                            AirPlayPersistence.loadClusterTurnCardVisible(this)) { visible ->
                            AirPlayPersistence.saveClusterTurnCardVisible(this, visible)
                        }
                        group.addView(label(getString(R.string.turn_card_overlay_horizontal), 12, MUTED_DIM).apply {
                            setPadding(0, dp(6), 0, 0)
                        })
                        group.addView(LinearLayout(this@DiPlayActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(6), 0, 0)
                            addView(label(getString(R.string.cluster_card_horizontal), 15, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
                            addView(numberInput(pendingXY["turn"]?.first ?: 0, 1920) { v ->
                                val y = pendingXY["turn"]?.second ?: 0
                                pendingXY["turn"] = Pair(v, y)
                                // Persist on every keystroke: the dashboard is on another screen that is
                                // visible at the same time as this one, so the driver watches the card move
                                // while typing instead of applying and going over to look.
                                AirPlayPersistence.saveClusterTurnCardX(this@DiPlayActivity, v)
                            }, LinearLayout.LayoutParams(dp(150), dp(48)).apply { leftMargin = dp(8) })
                        })
                        group.addView(LinearLayout(this@DiPlayActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(6), 0, 0)
                            addView(label(getString(R.string.cluster_card_vertical), 15, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
                            addView(numberInput(pendingXY["turn"]?.second ?: 0, 720) { v ->
                                val x = pendingXY["turn"]?.first ?: 0
                                pendingXY["turn"] = Pair(x, v)
                                AirPlayPersistence.saveClusterTurnCardY(this@DiPlayActivity, v)
                            }, LinearLayout.LayoutParams(dp(150), dp(48)).apply { leftMargin = dp(8) })
                        })
                        group.addView(button(getString(R.string.reset_turn_card_overlay), false) {
                            // Reset is an immediate action: persist the defaults right away, then
                            // re-render so the inputs show the restored values. (A preview-only reset
                            // would be wiped by render(), which re-initialises pendingXY from disk.)
                            AirPlayPersistence.saveClusterTurnCardX(this, AirPlayPersistence.DEFAULT_CLUSTER_TURN_CARD_X_PX)
                            AirPlayPersistence.saveClusterTurnCardY(this, AirPlayPersistence.DEFAULT_CLUSTER_TURN_CARD_Y_PX)
                            render()
                        }, matchButton(12, 56))
                        group.addView(label(getString(R.string.turn_card_overlay_note), 12, MUTED).apply {
                            setPadding(0, dp(8), 0, dp(6))
                        })
                    }
                } else {
                    cardGroup(card, getString(R.string.cluster_card_turn_title)) { group ->
                        // The turn card's position and size live inside the customCard branch, so with
                        // any other "仪表盘显示" they used to vanish without a word - which reads as a
                        // bug rather than a mode the driver is in. Say what to switch to instead.
                        group.addView(label(getString(R.string.cluster_turn_card_controls_hidden), 12, MUTED).apply {
                            setPadding(dp(2), dp(2), dp(2), dp(4))
                        })
                    }
                }
                // Per-card absolute position (x, y), origin = top-left of the full dashboard
                // panel (1920x720 canvas == display). Direct numeric input (not a slider -
                // sliders are easy to mis-touch on the head unit). Typing only previews into
                // pendingXY; Apply & save writes everything and refreshes the live cards.
                fun addCardOffsetInputs(
                    title: String,
                    cardId: String,
                    scaleTitle: Int?,
                    onChanged: (String, Int, Int) -> Unit,
                ) {
                    cardGroup(card, title) { group ->
                        toggle(group, getString(R.string.cluster_card_show, title),
                            getString(R.string.cluster_card_show_desc),
                            AirPlayPersistence.loadClusterCardVisible(this, cardId)) { visible ->
                            AirPlayPersistence.saveClusterCardVisible(this, cardId, visible)
                        }
                        if (scaleTitle != null) cardScaleRow(group, getString(scaleTitle), cardId)
                        // Same background-strength control the song card has (2026-10-25).
                        if (cardId == "eta") {
                            percentRow(
                                group,
                                getString(R.string.cluster_card_background_alpha),
                                AirPlayPersistence.loadClusterCardAlpha(this, cardId),
                            ) { percent ->
                                AirPlayPersistence.saveClusterCardAlpha(this, cardId, percent)
                            }
                        }
                        group.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(8), 0, 0)
                            addView(label(getString(R.string.cluster_card_horizontal), 15, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
                            addView(numberInput(pendingXY[cardId]?.first ?: 0, 1920) { v ->
                                val y = pendingXY[cardId]?.second ?: 0
                                pendingXY[cardId] = Pair(v, y)
                                onChanged(cardId, v, y)
                            }, LinearLayout.LayoutParams(dp(150), dp(48)).apply { leftMargin = dp(8) })
                        })
                        group.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(6), 0, dp(6))
                            addView(label(getString(R.string.cluster_card_vertical), 15, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
                            addView(numberInput(pendingXY[cardId]?.second ?: 0, 720) { v ->
                                val x = pendingXY[cardId]?.first ?: 0
                                pendingXY[cardId] = Pair(x, v)
                                onChanged(cardId, x, v)
                            }, LinearLayout.LayoutParams(dp(150), dp(48)).apply { leftMargin = dp(8) })
                        })
                    }
                }
                /**
                 * X/Y inputs for a card WINDOW we only place, never draw (the Apple
                 * full-navigation card).
                 *
                 * No show switch and no size slider on purpose: the window exists only while the
                 * phone streams that card (nothing to hide), and its size is the stream's own
                 * picture, so it cannot be scaled. Typing only updates pendingXY; the position is
                 * written by the Apply & save button below - which also works with no window on
                 * screen, the value then takes effect the next time the window is built.
                 */
                fun addWindowOffsetInputs(title: String, cardId: String, description: String) {
                    cardGroup(card, title) { group ->
                        group.addView(label(description, 13, MUTED).apply {
                            setPadding(0, 0, 0, dp(6))
                        })
                        group.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(8), 0, 0)
                            addView(label(getString(R.string.cluster_card_horizontal), 15, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
                            addView(numberInput(pendingXY[cardId]?.first ?: 0, 1920) { v ->
                                pendingXY[cardId] = Pair(v, pendingXY[cardId]?.second ?: 0)
                            }, LinearLayout.LayoutParams(dp(150), dp(48)).apply { leftMargin = dp(8) })
                        })
                        group.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(6), 0, dp(6))
                            addView(label(getString(R.string.cluster_card_vertical), 15, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
                            addView(numberInput(pendingXY[cardId]?.second ?: 0, 720) { v ->
                                pendingXY[cardId] = Pair(pendingXY[cardId]?.first ?: 0, v)
                            }, LinearLayout.LayoutParams(dp(150), dp(48)).apply { leftMargin = dp(8) })
                        })
                    }
                }
                addCardOffsetInputs(getString(R.string.cluster_card_eta_title), "eta", R.string.card_scale_eta) { _, _, _ -> }
                addCardOffsetInputs(getString(R.string.cluster_card_song_title), "song", R.string.card_scale_song) { _, _, _ -> }
                // The song card is sized by its text, so a long title stretches it across the panel.
                // This caps the text instead, in characters, with "..." marking the cut.
                cardGroup(card, getString(R.string.cluster_card_song_max_chars)) { group ->
                    group.addView(label(getString(R.string.cluster_card_song_max_chars_desc), 13, MUTED).apply {
                        setPadding(0, 0, 0, dp(6))
                    })
                    group.addView(LinearLayout(this@DiPlayActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            numberInput(
                                AirPlayPersistence.loadClusterSongMaxChars(this@DiPlayActivity),
                                AirPlayPersistence.MAX_SONG_MAX_CHARS,
                            ) { value ->
                                // Saves and notifies the dashboard immediately: the card re-reads the
                                // limit on its next draw.
                                AirPlayPersistence.saveClusterSongMaxChars(this@DiPlayActivity, value)
                            },
                            LinearLayout.LayoutParams(dp(150), dp(48)),
                        )
                    })
                }
                // The lyrics card = song name + artist + album cover in one capsule. The cover's
                // side decides where the text goes (it always takes the opposite side).
                cardGroup(card, getString(R.string.cluster_card_song_show_artwork)) { group ->
                    toggle(group, getString(R.string.cluster_card_song_show_artwork),
                        getString(R.string.cluster_card_song_show_artwork_desc),
                        AirPlayPersistence.loadClusterSongShowArtwork(this)) { show ->
                        AirPlayPersistence.saveClusterSongShowArtwork(this, show)
                    }
                    choice(
                        group,
                        getString(R.string.cluster_card_song_artwork_side),
                        listOf(
                            getString(R.string.song_art_top),
                            getString(R.string.song_art_bottom),
                            getString(R.string.song_art_left),
                            getString(R.string.song_art_right),
                        ),
                        AirPlayPersistence.loadClusterSongArtworkSide(this),
                    ) { index ->
                        AirPlayPersistence.saveClusterSongArtworkSide(this, index)
                    }
                    percentRow(
                        group,
                        getString(R.string.cluster_card_song_artwork_scale),
                        AirPlayPersistence.loadClusterSongArtworkScale(this),
                        min = 10,
                        max = 200,
                    ) { percent ->
                        AirPlayPersistence.saveClusterSongArtworkScale(this, percent)
                    }
                    group.addView(label(getString(R.string.cluster_card_song_font_scale_desc), 13, MUTED).apply {
                        setPadding(0, dp(8), 0, 0)
                    })
                    percentRow(
                        group,
                        getString(R.string.cluster_card_song_font_scale),
                        AirPlayPersistence.loadClusterSongFontScale(this),
                        min = 50,
                        max = 200,
                    ) { percent ->
                        AirPlayPersistence.saveClusterSongFontScale(this, percent)
                    }
                }
                // Background strength of the one card that can sit over the map: the driver may want
                // it to nearly vanish. Only the fill fades, the bevel stays.
                cardGroup(card, getString(R.string.cluster_card_song_alpha)) { group ->
                    group.addView(label(getString(R.string.cluster_card_song_alpha_desc), 13, MUTED).apply {
                        setPadding(0, 0, 0, dp(6))
                    })
                    percentRow(group, getString(R.string.cluster_card_song_alpha),
                        AirPlayPersistence.loadClusterSongCardAlpha(this)) { percent ->
                        AirPlayPersistence.saveClusterSongCardAlpha(this, percent)
                    }
                }
                // The card window of Apple's own full-navigation stream (FLY_CARD). Listed after the
                // cards we draw because it is the only entry here that moves something the phone
                // paints, not something of ours - and it shares the same Apply & save button.
                addWindowOffsetInputs(
                    getString(R.string.cluster_card_apple_window_title),
                    "apple",
                    getString(R.string.cluster_card_apple_window_desc),
                )
                // The four edges of the declared safe area, in panel pixels. Unlike the card
                // positions these are read into /info when the phone connects, so a change only
                // lands on the NEXT connection.
                cardGroup(card, getString(R.string.cluster_safe_area_title)) { group ->
                    group.addView(label(getString(R.string.cluster_safe_area_desc), 13, MUTED).apply {
                        setPadding(0, 0, 0, dp(6))
                    })
                    fun safeRow(titleRes: Int, edge: String, max: Int) {
                        group.addView(LinearLayout(this@DiPlayActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(6), 0, 0)
                            addView(
                                label(getString(titleRes), 15, TEXT, true),
                                LinearLayout.LayoutParams(0, -2, 1f),
                            )
                            addView(
                                numberInput(
                                    AirPlayPersistence.loadClusterSafeInset(this@DiPlayActivity, edge),
                                    max,
                                ) { value ->
                                    AirPlayPersistence.saveClusterSafeInset(this@DiPlayActivity, edge, value)
                                },
                                LinearLayout.LayoutParams(dp(150), dp(48)).apply { leftMargin = dp(8) },
                            )
                        })
                    }
                    safeRow(R.string.cluster_safe_area_top, "top", 720)
                    safeRow(R.string.cluster_safe_area_bottom, "bottom", 720)
                    safeRow(R.string.cluster_safe_area_left, "left", 1920)
                    safeRow(R.string.cluster_safe_area_right, "right", 1920)
                }
                card.addView(button(getString(R.string.cluster_card_position_apply), false) {
                    pendingXY.forEach { (id, pair) ->
                        if (id == "turn") {
                            AirPlayPersistence.saveClusterTurnCardX(this, pair.first)
                            AirPlayPersistence.saveClusterTurnCardY(this, pair.second)
                        } else {
                            AirPlayPersistence.saveClusterCardX(this, id, pair.first)
                            AirPlayPersistence.saveClusterCardY(this, id, pair.second)
                        }
                    }
                    toast(getString(R.string.cluster_card_position_applied))
                }, matchButton(0, 56))
                // The upstream "car marker" offsets are gone with the reset button: the map sits
                // in the safe area requested at connect time, so there is no marker to nudge.
                // The custom cards have their own position and size in the blocks above; these
                // step pickers never moved them.
            }
        }
        val groupsBeforeCustomizations = activeSectionGroups
        com.freddyq.diplay.customization.VehicleCustomizations.settings(this).forEach { group ->
            activeSectionGroups = when (group.area) {
                com.freddyq.diplay.customization.CustomizationSettingsArea.CONNECTION -> GROUP_CONNECT
                com.freddyq.diplay.customization.CustomizationSettingsArea.SYSTEM -> GROUP_SYSTEM
                com.freddyq.diplay.customization.CustomizationSettingsArea.DASHBOARD -> GROUP_CLUSTER
            }
            section(content, getString(group.title), R.drawable.ic_dp_navigation) { card ->
                group.toggles.forEach { setting ->
                    toggle(card, getString(setting.title), getString(setting.description), setting.enabled, save = setting.onChanged)
                }
                group.actions.forEach { action ->
                    card.addView(button(getString(action.title), false, action.onClick), matchButton(12, 56))
                    card.addView(label(getString(action.description), 13, MUTED).apply { setPadding(0, dp(8), 0, dp(6)) })
                }
            }
        }
        activeSectionGroups = groupsBeforeCustomizations
        if (BydOutputSettings.navigationAvailable(this)) section(content, getString(R.string.byd_navigation), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.navigation_on_hud_and_instrument_cluster),
                getString(R.string.show_phone_navigation_arrows_distance_and_street_names_on),
                com.shilapi.xcertplay.hud.BydOutputSettings.enabled(this)) { com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(this, it) }
            if (ClusterMapPresentation.findDisplay(this) != null) {
                toggle(card, getString(R.string.center_map_card), getString(R.string.center_map_card_description),
                    AirPlayPersistence.loadCenterMapOverlay(this)) {
                    AirPlayPersistence.saveCenterMapOverlay(this, it)
                    if (it && !CenterMapOverlay.permitted(this)) openOverlayPermission()
                    render()
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    toggle(card, getString(R.string.center_map_follows_dashboard), getString(R.string.center_map_follows_dashboard_description),
                        AirPlayPersistence.loadCenterMapFollowsDashboard(this)) {
                        AirPlayPersistence.saveCenterMapFollowsDashboard(this, it)
                    }
                }
                toggle(card, getString(R.string.launcher_map_sharing), getString(R.string.launcher_map_sharing_description),
                    AirPlayPersistence.loadLauncherMapSharing(this)) {
                    AirPlayPersistence.saveLauncherMapSharing(this, it)
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    val overlay = CenterMapOverlay.permitted(this)
                    card.addView(label(if (overlay) getString(R.string.center_map_overlay_allowed)
                        else getString(R.string.center_map_overlay_missing, packageName), 14, if (overlay) MUTED else WARNING))
                    val usage = HomeScreenMonitor.hasAccess(this)
                    card.addView(label(if (usage) getString(R.string.center_map_usage_allowed)
                        else getString(R.string.center_map_usage_missing, packageName), 14, if (usage) MUTED else WARNING))
                }
                if (DiLink51ClusterLayout.supported()) {
                    val automatic = DiLink51ClusterLayout.automatic(this)
                    toggle(card, getString(R.string.follow_instrument_theme_and_map_card),
                        getString(R.string.show_the_side_map_only_when_its_card_is_open_and_switch_to), automatic) {
                        DiLink51ClusterLayout.saveAutomatic(this, it)
                        render()
                        reconnectForClusterMap()
                    }
                    val allowed = DiLink51ClusterMonitor.hasAccess(this)
                    card.addView(label(if (allowed) getString(R.string.usage_access_enabled)
                        else getString(R.string.usage_access_setup_needed_for_automatic_mode), 14, if (allowed) MUTED else WARNING))
                    card.addView(button(getString(R.string.automatic_map_setup_adb), false) { showClusterAccessSetup() }, matchButton(10, 56))
                    if (!automatic) {
                        val themes = DiLink51ClusterLayout.Theme.entries
                        choice(card, getString(R.string.instrument_theme), themes.map { it.localizedLabel(this) }, themes.indexOf(DiLink51ClusterLayout.theme(this))) {
                            DiLink51ClusterLayout.saveTheme(this, themes[it])
                            reconnectForClusterMap()
                        }
                        card.addView(label(getString(R.string.manual_mode_match_the_cluster_theme_here_the_map_cannot_fo), 14, MUTED))
                    }
                    val contrasts = DiLink51ClusterLayout.Contrast.entries
                    choice(card, getString(R.string.instrument_contrast), contrasts.map { it.localizedLabel(this) }, contrasts.indexOf(DiLink51ClusterLayout.contrast(this))) {
                        DiLink51ClusterLayout.saveContrast(this, contrasts[it])
                        reconnectForClusterMap()
                    }
                }
            }
        }
        activeSectionGroups = GROUP_SYSTEM
        section(content, getString(R.string.permissions_and_connection_help), R.drawable.ic_dp_permissions) { card ->
            card.addView(label(getString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir), 16, MUTED))
            card.addView(button(getString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            card.addView(button(getString(R.string.bluetooth_settings), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(10, 60))
            card.addView(button(getString(R.string.wireless_connection_help), false) { wirelessHelp() }, matchButton(10, 60))
        }
        activeSectionGroups = GROUP_SYSTEM
        section(content, getString(R.string.about), R.drawable.ic_dp_about) { card ->
            card.addView(button(getString(R.string.about_diplay), false) { page = "about"; render() }, matchButton(0, 60))
        }
        activeSectionGroups = GROUP_SYSTEM
        languageSettings(content)
        // Late/async builders (ADB probes) must not inherit a group: untagged content stays visible.
        activeSectionGroups = GROUP_ALL
        applyGroupFilter()
        // The caller owns this row now (it goes outside the outer ScrollView), so the rail and the
        // page header stay put while only the right column scrolls.
        return body
    }

    private fun about(content: LinearLayout) {
        content.addView(label(getString(R.string.diplay), 40, TEXT, true))
        content.addView(label(getString(R.string.carplay_at_home_in_your_car), 20, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "${getString(aboutVersionPrefix())}${version()}") { card ->
            card.addView(label(getString(R.string.an_independent_carplay_receiver_for_android_head_units_wir), 17, TEXT))
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay), 16, MUTED))
        }
        section(content, getString(R.string.about_credits_title)) { card ->
            card.addView(label(getString(R.string.about_credits_body), 15, MUTED))
        }
    }

    // An opted-in connection prepares the hotspot in the controller instead of stopping at this reminder.
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotSettings.reportedOff(
                this,
                com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this),
            ) &&
            !(CarHotspotSettings.enabled(this) && CarHotspotTethering.permitted(this))

    private fun bydAdbSettings(parent: LinearLayout) {
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) return
        if (!CarHotspotSetup.isBydHeadUnit(this)) {
            Log.i("DiPlay-Hotspot", "settings hidden: BYD head unit not detected")
            return
        }
        // Group tag matters: this column is filled asynchronously, after the page has already
        // applied its filter, and an untagged view would show up under every rail entry.
        val controls = column().apply { visibility = View.GONE; tag = SectionTag(GROUP_SYSTEM, 5) }
        bydAdbControls = controls
        val sink = settingsSectionSink
        if (sink != null) sink.add(controls) else parent.addView(controls)
        Thread({
            val access = runCatching { CarHotspotSetup.check(applicationContext) }
                .onFailure { Log.w("DiPlay-Hotspot", "settings ADB check failed", it) }
                .getOrDefault(LocalAdb.Access.UNREACHABLE)
            Log.i("DiPlay-Hotspot", "settings eligibility: byd=true adb=$access visible=${CarHotspotSettings.visible(true, access)}")
            runOnUiThread {
                if (bydAdbControls !== controls || isFinishing || isDestroyed) return@runOnUiThread
                if (CarHotspotSettings.visible(true, access)) {
                    controls.visibility = View.VISIBLE
                    renderBydAdbControls(controls, access)
                }
            }
        }, "diplay-hotspot-adb-check").start()
    }

    private fun renderBydAdbControls(controls: LinearLayout, access: LocalAdb.Access) {
        controls.removeAllViews()
        adbSwitches.keys.retainAll(setOf(R.string.open_after_the_car_starts))
        adbStatus = null
        controls.visibility = if (CarHotspotSettings.visible(true, access)) View.VISIBLE else View.GONE
        if (controls.visibility == View.GONE) return
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) {
            controls.visibility = View.GONE
            return
        }
        section(controls, getString(R.string.byd_adb_features), R.drawable.ic_dp_permissions) { card ->
            if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL) {
                adbToggle(card, R.string.auto_car_hotspot_title, R.string.auto_car_hotspot_description,
                    read = { CarHotspotSettings.enabled(this) },
                    permissions = {
                        buildList {
                            add(CarHotspotSetup.Permission.HOTSPOT)
                            if (AirPlayPersistence.loadAutoStartOnBoot(this@DiPlayActivity)) {
                                add(CarHotspotSetup.Permission.BOOT_LAUNCH)
                            }
                        }
                    }) {
                    CarHotspotSettings.setEnabled(this, it)
                    if (!it) startupHotspotCancelled = true
                }
            }
            adbStatus = label(getString(if (access == LocalAdb.Access.READY)
                R.string.adb_access_ready else R.string.adb_not_approved), 14, MUTED).also(card::addView)
        }
    }

    private fun adbToggle(parent: LinearLayout, title: Int, description: Int, read: () -> Boolean,
        needsAdb: () -> Boolean = { true },
        permissions: () -> List<CarHotspotSetup.Permission> = { emptyList() }, save: (Boolean) -> Unit) {
        val control = toggle(parent, getString(title), getString(description), read(),
            enabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress()) { enabled ->
            if (updatingAdbSwitches || adbSwitchChangePending) return@toggle
            if (enabled && needsAdb()) requestAdbSwitchChange(permissions()) { save(true) }
            else save(enabled)
        }
        adbSwitches[title] = control to read
    }

    private fun requestAdbSwitchChange(permissions: List<CarHotspotSetup.Permission>, save: () -> Unit) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) {
            updateAdbSwitches()
            return
        }
        cancelAutomaticVehicleValidationForUserOperation(
            resumeAfter = automaticVehicleValidationInProgress || automaticVehicleValidationPending,
        )
        val app = applicationContext
        adbSwitchChangePending = true
        updateAdbSwitches()
        adbStatus?.setText(R.string.adb_checking_may_ask)
        if (page == "settings" && bydVehicleAdvancedExpanded) render()
        Thread({
            var access = LocalAdb.Access.UNREACHABLE
            val ready = runCatching {
                access = CarHotspotSetup.grant(app, permissions)
                access == LocalAdb.Access.READY && permissions.all { it.granted(app) }
            }.getOrDefault(false)
            runOnUiThread {
                adbSwitchChangePending = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = if (ready) {
                    if (permissions.isEmpty()) R.string.adb_access_ready else R.string.hotspot_permission_granted
                } else when (access) {
                    LocalAdb.Access.NOT_APPROVED -> R.string.adb_not_approved
                    LocalAdb.Access.UNREACHABLE -> R.string.adb_off
                    LocalAdb.Access.UNSUPPORTED -> R.string.adb_pairing_only
                    else -> R.string.hotspot_permission_failed
                }
                adbStatus?.setText(if (ready) R.string.adb_access_ready else message)
                if (ready) save()
                updateAdbSwitches()
                toast(getString(message))
                runPendingAutomaticVehicleValidation()
                // Re-enable the vehicle controls disabled while this authorization was outstanding.
                if (page == "settings" && bydVehicleAdvancedExpanded) render()
            }
        }, "diplay-adb-switch").start()
    }

    private fun updateAdbSwitches() {
        updatingAdbSwitches = true
        for ((control, read) in adbSwitches.values) {
            control.isChecked = read()
            control.isEnabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress()
        }
        updatingAdbSwitches = false
    }

    private fun vehicleAdbWorkInProgress(): Boolean =
        adbCheckInProgress || vehicleProbeAuthorizationInProgress || vehicleProbeInProgress

    private fun startCarHotspotOnLaunch() {
        if (!startupHotspotEligible()) return
        val app = applicationContext
        Thread({
            val result = CarHotspotTethering.enable(app,
                isCancelled = { startupHotspotCancelled || !startupHotspotEligible() },
                log = { Log.i("DiPlay-Hotspot", it) },
            )
            runOnUiThread {
                if (isFinishing || isDestroyed || result == CarHotspotTethering.Result.CANCELLED) return@runOnUiThread
                hotspotStartupResult = result
                if (page == "home") render()
            }
        }, "diplay-hotspot-startup").start()
    }

    private fun startupHotspotEligible(): Boolean =
        CarHotspotSetup.shouldStartOnLaunch(applicationContext, CarPlayBackgroundSession.hasSession())

    private fun hotspotResultText(result: CarHotspotTethering.Result): String = getString(when (result) {
        CarHotspotTethering.Result.READY -> R.string.hotspot_control_on
        CarHotspotTethering.Result.PERMISSION_REQUIRED -> R.string.hotspot_control_missing
        CarHotspotTethering.Result.UNSUPPORTED -> R.string.hotspot_control_unsupported
        CarHotspotTethering.Result.TIMED_OUT -> R.string.hotspot_control_timeout
        else -> R.string.hotspot_control_failed
    })

    private fun carHotspotOffDialog() {
        AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_is_off))
            .setMessage(getString(R.string.msg_car_hotspot_connect, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(R.string.connect)) { _, _ ->
                // The driver sees the hotspot on: trust that over Android's report, or this dialog
                // would come back on every tap (#604).
                CarHotspotSettings.setOffReportIgnored(this, true)
                connect(true)
            }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
        if (packageManager.resolveActivity(wifi, 0)?.activityInfo?.packageName == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        openSystem(wifi)
    }

    private fun connectionSetup(content: LinearLayout) {
        content.addView(label(getString(R.string.connection_setup), 34, TEXT, true))
        content.addView(label(getString(R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, getString(R.string.s_1_choose_your_connection)) { card -> wirelessLinkControls(card) }
        section(content, getString(R.string.s_2_pair_your_iphone)) { card ->
            card.addView(label(getString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car), 16, MUTED))
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
            card.addView(button(getString(R.string.review_app_permissions), false) {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, matchButton(12, 60))
        }
        section(content, getString(R.string.s_3_connect)) { card ->
            card.addView(label(getString(R.string.return_from_car_settings_to_diplay_then_connect_accept_the), 16, MUTED))
            card.addView(button(getString(R.string.connect_phone), true) { connect(true) }, matchButton(12, 60))
        }
        section(content, getString(R.string.prefer_a_cable)) { card ->
            card.addView(label(getString(R.string.use_a_usb_data_cable_and_the_car_s_usb_data_port_unlock_yo), 16, MUTED))
            card.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(12, 60))
        }
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val mode = if (pendingCarHotspotSetup) WirelessHotspotMode.MANUAL else AirPlayPersistence.loadWirelessHotspotMode(this)
        // Three modes only, matching upstream: car hotspot / Wi-Fi Direct / existing Wi-Fi.
        val modes = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.EXISTING_WIFI)
        val titles = listOf(getString(R.string.built_in_car_hotspot), getString(R.string.wifi_direct), getString(R.string.existing_wifi_title))
        val descriptions = listOf(
            getString(R.string.hotspot_mode_manual_desc),
            getString(R.string.hotspot_mode_p2p_desc),
            getString(R.string.existing_wifi_description)
        )
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row().apply { gravity = Gravity.TOP } else column()
        parent.addView(choices)
        modes.forEachIndexed { index, candidate ->
            val option = column()
            choices.addView(option, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(16)
            } else LinearLayout.LayoutParams(-1, -2))
            option.addView(button("${if (mode == candidate) "✓  " else ""}${titles[index]}", mode == candidate) {
                if (candidate == WirelessHotspotMode.MANUAL) {
                    pendingCarHotspotSetup = true
                    render()
                } else if (candidate == WirelessHotspotMode.EXISTING_WIFI) {
                    askHotspotCredentials(existingWifi = true) { ssid, password ->
                        AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                        pendingCarHotspotSetup = false
                        applyWirelessLink(candidate)
                    }
                } else {
                    pendingCarHotspotSetup = false
                    applyWirelessLink(candidate)
                }
            }, matchButton(12, 60))
            option.addView(label(descriptions[index], 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) })
        }
        if (mode == WirelessHotspotMode.MANUAL) {
            parent.addView(label(getString(R.string.hotspot_setup), 22, TEXT, true))
            parent.addView(label(getString(R.string.s_1_open_car_hotspot_settings_turn_the_hotspot_on_and_sele), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) })
            parent.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(0, 60))
            parent.addView(button(if (pendingCarHotspotSetup) getString(R.string.save_hotspot_details_and_use_this_mode) else "${getString(R.string.edit_saved_hotspot_prefix)}${storedSsid()}", false) {
                askHotspotCredentials { ssid, password ->
                    saveHotspotCredentials(ssid, password)
                    pendingCarHotspotSetup = false
                    applyWirelessLink(WirelessHotspotMode.MANUAL)
                }
            }, matchButton(12, 60))
            parent.addView(label(if (pendingCarHotspotSetup) getString(R.string.finish_setup_save_your_hotspot_details_to_use_this_mode) else if (carHotspotOff()) getString(R.string.hotspot_details_off) else getString(R.string.hotspot_details_saved), 15, if (carHotspotOff()) WARNING else MUTED).apply { setPadding(0, dp(12), 0, 0) })
        } else if (mode == WirelessHotspotMode.EXISTING_WIFI) {
            parent.addView(label(getString(R.string.existing_wifi_instructions), 16, MUTED))
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
            parent.addView(button(getString(R.string.existing_wifi_details), false) {
                askHotspotCredentials(existingWifi = true) { ssid, password ->
                    AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                    toast(getString(R.string.saved_for_your_next_connection))
                }
            }, matchButton(12, 60))
        } else {
            parent.addView(label(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            wifiDirectChannelControl(parent)
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
        }
    }

    private fun wifiDirectChannelLabel(channel: Int): String = if (channel == WifiP2pChannels.AUTO) {
        getString(R.string.auto)
    } else {
        getString(R.string.wifi_direct_channel_choice, channel,
            getString(if (channel < 36) R.string.s_2_4_ghz else R.string.s_5_ghz))
    }

    private fun wifiDirectChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.wifi_direct_channel_summary, wifiDirectChannelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadWifiP2pPreferredChannel(this)), false) {}
        control.setOnClickListener {
            val choices = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.channels
            val current = AirPlayPersistence.loadWifiP2pPreferredChannel(this)
            var selection = current
            AlertDialog.Builder(this).setTitle(R.string.wifi_direct_channel_title)
                .setSingleChoiceItems(choices.map(::wifiDirectChannelLabel).toTypedArray(),
                    choices.indexOf(current)) { _, which -> selection = choices[which] }
                .setPositiveButton(R.string.save) { _, _ ->
                    if (selection != current) {
                        AirPlayPersistence.saveWifiP2pPreferredChannel(this, selection)
                        control.text = summary(selection)
                        toast(getString(R.string.saved_for_your_next_connection))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        parent.addView(control, matchButton(12, 60))
        parent.addView(label(getString(R.string.wifi_direct_channel_description), 15, MUTED).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
    }

    private fun mediaChannelControl(parent: LinearLayout) {
        val title = getString(R.string.contrib_audio_home_media_channel_label)
        lateinit var value: TextView
        value = valueRow(parent, title, channelLabel(AirPlayPersistence.loadMediaAudioChannel(this))) {
            val current = AirPlayPersistence.loadMediaAudioChannel(this)
            showChannelDialog(
                title = title,
                current = current,
                navigation = false,
                onApply = { picked -> applyMediaChannel(picked, current, value, ::channelLabel) },
            )
        }
    }

    private fun navigationChannelControl(parent: LinearLayout) {
        val title = getString(R.string.contrib_audio_home_nav_channel_label)
        lateinit var value: TextView
        value = valueRow(parent, title, channelLabel(AirPlayPersistence.loadNavigationAudioChannel(this))) {
            val current = AirPlayPersistence.loadNavigationAudioChannel(this)
            showChannelDialog(
                title = title,
                current = current,
                navigation = true,
                onApply = { picked -> applyNavigationChannel(picked, current, value, ::channelLabel) },
            )
        }
        parent.addView(label(getString(R.string.contrib_audio_home_nav_channel_note), 12, MUTED_DIM).apply {
            setPadding(0, 0, 0, dp(10))
        })
    }

    private fun showChannelDialog(title: String, current: Int, navigation: Boolean, onApply: (Int) -> Unit) {
        val preview = AudioChannelPreview { channel ->
            toast(getString(R.string.contrib_audio_home_channel_preview_unavailable, channel))
        }
        val channels = AirPlayPersistence.AUDIO_CHANNELS
        val labels = channels.map(Int::toString).toTypedArray()
        var selection = current.coerceIn(channels.first, channels.last)
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels, selection) { _, which ->
                selection = which
                preview.play(which, navigation)
            }
            .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.apply_and_reconnect) else getString(R.string.save)) { _, _ ->
                onApply(selection)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setOnDismissListener { preview.close() }
            .show()
    }

    private fun applyMediaChannel(value: Int, previous: Int, control: TextView, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveMediaAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyNavigationChannel(value: Int, previous: Int, control: TextView, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveNavigationAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun channelLabel(value: Int): String = value.toString()

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(existingWifi: Boolean = false, done: (String, String) -> Unit) {
        val detected = if (existingWifi) null else CarHotspotSetup.readSavedHotspot(this)
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(getString(if (existingWifi) R.string.existing_wifi_instructions else if (detected != null) R.string.system_hotspot_details_loaded else R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        val ssid = EditText(this).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_ssid else R.string.hotspot_name)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiSsid(this@DiPlayActivity) else detected?.ssid ?: storedSsid())
            setSingleLine()
        }
        val password = EditText(this).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_password else R.string.hotspot_password)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiPassphrase(this@DiPlayActivity) else detected?.passphrase ?: storedPassword())
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        fields.addView(ssid); fields.addView(password)
        fields.addView(CheckBox(this).apply {
            text = getString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        val error = label("", 14, WARNING)
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(getString(if (existingWifi) R.string.existing_wifi_details else R.string.car_hotspot_details))
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = ssid.text.toString()
                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else { hideKeyboard(); dialog.dismiss(); done(name, secret) }
            }
        }
        dialog.show()
    }

    // "Left 20 %", "Centre · default", "Down 10 %": a signed step reads as a direction and a distance.
    private fun markerStepLabel(step: Int, negative: String, positive: String): String = when {
        step == 0 -> getString(R.string.marker_centre_default)
        step < 0 -> "$negative ${-step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
        else -> "$positive ${step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
    }

    /**
     * A numeric EditText for absolute card coordinates. Typing updates only the preview
     * (pendingXY); the caller persists on Apply. [initial] is the current value, [max] the
     * inclusive upper bound (X 1920 / Y 720).
     */
    /**
     * One labelled slider for a card's size, 60-160 % of the size it was designed at.
     *
     * Applied while dragging: saving calls the overlay listener, which refreshes the dashboard
     * cards in place without a reconnect, so the driver sizes a card against the real panel instead
     * of guessing a number and applying it blind.
     */
    /**
     * A labelled percentage slider over [min]..[max]. Used by the song card's background strength
     * (0-100) and artwork size (10-200), where the exact number matters less than feeling it.
     */
    private fun percentRow(
        parent: LinearLayout,
        title: String,
        current: Int,
        min: Int = 0,
        max: Int = 100,
        onChange: (Int) -> Unit,
    ) {
        val value = label("$current%", 15, ACCENT, true)
        if (parent.childCount > 0) parent.addView(divider())
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        head.addView(label(title, 15, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(value)
        parent.addView(head, LinearLayout.LayoutParams(-1, -2))
        val slots = (max - min).coerceAtLeast(1)
        parent.addView(SeekBar(this).apply {
            this.max = slots
            progress = (current - min).coerceIn(0, slots)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = "${min + progress.coerceIn(0, slots)}%"
                }

                override fun onStartTrackingTouch(seek: SeekBar?) = Unit

                override fun onStopTrackingTouch(seek: SeekBar?) {
                    onChange(min + (seek?.progress ?: 0).coerceIn(0, slots))
                }
            })
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun cardScaleRow(parent: LinearLayout, title: String, card: String) {
        val current = AirPlayPersistence.loadClusterCardScalePercent(this, card)
        val value = label("$current%", 15, ACCENT, true)
        if (parent.childCount > 0) parent.addView(divider())
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        head.addView(label(title, 15, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(value)
        parent.addView(head, LinearLayout.LayoutParams(-1, -2))
        parent.addView(SeekBar(this).apply {
            val step = 5
            val min = AirPlayPersistence.CLUSTER_CARD_SCALE_MIN
            max = (AirPlayPersistence.CLUSTER_CARD_SCALE_MAX - min) / step
            progress = ((current - min) / step).coerceIn(0, max)
            // No setMinHeight: this head-unit ROM strips SeekBar#setMinHeight and throws
            // NoSuchMethodError, which would kill the whole settings page.
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                    val percent = min + progress * step
                    value.text = "$percent%"
                    if (fromUser) {
                        AirPlayPersistence.saveClusterCardScalePercent(this@DiPlayActivity, card, percent)
                    }
                }
                override fun onStartTrackingTouch(seek: SeekBar?) = Unit
                override fun onStopTrackingTouch(seek: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(44)))
    }

    /**
     * How far the music drops while a navigation prompt plays. Media tracks only, so a call is
     * never attenuated; the value is read when the next CarPlay session opens.
     */
    private fun navDuckPercentControl(parent: LinearLayout) {
        val current = AirPlayPersistence.loadNavDuckPercent(this)
        val value = label(getString(R.string.nav_duck_percent_value, current), 15, ACCENT, true)
        if (parent.childCount > 0) parent.addView(divider())
        val head = row().apply { setPadding(0, dp(10), 0, dp(2)) }
        head.addView(label(getString(R.string.nav_duck_percent_title), 15, TEXT),
            LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(value)
        parent.addView(head, LinearLayout.LayoutParams(-1, -2))
        parent.addView(SeekBar(this).apply {
            val step = 5
            val min = AirPlayPersistence.NAV_DUCK_MIN
            max = (AirPlayPersistence.NAV_DUCK_MAX - min) / step
            progress = ((current - min) / step).coerceIn(0, max)
            // No setMinHeight: this head-unit ROM strips SeekBar#setMinHeight and throws
            // NoSuchMethodError, which would kill the whole settings page.
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                    val percent = min + progress * step
                    value.text = getString(R.string.nav_duck_percent_value, percent)
                    if (fromUser) {
                        AirPlayPersistence.saveNavDuckPercent(this@DiPlayActivity, percent)
                    }
                }
                override fun onStartTrackingTouch(seek: SeekBar?) = Unit
                override fun onStopTrackingTouch(seek: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(44)))
        parent.addView(label(getString(R.string.nav_duck_percent_hint), 14, MUTED).apply {
            setPadding(0, dp(4), 0, dp(12))
        })
    }

    private fun numberInput(initial: Int, max: Int, onChange: (Int) -> Unit): EditText =
        EditText(this).apply {
            setText(initial.toString())
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(TEXT)
            backgroundTintList = ColorStateList.valueOf(ACCENT)
            minHeight = dp(48)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(12), 0, dp(12), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString()?.trim()?.toIntOrNull() ?: return
                    onChange(v.coerceIn(0, max))
                }
            })
        }

    /**
     * One-tap root grant of the ACTIVATE_VPN AppOps operation.
     *
     * The wired CarPlay path ends by establishing a VpnService tunnel, and a ROM that deletes
     * com.android.vpndialogs leaves the app unable to pass the consent step, so establish()
     * returns null and the whole wired link fails at the last gate. A rooted head unit can fix
     * that without any dialog with `appops set <package> ACTIVATE_VPN allow` - the same
     * workaround documented for Windows Subsystem for Android, which has the same missing
     * component. Runs off the main thread; su that waits for a prompt times out and says so.
     */
    private fun runVpnRootGrant() {
        val context = applicationContext
        clusterUiLog("VPN-ROOT 开始尝试 root 授权 ACTIVATE_VPN")
        Thread({
            val result = runCatching { CarPlayVpnService.grantActivateVpnWithRoot(context) }
                .getOrElse { "异常 ${it.javaClass.simpleName}: ${it.message}" }
            clusterUiLog("VPN-ROOT 结果：$result")
            clusterUiLog("USB-DIAG vpn: ${CarPlayVpnService.diagnostic(context)}")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                android.widget.Toast.makeText(this, result, android.widget.Toast.LENGTH_LONG).show()
            }
        }, "vpn-root-grant").start()
    }

    /** Appends a diagnostic line to filesDir/logs/diplay.log (same file the car exports). */
    private fun clusterUiLog(message: String) {
        android.util.Log.e("DiPlay", message)
        try {
            val dir = java.io.File(filesDir, "logs")
            dir.mkdirs()
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            java.io.File(dir, "diplay.log").appendText("$stamp  $message\n")
        } catch (_: Exception) {
        }
    }

    /**
     * One-tap USB bring-up report (see UsbDiagnostics): records every gate that can silently block
     * wired CarPlay - system permission UI, the app's attach filter, ADB/root fallbacks and the raw
     * iPhone USB descriptors - into the exported diplay.log. Runs off the main thread; the log file
     * is appended line by line so a single long report stays readable.
     */
    private fun runUsbDiagnostics() {
        val context = applicationContext
        clusterUiLog("USB-DIAG 开始采集（插着 iPhone 采集才有意义）")
        Thread({
            val report = runCatching { UsbDiagnostics.report(context) }
                .getOrElse { "USB-DIAG collection failed: ${it.javaClass.simpleName} ${it.message}" }
            report.lineSequence().forEach { line ->
                if (line.isNotBlank()) clusterUiLog(line)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.usb_diagnostics_done),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }, "usb-diagnostics").start()
    }

    /** Vehicle icon name (OEM label) input row. iOS renders the CarPlay car icon from this
     *  text, so it is shared with the "OEM 标签" row in the CarPlay settings panel. */
    private fun vehicleLabelInputRow(title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(label(title, 16, TEXT, true))
            addView(EditText(this@DiPlayActivity).apply {
                setText(AirPlayPersistence.loadOemLabel(this@DiPlayActivity))
                textSize = 18f
                setTextColor(Color.WHITE)
                setHintTextColor(TEXT)
                backgroundTintList = ColorStateList.valueOf(ACCENT)
                minHeight = dp(48)
                isSingleLine = true
                setPadding(dp(12), 0, dp(12), 0)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        AirPlayPersistence.saveOemLabel(this@DiPlayActivity, s?.toString()?.trim() ?: "")
                    }
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(6) })
        }

    private fun overlayOffsetLabel(percent: Int, negative: String, positive: String, centre: Int): String {
        val delta = percent - centre
        return when {
            delta == 0 -> getString(R.string.marker_centre_default)
            delta < 0 -> "$negative ${-delta} %"
            else -> "$positive $delta %"
        }
    }

    /** Pixel-based offset label for the per-card position sliders (range -150..150 px). */
    private fun cardOffsetLabel(px: Int, negative: String, positive: String): String =
        when {
            px == 0 -> getString(R.string.marker_centre_default)
            px < 0 -> "$negative ${-px} px"
            else -> "$positive $px px"
        }

    private fun showClusterAccessSetup() {
        val command = "adb shell appops set $packageName GET_USAGE_STATS allow"
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.one_time_setup_on_this_car), 20, TEXT, true))
        body.addView(label(getString(R.string.usage_access_lets_diplay_follow_the_instrument_theme_and_m), 15, MUTED))
        body.addView(label(getString(R.string.s_1_connect_a_computer_with_adb_installed_to_the_car_using), 16, TEXT))
        body.addView(label(command, 16, TEXT).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(16))
        })
        body.addView(button(getString(R.string.copy_command), false) {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText(getString(R.string.clipboard_usage_access), command))
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(0, 56))
        body.addView(label(getString(R.string.cluster_adb_multi_device, packageName), 14, MUTED))
        body.addView(label(getString(R.string.s_3_tap_check_and_enable_below_this_enables_the_cluster_ma), 16, TEXT))
        val status = label(if (DiLink51ClusterMonitor.hasAccess(this)) getString(R.string.permission_enabled_ready) else getString(R.string.permission_not_enabled), 16, TEXT)
        body.addView(status)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.automatic_cluster_map_setup))
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton(getString(R.string.close), null)
            .setPositiveButton(getString(R.string.check_and_enable), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (DiLink51ClusterMonitor.hasAccess(this)) {
                    DiLink51ClusterLayout.saveAutomatic(this, true)
                    dialog.dismiss()
                    render()
                    toast(getString(R.string.automatic_map_enabled_open_the_cluster_map_card_or_select))
                    reconnectForClusterMap()
                } else {
                    status.text = getString(R.string.still_waiting_for_usage_access_check_that_the_command_ran)
                }
            }
        }
        dialog.show()
    }

    private fun advancedVehicleData(card: LinearLayout) {
        val legacyMode = BydOutputSettings.legacyVehicleProbe(this)
        card.addView(label(getString(R.string.advanced_vehicle_data_description), 14, MUTED)
            .apply { setPadding(0, dp(12), 0, 0) })
        choice(
            card,
            getString(R.string.vehicle_data_mode),
            listOf(
                getString(R.string.vehicle_data_mode_default),
                getString(R.string.vehicle_data_mode_legacy),
            ),
            if (legacyMode) 1 else 0,
            reconnects = false,
            announcesReconnect = vehicleDataSwitchesOn(),
            // A switch during ADB work would be dropped by the running step.
            enabled = !adbSwitchChangePending && !vehicleAdbWorkInProgress(),
        ) { selectVehicleDataMode(it == 1) }
        if (!legacyMode) {
            defaultVehicleData(card)
            return
        }
        val capabilities = displayedVehicleCapabilities()
        val adbText = when {
            vehicleProbeAuthorizationInProgress -> getString(R.string.adb_checking_may_ask)
            adbCheckInProgress -> getString(if (adbCheckMayAsk)
                R.string.adb_checking_may_ask else R.string.adb_checking)
            vehicleProbeInProgress || automaticVehicleValidationInProgress ->
                getString(R.string.probing_vehicle_data)
            adbCheckFailed -> getString(R.string.adb_check_failed)
            adbAccessState == null && capabilities != null -> getString(R.string.vehicle_probe_saved_automatic)
            adbAccessState == null -> getString(R.string.adb_not_checked)
            else -> adbLinkStatusText(requireNotNull(adbAccessState))
        }
        val healthy = capabilities != null && !adbCheckFailed && vehicleProbeOutcome?.error == null &&
            pendingVehicleLostFields.isEmpty() &&
            (adbAccessState == null || adbAccessState == BydAdbAccess.State.READY)
        card.addView(label(adbText, 14, if (healthy) MUTED else WARNING)
            .apply { setPadding(0, dp(12), 0, 0) })
        if (capabilities != null) {
            showVehicleProbeResults(card, capabilities)
            showVehicleDataSettings(card, capabilities)
        }
        if (vehicleProbeAuthorizationInProgress || adbCheckInProgress ||
            vehicleProbeInProgress || automaticVehicleValidationInProgress) return
        vehicleProbeOutcome?.error?.let {
            card.addView(label(getString(R.string.vehicle_probe_failed, it), 14, WARNING)
                .apply { setPadding(0, dp(10), 0, 0) })
        }
        if (pendingVehicleLostFields.isNotEmpty()) {
            card.addView(label(
                getString(
                    R.string.vehicle_probe_lost_saved_fields,
                    localizedVehicleFields(pendingVehicleLostFields),
                ),
                14,
                WARNING,
            ).apply { setPadding(0, dp(10), 0, 0) })
            card.addView(button(getString(R.string.replace_saved_vehicle_data_anyway), false) {
                replaceSavedVehicleDataAnyway()
            }, matchButton(10, 56))
        }
        val needsUserAction = capabilities == null || adbCheckFailed || vehicleProbeOutcome?.error != null ||
            pendingVehicleLostFields.isNotEmpty() ||
            adbAccessState in setOf(BydAdbAccess.State.NOT_APPROVED, BydAdbAccess.State.ADB_OFF, BydAdbAccess.State.PAIRING_ONLY)
        if (needsUserAction) {
            val title = if (adbAccessState == BydAdbAccess.State.NOT_APPROVED) {
                R.string.request_adb_authorization
            } else if (capabilities == null) {
                R.string.probe_adb_and_vehicle_data
            } else {
                R.string.probe_vehicle_data_again
            }
            card.addView(button(getString(title), false) {
                probeVehicleData(mayAsk = true)
            }.apply { isEnabled = !adbSwitchChangePending && !adbCheckInProgress }, matchButton(10, 56))
        }
    }

    private fun defaultVehicleData(card: LinearLayout) {
        val adbText = when {
            vehicleProbeAuthorizationInProgress -> getString(R.string.adb_checking_may_ask)
            adbCheckInProgress -> getString(if (adbCheckMayAsk)
                R.string.adb_checking_may_ask else R.string.adb_checking)
            vehicleProbeInProgress -> getString(R.string.probing_vehicle_data)
            adbCheckFailed -> getString(R.string.adb_check_failed)
            adbAccessState == null -> getString(R.string.adb_not_checked)
            else -> adbLinkStatusText(requireNotNull(adbAccessState))
        }
        val healthy = !adbCheckFailed && vehicleProbeOutcome?.error == null &&
            (adbAccessState == null || adbAccessState == BydAdbAccess.State.READY)
        card.addView(label(adbText, 14, if (healthy) MUTED else WARNING)
            .apply { setPadding(0, dp(12), 0, 0) })
        val busy = vehicleProbeAuthorizationInProgress || adbCheckInProgress || vehicleProbeInProgress
        if (!busy && adbAccessState == BydAdbAccess.State.READY) {
            defaultVehicleStatus?.let { showDefaultVehicleReadings(card, it) }
        }
        vehicleProbeOutcome?.error?.let {
            card.addView(label(getString(R.string.vehicle_probe_failed, it), 14, WARNING)
                .apply { setPadding(0, dp(10), 0, 0) })
        }
        showVehicleDataSettings(card, capabilities = null)
        if (!busy) {
            card.addView(button(getString(R.string.check_adb_access), false) {
                checkAdbState(mayAsk = true)
            }.apply { isEnabled = !adbSwitchChangePending }, matchButton(10, 56))
        }
    }

    /** The last default-mode reads; a value an enabled switch needs is a warning when missing. */
    private fun showDefaultVehicleReadings(card: LinearLayout, status: BydAdbAccess.Status) {
        fun reading(value: String?, unreadable: Int, needed: Boolean) {
            val text = value ?: getString(unreadable).takeIf { needed } ?: return
            card.addView(label(text, 14, if (value != null) MUTED else WARNING))
        }
        val battery = status.batteryPercent?.let { percent ->
            status.rangeKm?.let { getString(R.string.adb_battery_reading, percent.roundToInt(), it) }
        }
        reading(battery, R.string.adb_battery_unreadable, BydOutputSettings.batteryToIphone(this))
        reading(status.speedKmh?.let { getString(R.string.adb_vehicle_speed_reading, it.roundToInt()) },
            R.string.adb_vehicle_speed_unreadable, BydOutputSettings.wheelSpeedToIphone(this))
        reading(status.gear?.let { getString(R.string.adb_vehicle_gear_reading, it.letter.toString()) },
            R.string.adb_vehicle_gear_unreadable,
            BydOutputSettings.wheelSpeedToIphone(this) || BydOutputSettings.videoWhileParked(this))
    }

    private fun selectVehicleDataMode(legacyMode: Boolean) {
        if (adbSwitchChangePending) return
        // A stale mode dialog must also invalidate a probe when the Boolean stays the same.
        synchronized(vehicleOperationLock) { vehicleProbeGeneration++ }
        vehicleProbeAuthorizationInProgress = false
        vehicleProbeInProgress = false
        if (!legacyMode) {
            cancelAutomaticVehicleValidationForUserOperation(resumeAfter = false)
            synchronized(vehicleOperationLock) {
                BydOutputSettings.setLegacyVehicleProbe(this, false)
            }
            vehicleProbeOutcome = null
            pendingVehicleReplacement = null
            pendingVehicleLostFields = emptySet()
            defaultVehicleStatus = null
            render()
            // The session was built from the legacy probe; apply once the default reads succeed.
            vehicleDataReconnectPending = vehicleDataSwitchesOn()
            if (vehicleDataReconnectPending) checkAdbState(mayAsk = true)
            return
        }
        if (BydVehicleFieldStore.load(this) == null) {
            probeVehicleData(mayAsk = true, activateLegacyModeOnSuccess = true)
            return
        }
        synchronized(vehicleOperationLock) {
            BydOutputSettings.setLegacyVehicleProbe(this, true)
        }
        vehicleProbeOutcome = null
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        scheduleAutomaticVehicleValidation()
        render()
        // With every vehicle-data switch off, the mode changes nothing CarPlay was told.
        if (vehicleDataSwitchesOn()) reconnectForVehicleSetting()
    }

    // Probe results are saved before they are shown, so the saved snapshot is the newest without
    // trusting the clock.
    private fun displayedVehicleCapabilities(): BydVehicleCapabilities? =
        BydVehicleFieldStore.load(this) ?: vehicleProbeOutcome?.capabilities

    private fun localizedVehicleFields(fields: Set<BydVehicleField>): String = fields.map { field ->
        getString(when (field) {
            BydVehicleField.SPEED -> R.string.vehicle_field_speed
            BydVehicleField.GEAR -> R.string.vehicle_field_gear
            BydVehicleField.SOC, BydVehicleField.RANGE, BydVehicleField.REMAINING_KWH ->
                R.string.vehicle_field_battery
            BydVehicleField.BMS_STATE -> R.string.vehicle_field_charging
        })
    }.distinct().joinToString()

    private fun replaceSavedVehicleDataAnyway() {
        val candidate = pendingVehicleReplacement ?: return
        // Never over a snapshot saved since the comparison; a failed write is shown, not thrown.
        val error = runCatching {
            BydVehicleFieldStore.replaceAnyway(applicationContext, pendingVehicleReplacementExpected, candidate)
        }.fold(
            onSuccess = { replaced -> if (replaced) null else getString(R.string.vehicle_probe_snapshot_changed) },
            onFailure = { it.message ?: it.javaClass.simpleName },
        )
        Log.i(BYD_VEHICLE_TAG, "user replaced saved vehicle data error=${error ?: "none"}")
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        vehicleProbeOutcome = error?.let { BydVehicleProbeOutcome(BydAdbAccess.State.READY, error = it) }
        render()
    }

    private fun showVehicleProbeResults(card: LinearLayout, capabilities: BydVehicleCapabilities) {
        val catalogFields = capabilities.fields.values.count { it.address?.source == BydFieldSource.FIRMWARE }
        card.addView(label(getString(if (capabilities.catalogAvailable)
            R.string.vehicle_probe_catalog_ready else R.string.vehicle_probe_catalog_fallback, catalogFields),
            14, if (capabilities.catalogAvailable) MUTED else WARNING).apply { setPadding(0, dp(14), 0, 0) })

        val speed = capabilities.result(BydVehicleField.SPEED)
        val speedValue = speed.value
        val speedText = when {
            speedValue != null -> getString(R.string.adb_vehicle_speed_reading, speedValue.roundToInt())
            speed.supported -> getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_speed))
            else -> getString(R.string.adb_vehicle_speed_unreadable)
        }
        card.addView(label(speedText, 14, if (speed.supported) MUTED else WARNING))
        val gear = capabilities.result(BydVehicleField.GEAR)
        val gearValue = gear.value
        val gearText = when {
            gearValue != null -> getString(R.string.adb_vehicle_gear_reading, gearLetter(gearValue.toInt()))
            gear.supported -> getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_gear))
            else -> getString(R.string.adb_vehicle_gear_unreadable)
        }
        card.addView(label(gearText, 14, if (gear.supported) MUTED else WARNING))

        if (capabilities.batterySupported) {
            val percentValue = capabilities.result(BydVehicleField.SOC).value
            val rangeValue = capabilities.result(BydVehicleField.RANGE).value
            val energy = capabilities.result(BydVehicleField.REMAINING_KWH).value
            card.addView(label(if (percentValue == null || rangeValue == null) {
                getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_battery))
            } else if (energy != null) {
                getString(R.string.vehicle_probe_battery_reading,
                    percentValue.roundToInt(), rangeValue.roundToInt(), energy)
            } else {
                getString(R.string.vehicle_probe_battery_without_energy,
                    percentValue.roundToInt(), rangeValue.roundToInt())
            }, 14, if (energy != null || percentValue == null) MUTED else WARNING))
        } else {
            card.addView(label(getString(R.string.adb_battery_unreadable), 14, WARNING))
        }
        val charging = capabilities.result(BydVehicleField.BMS_STATE)
        val chargingValue = charging.value
        val chargingText = when {
            chargingValue != null -> getString(R.string.vehicle_probe_charging_state, chargingValue.toInt())
            charging.supported -> getString(R.string.vehicle_probe_cached_supported, getString(R.string.vehicle_field_charging))
            else -> getString(R.string.vehicle_probe_charging_unreadable)
        }
        card.addView(label(chargingText, 14, if (charging.supported) MUTED else WARNING))
    }

    private fun showVehicleDataSettings(card: LinearLayout, capabilities: BydVehicleCapabilities?) {
        if (capabilities == null || capabilities.batterySupported) {
            toggle(card, getString(R.string.car_battery_for_the_iphone),
                getString(R.string.car_battery_for_the_iphone_description),
                BydOutputSettings.batteryToIphone(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setBatteryToIphone(this, it)
                onVehicleDataSettingChanged(it)
            }
            val connectors = EvChargingConnectors.entries
            choice(card, getString(R.string.charging_connectors), connectors.map { it.localizedLabel(this) },
                connectors.indexOf(BydOutputSettings.chargingConnectors(this))) {
                BydOutputSettings.setChargingConnectors(this, connectors[it])
            }
            val lowCharge = BydOutputSettings.lowChargePresets
            choice(card, getString(R.string.low_charge_warning), lowCharge.map {
                    getString(if (it == BydOutputSettings.DEFAULT_LOW_CHARGE_PERCENT)
                        R.string.percent_default else R.string.percent_value, it)
                }, lowCharge.indexOf(BydOutputSettings.lowChargePercent(this)).coerceAtLeast(0), reconnects = false) {
                BydOutputSettings.setLowChargePercent(this, lowCharge[it])
            }
        }
        if (capabilities == null || capabilities.motionSupported) {
            toggle(card, getString(R.string.wheel_speed_for_tunnels),
                getString(R.string.wheel_speed_for_tunnels_description),
                BydOutputSettings.wheelSpeedToIphone(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setWheelSpeedToIphone(this, it)
                onVehicleDataSettingChanged(it)
            }
        }
        if (capabilities == null || capabilities.gearSupported) {
            toggle(card, getString(R.string.video_while_parked),
                getString(R.string.video_while_parked_description),
                BydOutputSettings.videoWhileParked(this), enabled = !adbSwitchChangePending) {
                BydOutputSettings.setVideoWhileParked(this, it)
                onVehicleDataSettingChanged(it)
            }
        }
    }

    private fun onVehicleDataSettingChanged(enabled: Boolean) {
        if (!enabled) {
            reconnectForVehicleSetting()
        } else if (BydOutputSettings.legacyVehicleProbe(this)) {
            scheduleAutomaticVehicleValidation()
            reconnectForVehicleSetting()
        } else {
            // Default mode applies the switch once a check reads what the enabled switches need.
            vehicleDataReconnectPending = true
            checkAdbState(mayAsk = true)
        }
    }

    // The approval dialog can open only after an explicit user action.
    private fun checkAdbState(mayAsk: Boolean) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) return
        val legacy = BydOutputSettings.legacyVehicleProbe(this)
        // Only a validation this check interrupts runs again; a Dashboard switch needs no vehicle check.
        cancelAutomaticVehicleValidationForUserOperation(
            resumeAfter = automaticVehicleValidationInProgress || automaticVehicleValidationPending,
        )
        val generation = ++adbCheckGeneration
        adbCheckInProgress = true
        adbCheckMayAsk = mayAsk
        adbCheckFailed = false
        render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("diplay-adb-state") {
            val result = runCatching {
                if (legacy) {
                    BydAdbAccess.Status(backend.checkState(applicationContext, mayAsk))
                } else {
                    // The default battery path must publish its first sample before CarPlay reconnects.
                    backend.check(applicationContext, mayAsk)
                }
            }
            runOnUiThread {
                val current = generation == adbCheckGeneration
                if (current) {
                    adbCheckInProgress = false
                    adbCheckMayAsk = false
                }
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                adbCheckFailed = result.isFailure
                val status = result.getOrNull()
                adbAccessState = status?.state
                if (!legacy) defaultVehicleStatus = status
                if (!legacy && adbAccessState == BydAdbAccess.State.READY) {
                    vehicleProbeOutcome = null
                }
                render()
                // Unreadable data keeps the current connection; the page shows what is missing.
                if (!legacy && vehicleDataReconnectPending && status?.state == BydAdbAccess.State.READY &&
                    enabledVehicleDataReadable(null, status)) {
                    vehicleDataReconnectPending = false
                    reconnectForVehicleSetting()
                }
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    /** First probe or user retry: one click handles ADB authorization, probing and persistence. */
    private fun probeVehicleData(mayAsk: Boolean) =
        probeVehicleData(mayAsk, activateLegacyModeOnSuccess = false)

    private fun probeVehicleData(mayAsk: Boolean, activateLegacyModeOnSuccess: Boolean) {
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) return
        val expectedLegacyMode = BydOutputSettings.legacyVehicleProbe(this)
        val expectedSnapshot = cancelAutomaticVehicleValidationForUserOperation(resumeAfter = false)
        val generation = ++vehicleProbeGeneration
        vehicleProbeAuthorizationInProgress = mayAsk
        vehicleProbeInProgress = !mayAsk
        adbCheckFailed = false
        pendingVehicleReplacement = null
        pendingVehicleLostFields = emptySet()
        Log.i(BYD_VEHICLE_TAG, "user vehicle probe starting mayAsk=$mayAsk")
        render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("diplay-byd13-probe") {
            val app = applicationContext
            val attempt = runCatching {
                val access = backend.checkState(app, mayAsk)
                if (access != BydAdbAccess.State.READY) {
                    VehicleProbeAttempt(BydVehicleProbeOutcome(access))
                } else {
                    runOnUiThread {
                        if (generation == vehicleProbeGeneration && !isFinishing && !isDestroyed) {
                            vehicleProbeAuthorizationInProgress = false
                            vehicleProbeInProgress = true
                            render()
                        }
                    }
                    var candidate = backend.probe(app, persist = false)
                    if (candidate.access == BydAdbAccess.State.NOT_APPROVED) {
                        // adbd confirms "Always allow" before it saves the key, so this new connection can be early.
                        Log.i(BYD_VEHICLE_TAG, "probe connection not approved yet; retrying once")
                        Thread.sleep(ADB_KEY_SAVE_WAIT_MILLIS)
                        candidate = backend.probe(app, persist = false)
                    }
                    val candidateCapabilities = candidate.capabilities
                    when {
                        candidate.access == BydAdbAccess.State.NOT_APPROVED -> VehicleProbeAttempt(
                            outcome = candidate,
                            allowedOnlyOnce = true,
                        )
                        candidateCapabilities == null -> VehicleProbeAttempt(candidate)
                        else -> {
                            // Invalidation must guard persistence/publication, not only the UI callback.
                            val replacement = synchronized(vehicleOperationLock) {
                                if (generation != vehicleProbeGeneration ||
                                    BydOutputSettings.legacyVehicleProbe(app) != expectedLegacyMode) null
                                else BydVehicleFieldStore.replaceAutomatically(
                                    app,
                                    expectedSnapshot,
                                    candidateCapabilities,
                                )
                            } ?: return@runCatching VehicleProbeAttempt(
                                outcome = BydVehicleProbeOutcome(candidate.access),
                                snapshotChanged = true,
                            )
                            when {
                                replacement.saved -> VehicleProbeAttempt(candidate)
                                replacement.snapshotChanged -> VehicleProbeAttempt(
                                    outcome = candidate,
                                    snapshotChanged = true,
                                )
                                else -> VehicleProbeAttempt(
                                    outcome = BydVehicleProbeOutcome(candidate.access),
                                    heldCandidate = candidateCapabilities,
                                    lostFields = replacement.lostFields,
                                )
                            }
                        }
                    }
                }
            }.getOrElse { error ->
                VehicleProbeAttempt(
                    BydVehicleProbeOutcome(
                        BydAdbAccess.State.READY,
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
            runOnUiThread {
                val current = generation == vehicleProbeGeneration
                if (current) {
                    vehicleProbeAuthorizationInProgress = false
                    vehicleProbeInProgress = false
                }
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                pendingVehicleReplacement = attempt.heldCandidate
                pendingVehicleReplacementExpected = expectedSnapshot
                pendingVehicleLostFields = attempt.lostFields
                vehicleProbeOutcome = when {
                    attempt.allowedOnlyOnce -> attempt.outcome.copy(
                        error = getString(R.string.vehicle_probe_allowed_once),
                    )
                    attempt.snapshotChanged -> attempt.outcome.copy(
                        capabilities = null,
                        error = getString(R.string.vehicle_probe_snapshot_changed),
                    )
                    else -> attempt.outcome
                }
                adbAccessState = attempt.outcome.access
                val activatedLegacyMode = activateLegacyModeOnSuccess &&
                    vehicleProbeOutcome?.capabilities != null
                if (activatedLegacyMode) {
                    BydOutputSettings.setLegacyVehicleProbe(this, true)
                }
                if (vehicleProbeOutcome?.capabilities != null) automaticVehicleValidationStarted = true
                Log.i(
                    BYD_VEHICLE_TAG,
                    "user vehicle probe access=${attempt.outcome.access} " +
                        "saved=${vehicleProbeOutcome?.capabilities != null} " +
                        "lost=${attempt.lostFields.joinToString()} " +
                        "error=${vehicleProbeOutcome?.error ?: "none"}",
                )
                render()
                if (activatedLegacyMode && vehicleDataSwitchesOn()) reconnectForVehicleSetting()
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    /**
     * After the first saved probe, validation is automatic and never asks for authorization. ADB
     * transport failure keeps the saved snapshot; only two complete READY-but-unreadable checks
     * trigger one automatic field re-probe.
     */
    private fun validateSavedVehicleConfigurationAutomatically() {
        if (!BydOutputSettings.legacyVehicleProbe(this)) return
        val saved = BydVehicleFieldStore.load(applicationContext) ?: return
        if (adbSwitchChangePending || vehicleAdbWorkInProgress()) {
            automaticVehicleValidationPending = true
            return
        }
        if (automaticVehicleValidationInProgress) {
            // A request during a run, such as a switch just turned on, runs once this one ends.
            automaticVehicleValidationPending = true
            return
        }
        if (automaticVehicleValidationStarted) return
        automaticVehicleValidationStarted = true
        automaticVehicleValidationInProgress = true
        val generation = ++vehicleValidationGeneration
        Log.i(BYD_VEHICLE_TAG, "automatic vehicle validation starting savedFirmware=${saved.firmwareKey}")
        if (page == "settings" && bydVehicleAdvancedExpanded) render()
        val backend = BydVehicleSettingsBackendProvider.current
        backend.execute("diplay-byd13-auto-validate") {
            val app = applicationContext
            val validation = runCatching {
                var status = backend.check(app, mayAsk = false)
                var outcome: BydVehicleProbeOutcome? = null
                var heldCandidate: BydVehicleCapabilities? = null
                var lostFields: Set<BydVehicleField> = emptySet()
                var snapshotChanged = false
                var readable = status.state == BydAdbAccess.State.READY && enabledVehicleDataReadable(saved, status)
                if (status.state == BydAdbAccess.State.READY && !enabledVehicleDataReadable(saved, status)) {
                    Log.w(BYD_VEHICLE_TAG, "saved vehicle fields unreadable; validating once more")
                    try {
                        Thread.sleep(VEHICLE_VALIDATION_RETRY_MILLIS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    // A cancelled validation reads nothing more alongside the operation that replaced it.
                    if (generation != vehicleValidationGeneration) {
                        return@runCatching VehicleValidationAttempt(
                            status = status,
                            snapshotChanged = true,
                        )
                    }
                    status = backend.check(app, mayAsk = false)
                    readable = status.state == BydAdbAccess.State.READY && enabledVehicleDataReadable(saved, status)
                    if (status.state == BydAdbAccess.State.READY && !readable) {
                        if (generation != vehicleValidationGeneration) {
                            return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                        }
                        Log.w(BYD_VEHICLE_TAG, "saved vehicle fields still unreadable; automatic re-probe starting")
                        val candidate = backend.probe(app, persist = false)
                        if (generation != vehicleValidationGeneration) {
                            return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                        }
                        outcome = candidate
                        candidate.capabilities?.let { next ->
                            val replacement = synchronized(vehicleOperationLock) {
                                if (generation != vehicleValidationGeneration) null
                                else BydVehicleFieldStore.replaceAutomatically(app, saved, next)
                            } ?: return@runCatching VehicleValidationAttempt(
                                status = status,
                                snapshotChanged = true,
                            )
                            when {
                                replacement.saved -> Unit
                                replacement.snapshotChanged -> {
                                    snapshotChanged = true
                                    outcome = null
                                }
                                else -> {
                                    heldCandidate = next
                                    lostFields = replacement.lostFields
                                    outcome = BydVehicleProbeOutcome(candidate.access)
                                }
                            }
                        }
                    }
                }
                VehicleValidationAttempt(
                    status = status,
                    outcome = outcome,
                    heldCandidate = heldCandidate,
                    lostFields = lostFields,
                    snapshotChanged = snapshotChanged,
                    savedFieldsReadable = readable,
                )
            }
            runOnUiThread {
                val current = generation == vehicleValidationGeneration
                if (current) automaticVehicleValidationInProgress = false
                if (!current || isFinishing || isDestroyed) return@runOnUiThread
                adbCheckFailed = validation.isFailure
                validation.exceptionOrNull()?.let { Log.w(BYD_VEHICLE_TAG, "automatic vehicle validation failed", it) }
                validation.getOrNull()?.let { result ->
                    adbAccessState = result.outcome?.access ?: result.status.state
                    when {
                        result.snapshotChanged -> Unit
                        result.savedFieldsReadable && result.outcome == null -> {
                            // An open offer to replace stays: this check may not have read its lost fields.
                            vehicleProbeOutcome = null
                        }
                        result.lostFields.isNotEmpty() -> {
                            vehicleProbeOutcome = result.outcome
                            pendingVehicleReplacement = result.heldCandidate
                            pendingVehicleReplacementExpected = saved
                            pendingVehicleLostFields = result.lostFields
                        }
                        result.outcome != null -> {
                            vehicleProbeOutcome = result.outcome
                            pendingVehicleReplacement = null
                            pendingVehicleLostFields = emptySet()
                        }
                    }
                    Log.i(
                        BYD_VEHICLE_TAG,
                        "automatic vehicle validation access=${adbAccessState} " +
                            "reprobed=${result.outcome != null} " +
                            "saved=${result.outcome?.capabilities != null && result.lostFields.isEmpty()} " +
                            "lost=${result.lostFields.joinToString()} " +
                            "snapshotChanged=${result.snapshotChanged} " +
                            "error=${result.outcome?.error ?: "none"}",
                    )
                }
                if (page == "settings" && bydVehicleAdvancedExpanded) {
                    render()
                }
                runPendingAutomaticVehicleValidation()
            }
        }
    }

    private fun scheduleAutomaticVehicleValidation() {
        if (!BydOutputSettings.legacyVehicleProbe(this)) {
            automaticVehicleValidationPending = false
            handler.removeCallbacks(automaticVehicleValidation)
            return
        }
        automaticVehicleValidationStarted = false
        automaticVehicleValidationPending = true
        handler.removeCallbacks(automaticVehicleValidation)
        handler.post(automaticVehicleValidation)
    }

    private fun cancelAutomaticVehicleValidationForUserOperation(
        resumeAfter: Boolean,
    ): BydVehicleCapabilities? = synchronized(vehicleOperationLock) {
        handler.removeCallbacks(automaticVehicleValidation)
        automaticVehicleValidationPending = resumeAfter
        if (resumeAfter) automaticVehicleValidationStarted = false
        vehicleValidationGeneration++
        automaticVehicleValidationInProgress = false
        BydVehicleFieldStore.load(applicationContext)
    }

    private fun runPendingAutomaticVehicleValidation() {
        if (!automaticVehicleValidationPending || adbSwitchChangePending ||
            vehicleAdbWorkInProgress() || automaticVehicleValidationInProgress) return
        handler.removeCallbacks(automaticVehicleValidation)
        handler.post(automaticVehicleValidation)
    }

    /** Whether [status] has every reading an enabled switch needs; null [capabilities] is default mode. */
    private fun enabledVehicleDataReadable(
        capabilities: BydVehicleCapabilities?,
        status: BydAdbAccess.Status,
    ): Boolean {
        if (BydOutputSettings.batteryToIphone(this) && capabilities?.batterySupported != false &&
            (status.batteryPercent == null || status.rangeKm == null)) return false
        if (BydOutputSettings.wheelSpeedToIphone(this) && capabilities?.motionSupported != false &&
            (status.speedKmh == null || status.gear == null)) return false
        if (BydOutputSettings.videoWhileParked(this) && capabilities?.gearSupported != false && status.gear == null) return false
        return true
    }

    private fun adbLinkStatusText(state: BydAdbAccess.State): String = when (state) {
        BydAdbAccess.State.READY -> getString(R.string.adb_access_ready)
        BydAdbAccess.State.NOT_APPROVED -> getString(R.string.adb_enabled_not_approved)
        BydAdbAccess.State.ADB_OFF -> getString(R.string.adb_off)
        BydAdbAccess.State.PAIRING_ONLY -> getString(R.string.adb_pairing_only)
    }

    private fun gearLetter(value: Int): String = when (value) {
        1 -> "P"
        2 -> "R"
        3 -> "N"
        else -> "D"
    }

    private fun reconnectForVehicleSetting() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun vehicleDataSwitchesOn() = BydOutputSettings.batteryToIphone(this) ||
        BydOutputSettings.wheelSpeedToIphone(this) || BydOutputSettings.videoWhileParked(this)

    private fun hasPreciseLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // The cluster screen is described at connection time, so a running session reconnects over
    // its current link. The position choices need no call: getString(R.string.apply_and_reconnect) already does it.
    private fun reconnectForClusterMap() {
        // In-place hot restart: keep the projection window and both resident stream surfaces
        // (110/111), so applying a cluster setting never re-activates stream 111 and the head
        // unit does not announce "全屏导航已开启". The old path (stop + openProjection) tore
        // down the host activity, dismissing the window and re-announcing on every change.
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.restartCarPlay()
    }

    private fun onLocationReportingChanged(enabled: Boolean) {
        if (enabled && !hasPreciseLocation()) {
            // Keep the switch off until precise location is actually granted.
            render()
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
            return
        }
        applyLocationReporting(enabled)
    }

    private fun applyLocationReporting(enabled: Boolean) {
        if (AirPlayPersistence.loadLocationReportingEnabled(this) == enabled) return
        AirPlayPersistence.saveLocationReportingEnabled(this, enabled)
        render()
        // Location support is advertised during iAP2 identification, so both enabling and
        // disabling it require a new session. The host also refreshes its location service type.
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
        else toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        startupHotspotCancelled = true
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, getString(R.string.carplay_size), sizes.map { it.localizedLabel(this) }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(getString(R.string.changes_the_size_of_carplay_icons_and_text_applying_a_size), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun connect(wireless: Boolean) {
        if (!com.freddyq.diplay.customization.VehicleCustomizations.allowAutomaticStartup(this)) return
        startupHotspotCancelled = true
        // The bootstrap now runs after the first frame; make sure it is done before connecting.
        if (setupError == null) {
            setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
                android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
                getString(R.string.setup_error_auth)
            }
        }
        if (wireless && pendingCarHotspotSetup) { toast(getString(R.string.save_your_hotspot_details_in_connection_setup_first)); page = "connection"; render(); return }
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            pendingCarHotspotSetup = true
            page = "connection"
            render()
            toast(getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings))
            return
        }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            launchCarPlayScreen()
        }
        if (CarPlayBackgroundSession.hasSession()) {
            CarPlayBackgroundSession.releaseSettingsHold()
            CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        }
        else open()
    }
    /**
     * Brings the CarPlay screen forward. This only starts the activity - it sends no projection
     * command. It used to be called openProjection(), which read like InCallClusterBridge's
     * openProjection() (the real activation sequence) and made every audit of "what can trigger
     * an announcement" report this settings screen as a suspect.
     */
    private fun launchCarPlayScreen() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            AlertDialog.Builder(this).setTitle(getString(R.string.turn_on_bluetooth))
                .setMessage(getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.later), null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle(getString(R.string.pair_your_iphone))
                .setMessage(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.got_it), null).show(); return
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.choose_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(R.string.pair_another)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        AlertDialog.Builder(this).setTitle(getString(R.string.wireless_connection_help))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.reset_carplay_wi_fi)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        AlertDialog.Builder(this).setTitle(getString(R.string.reset_carplay_wi_fi_2))
            .setMessage(getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.releaseSettingsHold()
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.closeCompat(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.closeCompat(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.closeCompat(); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.closeCompat(); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            channel.closeCompat(); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        if (lastRunning != running) {
            connectButton?.text = if (running) getString(R.string.open_carplay) else getString(R.string.connect_phone)
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
    }
    private fun reportFileName() = "DiPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Some head units omit or disable DocumentsUI. Launch itself can throw, before
            // the result callback and the background writer's exception handler ever run.
            runCatching { export.launch(reportFileName()) }.onFailure {
                toast(getString(R.string.this_head_unit_could_not_open_a_save_location_please_try_s))
            }
        } else {
            // Head unit has no document picker: save straight into the app's own directory.
            exportDiagnostics()
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = buildDiagnosticReport(appContext)
                if (uri != null) {
                    DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                    DiagnosticExportStore.SavedReport(uri)
                } else DiagnosticExportStore.saveWithoutPicker(appContext, fileName, report)
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val savedReport = result.getOrThrow()
                    AlertDialog.Builder(this).setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(when {
                            savedReport.savedInApp -> getString(R.string.diagnostic_report_saved_in_app)
                            savedReport.savedPath != null -> getString(R.string.diagnostic_report_saved_to_path, savedReport.savedPath)
                            uri == null -> "Downloads/DiPlay/$fileName"
                            else -> getString(R.string.your_report_was_saved_to_the_selected_location)
                        })
                        .setPositiveButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedReport.uri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedReport.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { showDiagnosticsOnScreen() }
                        }.show()
                } else {
                    AlertDialog.Builder(this).setTitle(getString(R.string.could_not_save_the_report))
                        .setMessage(getString(R.string.check_that_storage_is_available_or_choose_another_save_loc))
                        .setPositiveButton(getString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(R.string.close), null).show()
                }
            }
        }, "diplay-export").start()
    }

    /** Deletes every on-device diagnostic log (diplay.log + previous*.log) so the next
     *  connection records fresh; an already-running session simply appends to new files. */
    private fun clearDiagnosticLogs() {
        runCatching {
            val dir = File(filesDir, "logs")
            dir.listFiles()?.filter { it.name.endsWith(".log") }?.forEach { it.delete() }
        }
        Toast.makeText(this, getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
    }

    /** Shows the full diagnostic report directly on the head unit screen, for screenshot capture. */
    private fun showDiagnosticsOnScreen() {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.generating_report) }
        val appContext = applicationContext
        Thread({
            val report = runCatching { buildDiagnosticReport(appContext) }
                .getOrElse { "Diagnostics unavailable: $it" }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                val body = TextView(this).apply {
                    text = report
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.diagnostics_on_screen))
                    .setView(ScrollView(this).apply { addView(body) })
                    .setPositiveButton(getString(R.string.close), null)
                    .show()
            }
        }, "diplay-show-diagnostics").start()
    }

    private fun buildDiagnosticReport(appContext: Context): String = buildString {
        appendLine("DiPlay ${version()} · private beta diagnostic report")
        appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
        appendLine("Authentication: local experimental beta identity; no remote fallback")
        appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
        appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
        appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
        appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
        appendLine(
            "Saved audio routing: mediaViaCarBluetooth=${AirPlayPersistence.loadMediaViaCarBluetooth(appContext)} " +
                "duckPercent=${AirPlayPersistence.loadNavDuckPercent(appContext)} " +
                "audioFocus=${AirPlayPersistence.loadAudioFocusEnabled(appContext)}",
        )
        appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
        // Every saved setting, so a report never needs the driver to say what they had configured.
        append(AirPlayPersistence.describeSavedSettings(appContext))
        appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
        // The version number does not change between test builds, so name the installed package:
        // it is the only way to tell whether a report came from the APK that was just built.
        runCatching {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            appendLine("Installed: ${stamp.format(java.util.Date(info.lastUpdateTime))}" +
                " (first: ${stamp.format(java.util.Date(info.firstInstallTime))})")
        }
        appendLine()
        appendLine("--- BYD vehicle-data probe ---")
        appendLine(
            "mode=${if (BydOutputSettings.legacyVehicleProbe(appContext)) "legacy-probe" else "default"} " +
                "switches location=${AirPlayPersistence.loadLocationReportingEnabled(appContext)} " +
                "battery=${BydOutputSettings.batteryToIphone(appContext)} " +
                "wheelSpeed=${BydOutputSettings.wheelSpeedToIphone(appContext)} " +
                "parkedVideo=${BydOutputSettings.videoWhileParked(appContext)}",
        )
        val bydCapabilities = BydVehicleFieldStore.load(appContext)
        if (bydCapabilities == null) {
            appendLine("no saved successful probe")
        } else {
            appendLine(
                "catalog=${bydCapabilities.catalogAvailable} detectedAt=${bydCapabilities.detectedAtMillis} " +
                    "savedFirmware=${bydCapabilities.firmwareKey} " +
                    "currentFirmware=${BydVehicleFieldStore.firmwareKey()}",
            )
            for (field in BydVehicleField.entries) {
                val probe = bydCapabilities.result(field)
                appendLine("${field.name}: supported=${probe.supported} " +
                    (probe.address?.let { "tx=${it.transaction} dev=${it.device} fid=${it.fid} source=${it.source}" }
                        ?: "address=none"))
            }
        }
        appendLine()
        appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
        appendLine(DisplayDiagnosticSnapshot.report(appContext))
        appendLine()
        appendLine("--- Last received boot and app-launch result ---")
        appendLine(StartupDiagnosticSnapshot.report(appContext))
        appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
            "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)}")
        appendLine()
        appendLine("--- Recent own-app process exits (Android 11+) ---")
        appendLine(ProcessExitDiagnostics.report(appContext))
        appendLine()
        for (name in SessionLogFile.REPORT_NAMES) {
            val file = File(appContext.filesDir, "logs/$name")
            if (file.isFile) {
                appendLine("--- $name ---")
                file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
            }
        }
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.open_this_setting_from_your_car_s_settings_app)) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun playTestTone(streamType: Int) {
        toneStop?.let { handler.removeCallbacks(it) }
        toneStop = null
        testToneTrack?.let { runCatching { it.stop(); it.release() } }
        testToneTrack = null
        var candidate: AudioTrack? = null
        val track = try {
            val pcm = assets.open("navigation_test.pcm").use { it.readBytes() }
            AudioTrack(streamType, 44100, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STREAM).also {
                candidate = it
                check(it.state == AudioTrack.STATE_INITIALIZED)
                check(it.write(pcm, 0, pcm.size) == pcm.size)
                it.play()
            }
        } catch (error: Exception) {
            val state = candidate?.state ?: AudioTrack.STATE_UNINITIALIZED
            candidate?.let { runCatching { it.release() } }
            Log.w("DiPlay", "playTestTone streamType=$streamType unavailable", error)
            toast(getString(R.string.audio_stream_unavailable, streamType, state))
            return
        }
        Log.i("DiPlay", "playTestTone streamType=$streamType state=${track.state} playState=${track.playState}")
        testToneTrack = track
        val stop = Runnable {
            track.stop()
            track.release()
            if (testToneTrack === track) testToneTrack = null
            toneStop = null
        }
        toneStop = stop
        handler.postDelayed(stop, 4500)
    }

    private val channelButtons = mutableListOf<Button>()

    private fun paintChannel(index: Int, selected: Boolean) {
        val target = channelButtons.getOrNull(index) ?: return
        target.isSelected = selected
        target.setTextColor(if (selected) BG else TEXT)
        target.background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x336F9FD9),
            rounded(if (selected) ACCENT else SURFACE, if (selected) ACCENT else BORDER),
            null
        )
    }

    private fun channelSelector(): ViewGroup {
        channelButtons.clear()
        val grid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 3
            setPadding(0, dp(8), 0, dp(8))
        }
        for (i in 0..20) {
            val btn = Button(this).apply {
                text = i.toString()
                isAllCaps = false
                textSize = 16f
                minHeight = dp(48)
                stateListAnimator = null
                setOnClickListener {
                    val previous = navigationStreamType
                    navigationStreamType = i
                    if (previous != i) {
                        paintChannel(previous, false)
                        paintChannel(i, true)
                    }
                    playTestTone(i)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(btn, params)
            channelButtons.add(btn)
            paintChannel(i, i == navigationStreamType)
        }
        return grid
    }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"

    /**
     * Debug and release share the .hudtest application id and the debug signature, so an
     * installed debug build upgrades straight to release. The build flavour shows in the
     * version line instead: "public preview" for debug, "public release" for release.
     */
    private fun debugBuild() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun homeVersionPrefix() =
        if (debugBuild()) R.string.home_public_preview else R.string.home_public_release

    private fun aboutVersionPrefix() =
        if (debugBuild()) R.string.about_public_preview_prefix else R.string.about_public_release_prefix
    private fun languageSettings(content: LinearLayout) {
        section(content, getString(R.string.language_section_title)) { card ->
            card.addView(label(getString(R.string.language_hint), 14, MUTED))
            val current = AppLocale.preference(this)
            val languageButton = button("${getString(R.string.language_app_language)} · ${AppLocale.displayName(this, current)}", false) { }
            languageButton.setOnClickListener { AppLocale.showPicker(this) }
            card.addView(languageButton, matchButton(12, 60))
        }
    }

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        // The group label sits outside the card: inside it, thirteen cards all shouting at 22sp is
        // what made the old page read like a document instead of a settings screen.
        val wrap = column()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(26), dp(16), dp(10)) }
        if (icon != null) heading.addView(ImageView(this).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(MUTED_DIM)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(8) })
        heading.addView(label(title, 11, MUTED_DIM, true).apply { letterSpacing = 0.08f })
        wrap.addView(heading)
        val card = groupCard()
        build(card)
        wrap.addView(card)
        wrap.tag = SectionTag(activeSectionGroups, card.childCount.coerceAtLeast(1))
        val sink = settingsSectionSink
        if (sink != null && settingsStaging === parent) {
            sink.add(wrap)
        } else {
            parent.addView(wrap, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun groupCard() = column().apply {
        GlassUi.lift(this)
        setPadding(dp(16), dp(4), dp(16), dp(4))
    }

    /**
     * One inset block per dashboard card: its own show switch, size and position. The block sits
     * inside the cluster section's card with a darker fill and its own border, which is what
     * separates "车道指引卡片" from the card above and below it - the previous flat run of rows mixed
     * four cards' settings together with nothing but vertical spacing between them.
     */
    private fun cardGroup(parent: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        val group = column().apply {
            GlassUi.lift(this, nested = true)
            setPadding(dp(14), dp(2), dp(14), dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        }
        val head = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(4)) }
        head.addView(label(title, 14, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        group.addView(head)
        build(group)
        parent.addView(group)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(GlassUi.divider)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    /**
     * A drawn chevron instead of a "\u203A" glyph: the glyph carries its own side bearings, so a
     * column of value rows looked slightly crooked. Two stroked lines are exact.
     */
    private fun chevron(): View {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED_DIM
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        return object : View(this) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                val w = width.toFloat()
                val h = height.toFloat()
                if (w <= 0f || h <= 0f) return
                canvas.drawLine(w * 0.36f, h * 0.22f, w * 0.74f, h * 0.5f, paint)
                canvas.drawLine(w * 0.74f, h * 0.5f, w * 0.36f, h * 0.78f, paint)
            }
        }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    }

    /**
     * A long setting description collapses into a two-line summary plus this badge. Tapping it
     * shows the whole text, so the explanatory paragraphs stay available without making every row
     * four lines tall on a 720p head unit.
     */
    private fun detailBadge(title: String, description: String): TextView = label("!", 10, BG, true).apply {
        gravity = Gravity.CENTER
        background = rounded(ACCENT, ACCENT, 11)
        contentDescription = getString(R.string.settings_setting_details)
        isClickable = true
        setOnClickListener {
            AlertDialog.Builder(this@DiPlayActivity)
                .setTitle(title)
                .setMessage(description)
                .setPositiveButton(getString(R.string.close), null)
                .show()
        }
    }

    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, enabled: Boolean = true, save: (Boolean) -> Unit): Switch {
        if (parent.childCount > 0) parent.addView(divider())
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(62); setPadding(0, dp(11), 0, dp(11)) }
        val text = column()
        val head = row().apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(title, 15, if (enabled) TEXT else MUTED, true), LinearLayout.LayoutParams(-2, -2))
        text.addView(head)
        text.addView(label(description, 12, MUTED).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), dp(14), 0)
        })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        // Its own column rather than a marker after the title: titles differ in length, and a badge
        // that follows each one lands on a different x per row, which is what made the list look
        // crooked. Beside the switch they line up.
        if (description.length > 110) {
            line.addView(
                detailBadge(title, description),
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) },
            )
        }
        val control = Switch(this).apply {
            contentDescription = title
            isChecked = value
            isEnabled = enabled
            showText = false
            splitTrack = false
            minHeight = dp(48)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ACCENT_TRACK, TRACK_OFF),
            )
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ACCENT, THUMB_OFF),
            )
            setOnCheckedChangeListener { _, checked -> save(checked) }
        }
        line.addView(control)
        // The whole row is a target: a 48dp switch is fiddly with a finger on a moving car.
        line.setOnClickListener { if (control.isEnabled) control.isChecked = !control.isChecked }
        parent.addView(line)
        return control
    }

    // [announcesReconnect] labels a choice whose [save] reconnects by itself.
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true,
        announcesReconnect: Boolean = reconnects, enabled: Boolean = true, save: (Int) -> Unit) {
        var selection = current.coerceIn(0, (options.size - 1).coerceAtLeast(0))
        if (parent.childCount > 0) parent.addView(divider())
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(60); setPadding(0, dp(10), 0, dp(10)) }
        line.addView(label(title, 15, if (enabled) TEXT else MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label(options.getOrElse(selection) { "" }, 13, if (enabled) ACCENT else MUTED_DIM)
        line.addView(value)
        line.addView(chevron(), LinearLayout.LayoutParams(dp(12), dp(20)).apply { marginStart = dp(4) })
        line.isClickable = enabled
        line.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(getString(if (announcesReconnect && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        value.text = options.getOrElse(selection) { "" }
                        if (reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }
        parent.addView(line)
    }

    /**
     * A choice row that is not backed by a fixed index list (the channel pickers build their own
     * dialog). Returns the value label so the caller can refresh it after a pick.
     */
    private fun valueRow(parent: LinearLayout, title: String, initial: String, onClick: () -> Unit): TextView {
        if (parent.childCount > 0) parent.addView(divider())
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(60); setPadding(0, dp(10), 0, dp(10)) }
        line.addView(label(title, 15, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label(initial, 13, ACCENT)
        line.addView(value)
        line.addView(chevron(), LinearLayout.LayoutParams(dp(12), dp(20)).apply { marginStart = dp(4) })
        line.isClickable = true
        line.setOnClickListener { onClick() }
        parent.addView(line)
        return value
    }

    private fun card() = column().apply { GlassUi.lift(this); setPadding(dp(24), dp(24), dp(24), dp(24)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size * textScale; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 16f * textScale
        val exit = title == DiPlayAppExit.title(this@DiPlayActivity)
        setTextColor(if (primary) Color.WHITE else if (exit) GlassUi.danger else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        backgroundTintList = null
        background = GlassUi.button(this@DiPlayActivity, primary, destructive = exit)
        setPadding(dp(14), 0, dp(14), 0); minHeight = dp(48); stateListAnimator = null
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int, radiusDp: Int = 20) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat(); setStroke(dp(1), stroke)
    }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }

    /**
     * Left rail of the settings page: one entry per group. Rebuilding the rail is cheap (six rows)
     * and keeps its own highlight in sync without touching the section column, so switching groups
     * never re-renders the page and never loses in-progress state such as a pending ADB prompt.
     */
    private fun buildSettingsRail(rail: LinearLayout) {
        rail.removeAllViews()
        rail.setPadding(dp(10), dp(14), dp(10), dp(14))
        val items = listOf(
            R.string.settings_group_frequent to GROUP_FREQUENT,
            R.string.settings_group_connect to GROUP_CONNECT,
            R.string.settings_group_display to GROUP_DISPLAY,
            R.string.settings_group_sound to GROUP_SOUND,
            R.string.settings_group_cluster to GROUP_CLUSTER,
            R.string.settings_group_system to GROUP_SYSTEM,
        )
        for ((titleRes, group) in items) {
            val selected = settingsGroup == group
            val item = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(10), 0)
                minimumHeight = dp(56)
                background = if (selected) rounded(GlassUi.selected, BORDER, 14) else null
                isClickable = true
                setOnClickListener {
                    if (settingsGroup == group) return@setOnClickListener
                    settingsGroup = group
                    buildSettingsRail(rail)
                    applyGroupFilter()
                    rootScroll?.scrollTo(0, 0)
                }
            }
            item.addView(View(this).apply {
                setBackgroundColor(if (selected) ACCENT else Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(dp(3), dp(18)).apply { marginEnd = dp(10) })
            item.addView(label(getString(titleRes), 15, if (selected) TEXT else MUTED, selected))
            rail.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        }
    }

    /**
     * Distributes the visible sections over the two content columns.
     *
     * One column when the group has a single section (a lone half-width card next to empty space
     * looked like a rendering bug), two balanced columns otherwise: the head unit is 1920px wide
     * and a single full-width column left a third of the screen blank.
     */
    private fun applyGroupFilter() {
        val sink = settingsSectionSink ?: return
        val left = settingsLeft ?: return
        val right = settingsRight ?: return
        left.removeAllViews()
        right.removeAllViews()
        val visible = sink.filter { sectionGroupsOf(it) and settingsGroup != 0 }
        // Narrow panels (1024x600 dashes) keep one column: two 230dp columns cannot hold a label
        // and a 150dp number input side by side.
        val screenDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        if (visible.size <= 1 || screenDp < 900) {
            right.visibility = View.GONE
            visible.forEach { left.addView(it) }
            return
        }
        right.visibility = View.VISIBLE
        var leftRows = 0
        var rightRows = 0
        for (view in visible) {
            if (leftRows <= rightRows) {
                left.addView(view)
                leftRows += sectionRowsOf(view)
            } else {
                right.addView(view)
                rightRows += sectionRowsOf(view)
            }
        }
    }

    private fun sectionGroupsOf(view: View): Int =
        (view.tag as? SectionTag)?.groups ?: (view.tag as? Int ?: GROUP_ALL)

    private fun sectionRowsOf(view: View): Int = (view.tag as? SectionTag)?.rows ?: 3

    private data class SectionTag(val groups: Int, val rows: Int)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BYD_VEHICLE_TAG = "DiPlay-BYD13"
        private const val VEHICLE_VALIDATION_RETRY_MILLIS = 500L
        private const val ADB_KEY_SAVE_WAIT_MILLIS = 500L
        // Settings navigation groups. A section carries a mask so one build of the page can serve
        // every group: switching tabs only flips child visibility, which is what keeps both copies
        // of a switch from disagreeing about the same preference.
        const val GROUP_FREQUENT = 1
        const val GROUP_CONNECT = 1 shl 1
        const val GROUP_DISPLAY = 1 shl 2
        const val GROUP_SOUND = 1 shl 3
        const val GROUP_CLUSTER = 1 shl 4
        const val GROUP_SYSTEM = 1 shl 5
        const val GROUP_ALL = (1 shl 6) - 1
        // Accent follows the CarPlay menu (MENU_ACCENT in CarPlayHostActivity): one palette across
        // both screens instead of a blue settings page next to a green menu.
        private val BG = GlassUi.background
        private val SURFACE = GlassUi.surface
        private val RAIL = GlassUi.inset
        private val BORDER = GlassUi.border
        private val ACCENT = GlassUi.accent
        private val ACCENT_TRACK = GlassUi.accentTrack
        private val TRACK_OFF = GlassUi.trackOff
        private val THUMB_OFF = GlassUi.thumbOff
        private val TEXT = GlassUi.text
        private val MUTED = GlassUi.secondary
        private val MUTED_DIM = GlassUi.muted
        private val WARNING = GlassUi.warning
    }
}
