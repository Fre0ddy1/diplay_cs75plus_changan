package com.freddyq.diplay.hotspot

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.freddyq.diplay.customization.*
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering

class HotspotStartupCustomization : VehicleCustomization {
    override val id = "hotspot-startup"
    private var worker: HotspotStartupWorker? = null
    internal var createWorker: (Context) -> HotspotStartupWorker = ::newWorker
    @Volatile private var foreground: ApplicationForegroundControl? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastNotice: CarHotspotTethering.Result? = null
    private var lastNoticeAt = 0L
    override fun automaticHotspotDefault(context: Context) = true
    override fun platformHotspotCredentials(context: Context) = SavedHotspotReader.read(context)
    override fun platformHotspotStarter(context: Context): PlatformHotspotStarter {
        val app = context.applicationContext
        return FrameworkHotspotStarter(app, ::diagnostic, restored = {
            main.post {
                if (VehicleCustomizations.allowAutomaticStartup(app) && CarHotspotSettings.enabled(app)) {
                    Toast.makeText(app, R.string.hotspot_ready_notice, Toast.LENGTH_SHORT).show()
                    diagnostic("hotspot restored notice shown")
                }
            }
        })
    }

    private fun selected() = foreground?.current()?.let { it.wireless && it.vehicleHotspot } == true
    override fun onApplicationForeground(context: Context, control: ApplicationForegroundControl): Boolean {
        foreground = control
        val state = control.current()
        if (!state.wireless || !state.vehicleHotspot || !CarHotspotSettings.enabled(context)) {
            worker?.cancel()
            return true
        }
        val app = context.applicationContext
        val task = worker ?: createWorker(app).also { worker = it }
        task.request()
        return true
    }
    private fun newWorker(app: Context) = HotspotStartupWorker(
            execute = { job -> Thread(job, "diplay-hotspot-recovery").start() },
            eligible = { VehicleCustomizations.allowAutomaticStartup(app) && CarHotspotSettings.enabled(app) && selected() },
            enable = { cancelled -> CarHotspotTethering.enable(app, cancelled) { diagnostic(it) } },
            finished = { result -> main.post { report(app, result) } },
        )
    private fun diagnostic(message: String) {
        Log.i("DiPlay-Hotspot", message)
        VehicleCustomizations.diagnostic("HotspotStartup", message)
    }
    private fun report(app: Context, result: CarHotspotTethering.Result) {
        diagnostic("foreground recovery ${result.name}")
        if (!VehicleCustomizations.allowAutomaticStartup(app) || !CarHotspotSettings.enabled(app) || !selected() ||
            result == CarHotspotTethering.Result.READY || result == CarHotspotTethering.Result.CANCELLED) return
        val now = SystemClock.elapsedRealtime()
        if (lastNotice == result && now - lastNoticeAt < 30_000L) return
        lastNotice = result; lastNoticeAt = now
        val message = when (result) {
            CarHotspotTethering.Result.PERMISSION_REQUIRED -> if (CarHotspotTethering.permitted(app)) R.string.hotspot_system_denied_notice else R.string.hotspot_permission_notice
            CarHotspotTethering.Result.UNSUPPORTED -> R.string.hotspot_interface_notice
            else -> R.string.hotspot_failed_notice
        }
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }
    override fun settings(context: Context): List<CustomizationSettingsGroup> {
        val app = context.applicationContext
        val actions = if (CarHotspotTethering.permitted(app)) emptyList() else listOf(
            CustomizationSettingsAction(R.string.hotspot_grant_title, R.string.hotspot_grant_description) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { app.startActivity(intent) }.onFailure {
                    Toast.makeText(app, R.string.hotspot_permission_unavailable, Toast.LENGTH_LONG).show()
                }
            },
        )
        return listOf(CustomizationSettingsGroup(R.string.hotspot_group, listOf(
            CustomizationToggle(R.string.hotspot_auto_title, R.string.hotspot_auto_description, CarHotspotSettings.enabled(app)) {
                CarHotspotSettings.setEnabled(app, it)
                if (!it) worker?.cancel() else worker?.request()
            },
        ), actions, CustomizationSettingsArea.CONNECTION))
    }
}

class HotspotStartupInitializer : ContentProvider() {
    override fun onCreate(): Boolean { VehicleCustomizations.register(HotspotStartupCustomization()); return true }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
