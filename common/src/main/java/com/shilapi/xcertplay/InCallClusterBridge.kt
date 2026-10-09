package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log

/**
 * InCallClusterBridge
 *
 * Full activation sequence ported from the UNI-V CarPlay box's
 * `the reference UNI-V box` (decompiled from the reference box).
 * This is the head-unit-level "open/close dashboard projection" handshake that
 * the stock Amap normally performs when navigation starts; the box repeats it
 * so a three-finger swipe can project CarPlay to the instrument cluster
 * WITHOUT launching the stock Amap first.
 *
 * Structure (aligned 1:1 with InstrumentBridge):
 *   openProjection() = V() = E0 + Z + J0 (binder path):
 *     E0: F0() ext-screen clear (10116/10117 op=0 mode=2 -> 4 pkgs)
 *         A0() 10019 EXTRA_STATE ladder {49,3,50,311,2001,15,116,319,3025,46,-1,200,417},
 *              one state every 90 ms; state -1 also re-sends 10117(0,2)
 *         p0() 10019 state=9 then 12/40 ladder at {250,1200,2200,3500} (absolute)
 *     Z()  CarProperty 557855845=3 / 557855847=1
 *     J0() binder connected -> G0; otherwise F0+D0(9)+l0(waits for binder)
 *     G0: tx29(INavigateCallback stub) register callback
 *         F0() + 10019 state=9 + n0() repeat F0 at {80,180,420,900,1600,2400,3600,5200,7600}
 *         C0(1) C0(3): tx21[1,0]+tx8[mode,0]+tx1[0]
 *         Z() CarProperty, then tx2[1] at +90 ms
 *         H0 hold loop F0+C0(3)+Z at {80,180,420}
 *         (no D0(0) anywhere in the open path)
 *   closeProjection() = t0: r0() ext-screen reset (op/status 2,2 -> 4 pkgs + op/status 0,1
 *         -> incall alone), s0() tx21[1,1]+tx8[3,0]+tx1[1], repeated at {40,90,160,240},
 *         h0() repeat r0+s0 at {900,1600,2600,4200,5600,7200}, j0() D0(0) at +5 s,
 *         v0() tx30 unregister callback at +5 s.
 *
 * The earlier port was behaviourally wrong in several ways (fixed here): the open
 * sequence used the close-side ext-screen values (2,2+0,1) instead of F0 (0,2);
 * the A0 10019 state ladder and tx2[1] were missing entirely; the 12/40 ladder and
 * the repeat points were treated as cumulative intervals instead of absolute offsets;
 * the open path wrongly scheduled the close-side D0(0) (+5 s) which ended the
 * projection a few seconds after activation (user-verified: picture vanished and the
 * stock navigation channel was released); and tx29 registered the service binder
 * instead of a real INavigateCallback stub.
 *
 * All calls are best-effort: failures are logged and never thrown. The head unit
 * may reject a call without system signature; the log will show which steps
 * actually landed so it can be tuned per ROM.
 */
object InCallClusterBridge {
    private const val TAG = "InCallClusterBridge"

    // ---- service targets (from the reference UNI-V box) ----
    private const val PKG_INCALL = "com.incall.interactive"
    private const val SERVICE_ACTION = "com.incall.double.INTERACTIVE_SERVICE"
    private const val SERVICE_CLASS = "com.incall.interactive.service.DoubleInteractiveService"
    private const val BINDER_TOKEN = "com.incall.serversdk.interactive.IDouInteractiveManager"
    private const val CALLBACK_TOKEN = "com.incall.serversdk.interactive.callback.INavigateCallback"

    // ---- broadcast extras (decrypted) ----
    private const val ACTION_AUTONAVI = "AUTONAVI_STANDARD_BROADCAST_SEND"
    private const val EXTRA_KEY_TYPE = "KEY_TYPE"
    private const val EXTRA_STATE = "EXTRA_STATE"
    private const val EXTRA_CROSS_MAP = "EXTRA_CROSS_MAP"
    private const val EXTRA_OPERATE = "EXTSCREEN_OPERATE_TYPE"
    private const val EXTRA_STATUS = "EXTSCREEN_STATUS_INFO"
    private const val EXTRA_WINDOW = "EXTSCREEN_WINDOW_INFO"
    private const val EXTRA_MODE = "EXTSCREEN_MODE_INFO"

    // ---- packages that receive the 10116/10117 ext-screen broadcasts ----
    private val EXTERNAL_SCREEN_PKGS = arrayOf(
        "com.incall.interactive",
        "com.iflytek.autofly.launcher",
        "com.iflytek.autofly.applet",
        "com.incall.naviadapter",
    )

    // ---- CarProperty ids (decrypted: setIntProperty(557855845, 0, 3) etc.) ----
    private const val CAR_PROP_SOURCE = 557855845
    private const val CAR_PROP_MODE = 557855847

    // ---- timing sequences from the box ----
    // f5334u: repeated F0() (ext-screen clear) points inside the open sequence
    private val OPEN_RETRY_DELAYS = longArrayOf(80, 180, 420, 900, 1600, 2400, 3600, 5200, 7600)
    // f5336w: hold-loop points (F0 + C0(3) + Z)
    private val HOLD_RETRY_DELAYS = longArrayOf(80, 180, 420)
    // i.d(): 12/40 source-switch ladder, NON-cumulative absolute offsets
    private val SOURCE_SWITCH_DELAYS = intArrayOf(250, 1200, 2200, 3500)
    // i.c(): the 10019 EXTRA_STATE ladder A0() broadcasts, one every 90 ms.
    // This is the head-unit's projection-mode state machine — previously missing entirely.
    private val STATE_LADDER = intArrayOf(49, 3, 50, 311, 2001, 15, 116, 319, 3025, 46, -1, 200, 417)

    private var contextRef: Context? = null
    private var bound = false
    private var binder: IBinder? = null
    private var callbackRegistered = false
    private var openState = false      // f5349k (open in progress / active)
    private var generation = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val token = Any()

    // Real INavigateCallback stub, matching the reference box: the head-unit
    // registers callbacks against this interface descriptor; passing any other binder
    // (e.g. the service binder itself) makes registration fail silently on its side.
    private val navigateCallback = object : Binder(), IInterface {
        init {
            attachInterface(this, CALLBACK_TOKEN)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                1598968902 -> {
                    reply?.writeString(CALLBACK_TOKEN)
                    return true
                }
                1, 2 -> {
                    data.enforceInterface(CALLBACK_TOKEN)
                    val value = data.readInt()
                    Log.i(TAG, "INavigateCallback code=$code value=$value")
                    reply?.writeNoException()
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "service connected: $name")
            binder = service
            bound = true
            if (openState) runOpenLocked()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "service disconnected: $name")
            binder = null
            bound = false
            callbackRegistered = false
        }
    }

    /** Whether the incall interactive service exists on this head-unit. */
    fun isAvailable(context: Context): Boolean {
        contextRef = context.applicationContext
        return try {
            val pm = context.packageManager
            pm.getApplicationInfo(PKG_INCALL, 0)
            true
        } catch (e: Exception) {
            Log.w(TAG, "incall.interactive not installed: ${e.message}")
            false
        }
    }

    /**
     * Open the dashboard projection (the full W→E0+G0 sequence from the box).
     * Returns immediately; the handshake continues on the binder thread.
     */
    fun openProjection(context: Context) {
        contextRef = context.applicationContext
        val ctx = contextRef ?: return
        mainHandler.removeCallbacksAndMessages(token)
        generation++
        openState = true
        // E0 half (broadcasts + CarProperty, does not need the binder): F0 + A0 state
        // ladder + p0 9/12/40 ladder + Z + J0. Runs immediately so the head-unit starts
        // switching to projection mode while the service bind is in flight.
        runOpenBroadcastSide()
        ensureBound(ctx)
        if (binder != null) runOpenLocked()
    }

    private fun ensureBound(ctx: Context) {
        if (bound && binder != null) return
        if (bound && binder == null) {
            // disconnected: unbind and retry once
            try {
                ctx.unbindService(connection)
            } catch (_: IllegalArgumentException) {
            }
            bound = false
        }
        if (bound) return
        val intent = Intent(SERVICE_ACTION).setComponent(
            ComponentName(PKG_INCALL, SERVICE_CLASS),
        )
        try {
            bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService -> $bound")
        } catch (e: RuntimeException) {
            bound = false
            Log.w(TAG, "bindService failed", e)
        }
    }

    // ---- the E0 broadcast half (InstrumentBridge.E0): F0 + A0 + p0 + Z + J0 ----

    private fun runOpenBroadcastSide() {
        val gen = generation
        logSeq(gen, "E0: F0 + A0 state ladder + p0 + Z + J0")
        clearScreenOpen()
        // A0: 10019 EXTRA_STATE ladder {49,3,50,311,2001,15,116,319,3025,46,-1,200,417},
        // one state every 90 ms (cumulative). state -1 additionally re-sends 10117(0,2).
        var t = 0L
        for (s in STATE_LADDER) {
            val at = t
            val state = s
            mainHandler.postAtTime({
                if (gen != generation) return@postAtTime
                broadcastSource(state)
                if (state == -1) broadcastStatusOpen()
            }, token, SystemClock.uptimeMillis() + at)
            t += 90L
        }
        // p0: D0(9) then 12/40 ladder at NON-cumulative offsets {250,1200,2200,3500}.
        broadcastSource(9)
        for ((i, d) in SOURCE_SWITCH_DELAYS.withIndex()) {
            mainHandler.postAtTime({
                if (gen != generation) return@postAtTime
                broadcastSource(if (i == 0) 12 else 40)
            }, token, SystemClock.uptimeMillis() + d.toLong())
        }
        // Z: CarProperty immediately (no binder dependency).
        setCarProperties()
        // NOTE: the UNI-V open sequence (V = E0 + Z + J0) does NOT contain any
        // delayed D0(0). D0(0) is the CLOSE-side signal (t0 -> j0 -> k0 at +5 s) that
        // ends the projection session. The earlier port mistakenly scheduled a D0(0)
        // here 5 s after open, so the head-unit received EXTRA_STATE=0 (projection
        // end) a few seconds after activation — the dashboard picture vanished and
        // the stock navigation channel got released (user-verified). Removed.
    }

    // ---- the G0 binder half (InstrumentBridge.G0) ----

    private fun runOpenLocked() {
        val b = binder ?: return
        val gen = generation
        logSeq(gen, "G0: register + F0 + 9 + n0(F0) + C0(1) + C0(3) + Z + tx2 + hold")
        registerCallback(b)
        clearScreenOpen()
        broadcastSource(9)
        // n0: repeat F0() at f5334u points (ext-screen clear only).
        repeatAt(OPEN_RETRY_DELAYS) {
            if (gen != generation) return@repeatAt
            if (!alive()) return@repeatAt
            clearScreenOpen()
        }
        setMode(1)
        setMode(3)
        setCarProperties()
        // f0/g0: 90 ms later tx2[1] (dashboard source switch confirmation).
        mainHandler.postAtTime({
            if (gen != generation) return@postAtTime
            if (!alive()) return@postAtTime
            val ok = txWrite(b, 2, intArrayOf(1))
            logSeq(gen, "tx2[1] -> $ok")
        }, token, SystemClock.uptimeMillis() + 90L)
        // H0 hold loop: F0 + C0(3) + Z at f5336w points.
        repeatAt(HOLD_RETRY_DELAYS) {
            if (gen != generation) return@repeatAt
            if (!alive()) return@repeatAt
            clearScreenOpen()
            setMode(3)
            setCarProperties()
        }
    }

    // ---- building blocks ----

    private fun alive(): Boolean {
        val b = binder
        if (b == null || !b.isBinderAlive) {
            binder = null
            ensureBound(contextRef ?: return false)
            return binder != null
        }
        return true
    }

    private fun logSeq(gen: Int, msg: String) {
        Log.i(TAG, "[seq $gen] $msg")
    }

    private fun registerCallback(b: IBinder): Boolean {
        val ok = txBinder(b, 29, navigateCallback.asBinder())
        callbackRegistered = ok
        logSeq(generation, "registerCallback tx29(INavigateCallback) -> $ok")
        return ok
    }

    private fun unregisterCallback(b: IBinder) {
        if (!callbackRegistered) return
        val ok = txBinder(b, 30, navigateCallback.asBinder())
        callbackRegistered = false
        logSeq(generation, "unregisterCallback tx30 -> $ok")
    }

    private fun setMode(mode: Int) {
        val b = binder ?: return
        txWrite(b, 21, intArrayOf(1, 0))
        txWrite(b, 8, intArrayOf(mode, 0))
        txWrite(b, 1, intArrayOf(0))
        logSeq(generation, "setMode($mode) done")
    }

    // F0(): open-sequence ext-screen clear — 10116 op=0 mode=2 + 10117 status=0 mode=2 to all
    // 4 packages (InstrumentBridge.F0 = w0(0,2) + y0(0,2)). NOTE: the earlier port wrongly
    // used the close-side r0() values here; the head-unit expects 0/2 during open.
    private fun clearScreenOpen() {
        broadcastOperateAll(0, 2)
        broadcastStatusAll(0, 2)
    }

    private fun broadcastSource(state: Int) {
        val ctx = contextRef ?: return
        val intent = Intent(ACTION_AUTONAVI).apply {
            setPackage(PKG_INCALL)
            putExtra(EXTRA_KEY_TYPE, 10019)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_CROSS_MAP, -1)
            addFlags(0x1000000)
        }
        runCatching { ctx.sendBroadcast(intent) }
            .onSuccess { logSeq(generation, "broadcast 10019 state=$state") }
            .onFailure { Log.w(TAG, "broadcast 10019 state=$state failed", it) }
    }

    private fun broadcastOperateAll(op: Int, mode: Int) {
        for (pkg in EXTERNAL_SCREEN_PKGS) broadcastOperateTo(pkg, op, mode)
    }

    private fun broadcastOperateTo(op: Int, mode: Int) {
        val ctx = contextRef ?: return
        val intent = Intent(ACTION_AUTONAVI).apply {
            setPackage(PKG_INCALL)
            putExtra(EXTRA_KEY_TYPE, 10116)
            putExtra(EXTRA_OPERATE, op)
            putExtra(EXTRA_WINDOW, 1001)
            putExtra(EXTRA_MODE, mode)
            addFlags(0x1000000)
        }
        runCatching { ctx.sendBroadcast(intent) }
            .onSuccess { logSeq(generation, "broadcast 10116 op=$op mode=$mode -> $PKG_INCALL") }
            .onFailure { Log.w(TAG, "broadcast 10116 failed", it) }
    }

    private fun broadcastOperateTo(pkg: String, op: Int, mode: Int) {
        val ctx = contextRef ?: return
        val intent = Intent(ACTION_AUTONAVI).apply {
            setPackage(pkg)
            putExtra(EXTRA_KEY_TYPE, 10116)
            putExtra(EXTRA_OPERATE, op)
            putExtra(EXTRA_WINDOW, 1001)
            putExtra(EXTRA_MODE, mode)
            addFlags(0x1000000)
        }
        runCatching { ctx.sendBroadcast(intent) }
            .onSuccess { logSeq(generation, "broadcast 10116 op=$op mode=$mode -> $pkg") }
            .onFailure { Log.w(TAG, "broadcast 10116 -> $pkg failed", it) }
    }

    private fun broadcastStatusAll(status: Int, mode: Int) {
        for (pkg in EXTERNAL_SCREEN_PKGS) broadcastStatusTo(pkg, status, mode)
    }

    // y0(0,2): 10117 status=0 mode=2 to all 4 packages — sent when the A0 ladder hits -1.
    private fun broadcastStatusOpen() {
        broadcastStatusAll(0, 2)
    }

    private fun broadcastStatusTo(status: Int, mode: Int) {
        val ctx = contextRef ?: return
        val intent = Intent(ACTION_AUTONAVI).apply {
            setPackage(PKG_INCALL)
            putExtra(EXTRA_KEY_TYPE, 10117)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_WINDOW, 1001)
            putExtra(EXTRA_MODE, mode)
            addFlags(0x1000000)
        }
        runCatching { ctx.sendBroadcast(intent) }
            .onSuccess { logSeq(generation, "broadcast 10117 status=$status mode=$mode -> $PKG_INCALL") }
            .onFailure { Log.w(TAG, "broadcast 10117 failed", it) }
    }

    private fun broadcastStatusTo(pkg: String, status: Int, mode: Int) {
        val ctx = contextRef ?: return
        val intent = Intent(ACTION_AUTONAVI).apply {
            setPackage(pkg)
            putExtra(EXTRA_KEY_TYPE, 10117)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_WINDOW, 1001)
            putExtra(EXTRA_MODE, mode)
            addFlags(0x1000000)
        }
        runCatching { ctx.sendBroadcast(intent) }
            .onSuccess { logSeq(generation, "broadcast 10117 status=$status mode=$mode -> $pkg") }
            .onFailure { Log.w(TAG, "broadcast 10117 -> $pkg failed", it) }
    }

    private fun setCarProperties() {
        // Car.createCar(context).getCarManager("instrument_panel").setIntProperty(id, 0, value)
        // via reflection so a non-system app is not hard-failed at class-load time.
        val ctx = contextRef ?: return
        try {
            val carClass = Class.forName("android.car.Car")
            val createCar = carClass.getMethod("createCar", Context::class.java)
            val car = createCar.invoke(null, ctx) ?: return
            val manager = carClass.getMethod("getCarManager", String::class.java)
                .invoke(car, "instrument_panel") ?: return
            val setter = manager.javaClass.getMethod("setIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            setter.invoke(manager, CAR_PROP_SOURCE, 0, 3)
            setter.invoke(manager, CAR_PROP_MODE, 0, 1)
            logSeq(generation, "CarProperty setIntProperty(557855845,3)/(557855847,1) ok")
        } catch (e: Exception) {
            Log.w(TAG, "CarProperty set failed (non-system app likely)", e)
        }
    }

    // ---- raw Binder transactions on IDouInteractiveManager ----

    private fun txWrite(b: IBinder, code: Int, ints: IntArray): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(BINDER_TOKEN)
            for (v in ints) data.writeInt(v)
            val ok = b.transact(code, data, reply, 0)
            runCatching { reply.readException() }
            return ok
        } catch (e: Exception) {
            Log.w(TAG, "tx$code ${ints.contentToString()} failed", e)
            return false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun txBinder(b: IBinder, code: Int, callback: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(BINDER_TOKEN)
            data.writeStrongBinder(callback)
            val ok = b.transact(code, data, reply, 0)
            runCatching { reply.readException() }
            return ok
        } catch (e: Exception) {
            Log.w(TAG, "tx$code binder failed", e)
            return false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    // The reference box repeats use the array values as ABSOLUTE offsets from now (postAtTime + value),
    // not cumulative intervals — a cumulative ladder would fire far too late.
    private fun repeatAt(delays: LongArray, action: () -> Unit) {
        for (d in delays) {
            mainHandler.postAtTime({ action() }, token, SystemClock.uptimeMillis() + d)
        }
    }
}
