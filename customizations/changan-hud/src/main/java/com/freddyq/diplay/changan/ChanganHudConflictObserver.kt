package com.freddyq.diplay.changan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Observes global foreign resets. Our own HUD packets target interactive and cannot loop back. */
internal class ChanganHudConflictObserver(
    private val app: Context,
    private val onReset: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var registered = false
    @Volatile private var generation = 0L
    @Volatile private var pending: Runnable? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val kind = resetKind(intent) ?: return
            if (!registered || pending != null) return
            val ticket = generation
            lateinit var task: Runnable
            task = Runnable {
                if (pending === task) pending = null
                if (registered && ticket == generation) onReset(kind)
            }
            pending = task
            handler.postDelayed(task, SETTLE_MS)
        }
    }

    @Synchronized fun start() {
        if (registered) return
        generation++
        val filter = IntentFilter(ChanganHudProfile.ACTION)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            app.registerReceiver(receiver,filter)
        }
        registered = true
    }

    @Synchronized fun stop() {
        generation++
        pending?.let(handler::removeCallbacks)
        pending = null
        if (registered) {
            registered = false
            app.unregisterReceiver(receiver)
        }
    }

    companion object {
        const val SETTLE_MS = 20L
        fun resetKind(intent: Intent): String? {
            if (intent.action != ChanganHudProfile.ACTION) return null
            // Defensive even on runtimes/test doubles that do not filter targeted broadcasts.
            if (intent.`package` == ChanganHudProfile.RECEIVER_PACKAGE) return null
            return when (intent.getIntExtra("KEY_TYPE",-1)) {
                10001 -> if (intent.getIntExtra("NEW_ICON",-1)==0 && intent.getIntExtra("SEG_REMAIN_DIS",-1)==0) "guidance-zero" else null
                10019 -> intent.getIntExtra("EXTRA_STATE",-1).takeIf { it==9 || it==12 }?.let { "navigation-clear-$it" }
                else -> null
            }
        }
    }
}
