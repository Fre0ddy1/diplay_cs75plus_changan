package com.freddyq.diplay.customization

import android.content.ComponentName

data class ApplicationExitUi(val title: Int, val description: Int, val progress: Int)

interface ApplicationExitControl {
    val automaticActivities: List<ComponentName>
    fun shutdown(completed: () -> Unit)
    fun removeTasks()
    /** Terminates this app's own process only, after cleanup. */
    fun terminate()
}
