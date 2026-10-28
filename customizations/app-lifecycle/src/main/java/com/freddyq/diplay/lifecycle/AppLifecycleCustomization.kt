package com.freddyq.diplay.lifecycle

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.freddyq.diplay.customization.ApplicationExitControl
import com.freddyq.diplay.customization.ApplicationExitUi
import com.freddyq.diplay.customization.VehicleCustomization
import com.freddyq.diplay.customization.VehicleCustomizations

class AppLifecycleCustomization : VehicleCustomization {
    override val id = "app-lifecycle"
    private val main = Handler(Looper.getMainLooper())
    private var exiting = false
    internal var components: (Context) -> BackgroundComponents = { OwnBackgroundComponents(it) }
    override fun applicationExitUi() = ApplicationExitUi(R.string.exit_diplay_title, R.string.exit_diplay_description, R.string.exit_diplay_progress)
    override fun allowAutomaticStartup(context: Context) =
        !context.getSharedPreferences(PREFS, 0).getBoolean("user_stopped", false)
    override fun onManualApplicationLaunch(context: Context) {
        if (exiting || allowAutomaticStartup(context)) return
        components(context).restore()
        check(context.getSharedPreferences(PREFS, 0).edit().putBoolean("user_stopped", false).commit())
        VehicleCustomizations.diagnostic("ApplicationExit", "manual launch restored app components")
    }
    override fun requestApplicationExit(context: Context, control: ApplicationExitControl): Boolean {
        if (Looper.myLooper() != main.looper) { main.post { requestApplicationExit(context, control) }; return true }
        if (exiting) return true
        check(context.getSharedPreferences(PREFS, 0).edit().putBoolean("user_stopped", true).commit())
        exiting = true
        VehicleCustomizations.diagnostic("ApplicationExit", "user requested stop until manual launcher open")
        var completed = false
        val finish = Runnable {
            if (!completed) {
                completed = true
                try { components(context).suspend(control.automaticActivities) }
                catch (error: Exception) { Log.w("DiPlay-Exit", "Own component suspension incomplete", error) }
                runCatching { control.removeTasks() }.onFailure { Log.w("DiPlay-Exit", "Task removal incomplete", it) }
                main.postDelayed({ control.terminate() }, 200L)
            }
        }
        main.postDelayed(finish, 12_000L)
        try { control.shutdown { main.post { main.removeCallbacks(finish); finish.run() } } }
        catch (error: Exception) { Log.w("DiPlay-Exit", "Shutdown failed; bounded cleanup retained", error) }
        return true
    }
    companion object { const val PREFS = "diplay_application_lifecycle" }
}

class AppLifecycleInitializer : ContentProvider() {
    override fun onCreate(): Boolean { VehicleCustomizations.register(AppLifecycleCustomization()); return true }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
