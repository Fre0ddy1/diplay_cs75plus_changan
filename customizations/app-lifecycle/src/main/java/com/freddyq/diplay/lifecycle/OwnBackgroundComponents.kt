package com.freddyq.diplay.lifecycle

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject

internal interface BackgroundComponents {
    fun suspend(automaticActivities: List<ComponentName>)
    fun restore()
}

/** Reversible component states inside this package; pairing, user preferences and other apps stay out. */
internal class OwnBackgroundComponents(private val app: Context) : BackgroundComponents {
    private val prefs get() = app.getSharedPreferences(AppLifecycleCustomization.PREFS, 0)
    private val manager get() = app.packageManager
    @Suppress("DEPRECATION")
    private fun declared() = manager.getPackageInfo(app.packageName,
        PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS)

    override fun suspend(automaticActivities: List<ComponentName>) {
        val info = declared()
        val background = info.services.orEmpty().toList() + info.receivers.orEmpty().toList()
        val names = background.map { ComponentName(app.packageName, it.name) } + automaticActivities
        val journal = JSONObject(prefs.getString("component_states", "{}") ?: "{}")
        names.filter { it.packageName == app.packageName }.forEach { component ->
            val state = manager.getComponentEnabledSetting(component)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                if (!journal.has(component.className)) journal.put(component.className, state)
            }
        }
        check(prefs.edit().putString("component_states", journal.toString()).commit())
        val keys = journal.keys().asSequence().toList()
        keys.forEach { name ->
            manager.setComponentEnabledSetting(ComponentName(app.packageName, name),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        info.services.orEmpty().forEach { service ->
            runCatching { app.stopService(android.content.Intent().setComponent(ComponentName(app.packageName, service.name))) }
        }
    }
    override fun restore() {
        val journal = JSONObject(prefs.getString("component_states", "{}") ?: "{}")
        val info = declared()
        val present = (info.services.orEmpty().map { it.name } + info.receivers.orEmpty().map { it.name } +
            info.activities.orEmpty().map { it.name }).toSet()
        journal.keys().asSequence().toList().forEach { name ->
            if (name in present) manager.setComponentEnabledSetting(ComponentName(app.packageName, name),
                journal.getInt(name), PackageManager.DONT_KILL_APP)
        }
        check(prefs.edit().remove("component_states").commit())
    }
}
