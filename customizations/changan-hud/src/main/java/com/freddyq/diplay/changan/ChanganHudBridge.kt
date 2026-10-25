package com.freddyq.diplay.changan

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.freddyq.diplay.customization.VehicleCustomizations
import com.shilapi.xcertplay.customization.CustomizationOutputWorker
import com.shilapi.xcertplay.customization.PhoneNavigationDecoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Ordinary-app broadcasts only. Lifetime follows the CarPlay session, not its Activity. */
object ChanganHudBridge {
    private const val TAG = "DiPlay-Changan-HUD"
    private const val PROJECTION_SETTLE_NS = 1_500_000_000L
    private val worker = CustomizationOutputWorker("diplay-changan-hud", ::clearOutput)
    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diplay-changan-hud-tick").apply { isDaemon = true }
    }
    @Volatile private var runtime: Runtime? = null
    private var ticks: ScheduledFuture<*>? = null

    private class Runtime(val app: Context) {
        @Volatile var projection = ChanganHudProjection()
        private var frameCount = 0
        private var lastIcon: Int? = null
        val session = ChanganHudSession(projection = { projection }) { packet ->
            try {
                app.sendBroadcast(packet.toIntent())
                if (packet is ChanganHudPacket.State) {
                    diagnostic("state=${packet.value} uid=${Process.myUid()} frames=$frameCount target=${ChanganHudProfile.RECEIVER_PACKAGE}")
                    if (packet.value == 8) { frameCount = 0; lastIcon = null }
                } else if (packet is ChanganHudPacket.Guidance) {
                    frameCount++
                    if (lastIcon != packet.frame.icon) {
                        diagnostic("guidance icon=${packet.frame.icon} meters=${packet.frame.distanceMeters}")
                        lastIcon = packet.frame.icon
                    }
                    Log.d(TAG, "guidance icon=${packet.frame.icon} meters=${packet.frame.distanceMeters}")
                }
                true
            } catch (error: RuntimeException) {
                Log.w(TAG, "Broadcast failed; output will retry while connected", error)
                false
            }
        }
    }

    @Synchronized fun start(context: Context) {
        if (!ChanganHudSettings.receiverInstalled(context)) {
            diagnostic("interactive receiver not installed; HUD setting remains available")
            return
        }
        worker.start {
            val output = runtime ?: Runtime(context.applicationContext).also { runtime = it }
            output.session.start()
            diagnostic("session ready enabled=${ChanganHudSettings.enabled(output.app)} retention=session")
        }
        if (ticks?.isCancelled != false) {
            ticks = timer.scheduleAtFixedRate({ worker.submit { tick() } }, 1, 1, TimeUnit.SECONDS)
        }
    }

    internal fun onFrame(messageId: Int, payload: ByteArray) {
        if (messageId != PhoneNavigationDecoder.ROUTE_GUIDANCE_UPDATE &&
            messageId != PhoneNavigationDecoder.MANEUVER_UPDATE) return
        worker.submit {
            runtime?.let { it.session.onFrame(messageId, payload, ChanganHudSettings.enabled(it.app)) }
        }
    }

    internal fun settingsChanged() = worker.submit { tick() }

    /** Called before the existing projection code sends one of its navigation-reset states. */
    @Synchronized fun projectionStateChanging(state: Int) {
        val output = runtime ?: return
        if (!ChanganHudSettings.enabled(output.app) || !ChanganHudSession.projectionStateClearsGuidance(state)) return
        output.projection = ChanganHudProjection(output.projection.revision + 1, System.nanoTime() + PROJECTION_SETTLE_NS)
        diagnostic("projection state=$state; restore fresh guidance after handshake settles")
    }

    @Synchronized fun clear() {
        if (runtime == null && ticks == null) return
        ticks?.cancel(false)
        ticks = null
        worker.clear()
    }

    private fun tick() {
        runtime?.let { it.session.tick(ChanganHudSettings.enabled(it.app)) }
    }

    private fun clearOutput() {
        runtime?.session?.stop()
    }

    private fun diagnostic(message: String) {
        Log.i(TAG, message)
        VehicleCustomizations.diagnostic("ChanganHUD", message)
    }
}

internal fun ChanganHudPacket.toIntent(): Intent = Intent(ChanganHudProfile.ACTION).apply {
    setPackage(ChanganHudProfile.RECEIVER_PACKAGE)
    addFlags(0x01000000)
    when (val packet = this@toIntent) {
        is ChanganHudPacket.State -> {
            putExtra("KEY_TYPE", 10019)
            putExtra("EXTRA_STATE", packet.value)
            putExtra("EXTRA_CROSS_MAP", 0)
        }
        is ChanganHudPacket.Guidance -> {
            val frame = packet.frame
            putExtra("KEY_TYPE", 10001)
            putExtra("TYPE", 0)
            putExtra("ICON", frame.icon)
            putExtra("NEW_ICON", frame.icon)
            putExtra("SEG_REMAIN_DIS", frame.distanceMeters)
            putExtra("CUR_ROAD_NAME", frame.currentRoad)
            putExtra("NEXT_ROAD_NAME", frame.road)
            // The S311 receiver reads ROUND; AMap and the reference sender also spell it ROUNG.
            putExtra("ROUND_ABOUT_NUM", frame.roundaboutExit)
            putExtra("ROUNG_ABOUT_NUM", frame.roundaboutExit)
        }
    }
}
