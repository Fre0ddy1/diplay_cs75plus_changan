package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.freddyq.diplay.customization.ProjectionControl
import com.freddyq.diplay.customization.ProjectionLayout
import com.freddyq.diplay.customization.VehicleCustomizations
import java.lang.ref.WeakReference

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
 *         p0() 10019 state=9 then 12/40 ladder at {250,1200} (absolute)
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
 * NOTE (never ported): the binder-side close (s0/h0/j0/v0) is deliberately NOT implemented.
 * Field testing showed it ends the head-unit's projection session for good - every later
 * openProjection() is ignored until the head-unit restarts. releaseExternalScreen() below sends
 * ONLY r0 (the ext-screen hand-back), which is what the driver's "close" swipe needs: the OEM
 * UI comes back, the projection session and our bound surfaces stay alive.
 *
 * Every call is bind-gated: the projection-mode state ladder is meaningless to an incall service
 * that is still starting, so openProjection waits (bounded) for the service connection before
 * sending anything, and runs the whole sequence - broadcast half included - once it lands.
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
 *
 * ---- WHAT CAN MAKE INCALL ANNOUNCE (single source of truth; read this before changing any
 * ---- send path, so a stale comment cannot mislead again) ----
 *
 * The voice ("全屏导航已开启" / "全屏镜像已开启") is produced by the head unit, and only these
 * sends have been observed or are suspected to cause it:
 *
 *   1. the 10019 EXTRA_STATE ladder (A0) and the 9/12/40 source ladder (p0) - a source switch
 *   2. tx2[1], the source-switch confirmation
 *   3. (re-)activating a CarPlay video stream on the incall sink, notably 111
 *
 * F0 (ext-screen clear), the C0 mode writes (tx21/tx8/tx1) and Z (CarProperty) are the parts the
 * box itself repeats many times; none has ever been observed to announce. runSilentOpen() and
 * reassertProjection() are deliberately built from those parts only.
 *
 * NOT a signal: whether the dashboard display exists. Our Presentation is on display 1 within
 * 200-300 ms of the host starting, in sessions where openProjection() is never called at all, so
 * display topology is always "present" and says nothing about projection state.
 *
 * What IS a state signal: callback code 2 - value 3 while incall is projecting, value 0 when it is
 * not. openProjection() reads it before sending anything and takes the silent path when it already
 * reports projecting.
 *
 * Across a process restart that signal is gone: lastCallbackValue starts empty, so a fresh
 * process used to send the full sequence even when the projection was still warm (field report
 * 2026-10-18: "又出现了全屏导航已开启的播报" after every app restart / reconnect). Two things close
 * that gap. First, incall reports its state proactively when the callback is registered - observed
 * within ~20 ms in three of four cold starts - so a fresh process usually hears the truth before
 * auto-project fires. Second, the last known state is persisted: a fresh process that has heard
 * nothing yet can still take the silent half when the projection was warm because we never handed
 * the ext-screen back (force-kill or crash mid-projection). The silent half stays bounded: if
 * incall does not confirm within SILENT_CONFIRM_MS the full sequence runs anyway, because a
 * dashboard stuck on the head unit's own screen is worse than the one announcement a genuine
 * re-open makes. A proactive report always outranks the persisted state - it is incall's own word
 * about right now.
 *
 * After our own r0 hand-back the word is filtered, not trusted: incall answers the hand-back with
 * "value 3" (+30-43 ms measured) - that is the projection mode still being on, but the ext-screen
 * being the head unit's own screen again, NOT us being projected. So for
 * [R0_ANSWER_WINDOW_MS] after a r0 we send, a "projecting" report is ignored for the open decision
 * (logged), and the persisted flag [KEY_EXT_SCREEN_RELEASED_BY_US] keeps the silent half closed
 * until incall genuinely reports us again (the driver enabling the channel by hand, observed
 * +125 s later) or we take it back ourselves. Without that filter the r0 answer read as "still
 * projecting" and every reconnect after a close sent the silent half, which field-measured cannot
 * re-grab the ext-screen (2026-10-18: "关闭投影三指滑动无法投屏,需要在车机上手动开启投影通道").
 *
 * Still unproven, and deliberately left alone rather than "fixed" by guesswork: which single send
 * among (1)-(3) produces the one announcement a genuine cold-start switch still makes.
 *
 * Everything that used to send a source-switch signal more than once in an activation is gone:
 * the 12/40 ladder repeats, tx2 in the re-assert path, the retry ladder's full re-send, and the
 * duplicated state=9 (now sent once per open via sendSourceEnter()).
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
    // i.d(): 12/40 source-switch ladder, NON-cumulative absolute offsets.
    //
    // The source switch is the head unit's cue to announce "全屏导航已开启", and the reference box
    // repeated state=40 three times (1200/2200/3500 ms), so one activation produced three
    // announcements in a row - field report: "it announces three or four times, close together".
    // The switch itself only has to be signalled once: state=9 enters, state=12 selects, state=40
    // settles. Keeping the full progression and dropping the two redundant repeats leaves the state
    // machine identical while announcing once instead of three times. F0 (ext-screen) and C0/Z keep
    // their own repeats for reliability, and those are silent. If a cold start ever comes up blank
    // because the head unit ignored the single switch, restoring one repeat is a one-line change.
    private val SOURCE_SWITCH_DELAYS = intArrayOf(250, 1200)
    // A0: the 10019 EXTRA_STATE ladder A0() broadcasts, one every 90 ms.
    // This is the head-unit's projection-mode state machine — previously missing entirely.
    private val STATE_LADDER = intArrayOf(49, 3, 50, 311, 2001, 15, 116, 319, 3025, 46, -1, 200, 417)
    // How long to wait for the incall service binder before falling back to the broadcast-only
    // half of the open sequence (cold boot: the service can take a while to come up).
    private const val SERVICE_WAIT_FALLBACK_MS = 4_000L

    // ---- persisted projection state (survives a process restart) ----
    private const val PREFS_NAME = "InCallClusterBridge"
    private const val KEY_INCALL_PROJECTING = "incallProjecting"
    private const val KEY_EXT_SCREEN_RELEASED = "extScreenReleased"
    /**
     * We handed the ext-screen back ourselves (r0) and have not taken it back since.
     *
     * Unlike [KEY_EXT_SCREEN_RELEASED] this flag is cleared only by our own next full open, never
     * by an incall report. The head unit answers our r0 with "projecting" for ITS OWN screen
     * within tens of milliseconds (measured 30 ms and 43 ms in the field), and letting that answer
     * look like a projection to us is what kept the next open on the silent half - which never
     * re-grabs the ext-screen we just gave away. Field 2026-10-18: after a close swipe the
     * dashboard stayed on the head unit's own screen until the driver re-enabled the projection
     * channel by hand, and a plain reconnect after a disconnect had the same silent-half open and
     * the same blank dashboard.
     */
    private const val KEY_EXT_SCREEN_RELEASED_BY_US = "extScreenReleasedByUs"
    /**
     * How long a value-3 report after our own r0 still counts as the head unit answering that
     * hand-back rather than as "projecting to us again". Observed answers arrive in tens of
     * milliseconds; the next genuine "ours again" in the field logs came 125 s later (the driver
     * enabling the projection channel by hand after a close swipe).
     */
    private const val R0_ANSWER_WINDOW_MS = 2_000L
    /** Callback code that carries incall's projecting state (value 3 = projecting, 0 = not). */
    private const val CALLBACK_CODE_STATE = 2
    /**
     * How long the fresh-process silent open has to be confirmed by incall before the full open
     * sequence - the one that makes the head unit announce - runs anyway.
     */
    private const val SILENT_CONFIRM_MS = 4_000L

    private var contextRef: Context? = null
    private var bound = false
    private var binder: IBinder? = null
    private var callbackRegistered = false
    /** Set once the host asks us to watch the callback before any projection is requested. */
    private var observeCallbackRequested = false
    /** Last callback value reported per code, so only transitions reach the exported log. */
    private val lastCallbackValue = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /**
     * Set on the binder thread the first time incall calls back in this process. Binder liveness
     * only - it says nothing about the projection state, but it does separate a head unit that is
     * silent because it has nothing to say from one whose projection service is gone (adb mode).
     */
    @Volatile
    private var callbackHeard = false

    /**
     * What incall reports on callback code 2 once the cluster is being driven by us. Measured twice:
     * it is the value sent while the driver has the projection channel open by hand (before we send
     * anything), and the value the head unit settles on right after our source switch lands.
     */
    private const val CALLBACK_PROJECTING = 3

    /**
     * Whether incall's own last report says the cluster is already being projected.
     *
     * This is the signal that display topology cannot provide: the dashboard display exists from the
     * first moments of every session, including sessions where the open sequence never runs at all,
     * so only incall can say whether it is currently routing the cluster to us.
     */
    private fun incallReportsProjecting(): Boolean = lastCallbackValue[2] == CALLBACK_PROJECTING

    /**
     * incall's report, as the UI may act on it: projecting AND we have not handed the ext-screen
     * back since. The three-finger swipe uses it to choose between the quiet hot-switch and a real
     * re-open - a hot-switch onto a session the head unit no longer projects changes nothing on the
     * dashboard (field 2026-10-18 14:25: incall reported value 0, the left swipe logged
     * "已激活，热切换" and the cluster stayed on the head unit's own screen).
     */
    fun reportsProjecting(): Boolean = incallReportsProjecting() && !releasedByUs()

    /**
     * incall has said anything at all in this process (any callback code). False means the binder
     * has not produced a single word - a dead session (field: adb mode) or a head unit that is
     * simply quiet, which cannot be told apart, so callers must not treat it as "not projecting".
     */
    fun heardFromIncall(): Boolean = callbackHeard

    // ---- persisted projection state ----

    private fun prefs(): SharedPreferences? =
        contextRef?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** incall was routing the cluster to us the last time we heard from it (callback value 3). */
    private fun persistedProjecting(): Boolean =
        prefs()?.getBoolean(KEY_INCALL_PROJECTING, false) ?: false

    /** We handed the ext-screen back ourselves (r0) and the binder session was never torn down. */
    private fun persistedReleased(): Boolean =
        prefs()?.getBoolean(KEY_EXT_SCREEN_RELEASED, false) ?: false

    /**
     * We handed the ext-screen back ourselves and have not taken it back since - so incall is NOT
     * projecting to us right now, whatever it last reported. Persisted, because the flag has to
     * outlive both the process (force-kill right after a disconnect) and the head unit's own answer
     * to our r0, which reports "projecting" for the screen it just took back.
     */
    private fun releasedByUs(): Boolean =
        prefs()?.getBoolean(KEY_EXT_SCREEN_RELEASED_BY_US, false) ?: false

    private fun setReleasedByUs(value: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_EXT_SCREEN_RELEASED_BY_US, value)?.apply()
    }

    /**
     * Uptime of our own last r0 hand-back, 0 when this process never sent one. In-process only: it
     * exists to tell the head unit's answer to OUR r0 apart from a later report that means the
     * projection is ours again (the driver enabling the channel by hand, or the head unit
     * re-projecting us after it comes back from a dead state such as adb mode).
     */
    private var r0SentAtUptimeMs = 0L

    private fun withinR0AnswerWindow(): Boolean =
        r0SentAtUptimeMs > 0 && SystemClock.uptimeMillis() - r0SentAtUptimeMs <= R0_ANSWER_WINDOW_MS

    private fun persistProjecting(projecting: Boolean) {
        val editor = prefs()?.edit() ?: return
        editor.putBoolean(KEY_INCALL_PROJECTING, projecting)
        // incall reporting "projecting" is also the proof that our r0 hand-back has been undone.
        if (projecting) editor.putBoolean(KEY_EXT_SCREEN_RELEASED, false)
        editor.apply()
    }

    /**
     * True when this process has not heard from incall yet but the persisted state says the
     * projection is still warm because we never handed the ext-screen back: incall was routing the
     * cluster to us when we last knew (value 3 - a force-kill or crash during an active
     * projection). The full sequence's source switch would announce for nothing in that case, so
     * the silent half goes first. Deliberately NOT true after our own r0: there the ext-screen is
     * the head unit's again and the silent half has been measured not to take it back.
     */
    private fun warmStart(): Boolean = persistedProjecting()
    private var openState = false      // f5349k (open in progress / active)
    private var broadcastHalfSent = false
    /**
     * True when this open was started while incall already reported projecting, so only the parts
     * that cannot switch its projection source may be sent. Also consulted when the service connects
     * late, which would otherwise run the full sequence and undo the decision.
     */
    private var silentOpenOnly = false
    private var silentHoldScheduled = false
    /** Set once the entering state (9) has been sent for this open. */
    private var sourceEnterSent = false
    @Volatile private var generation = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val token = Any()
    private var observationOwner: WeakReference<Any>? = null
    private var projectionNeedsReconfirmation = false
    private var automaticProjectionOwner: Any? = null
    private val confirmationLock = Any()
    private var callbackSerial = 0L
    private var resumeGeneration: Int? = null
    private var resumeRequestSerial = Long.MAX_VALUE
    private var resumeFullSeen = false
    private var resumeOpenSeen = false
    private val projectionControl = object : ProjectionControl {
        override fun requestLayout(layout: ProjectionLayout): Boolean {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!openState || binder?.isBinderAlive != true) return false
            val mode = when (layout) {
                ProjectionLayout.CARD -> 1
                ProjectionLayout.FULLSCREEN -> 3
            }
            logSeq(generation, "customization layout request=$layout; existing C0($mode) + Z once")
            val dispatched = setMode(mode)
            setCarProperties()
            return dispatched
        }
    }

    private data class ConfirmationSample(val serial: Long, val waiting: Boolean, val confirmed: Boolean)

    private fun sampleConfirmation(code: Int, value: Int): ConfirmationSample = synchronized(confirmationLock) {
        callbackSerial++
        val waiting = resumeGeneration == generation
        if (waiting && callbackSerial > resumeRequestSerial) {
            if (code == 1) resumeFullSeen = value == 3
            if (code == CALLBACK_CODE_STATE) resumeOpenSeen = value == CALLBACK_PROJECTING
            if (resumeFullSeen && resumeOpenSeen) {
                resumeGeneration = null
                return@synchronized ConfirmationSample(callbackSerial, true, true)
            }
        }
        ConfirmationSample(callbackSerial, waiting, false)
    }

    /** One restore per phone/session owner; retained values alone cannot confirm a new window. */
    fun resumeAutomaticProjection(context: Context, owner: Any) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (automaticProjectionOwner === owner) return
        automaticProjectionOwner = owner
        if (!reportsProjecting()) return // The bounded channel watcher owns cold activation.
        contextRef = context.applicationContext
        val ctx = contextRef ?: return
        openState = true
        val needsFresh = projectionNeedsReconfirmation
        projectionNeedsReconfirmation = false
        if (needsFresh) {
            mainHandler.removeCallbacksAndMessages(token)
            generation++
        }
        val gen = generation
        val scoped = object : ProjectionControl {
            override fun requestLayout(layout: ProjectionLayout): Boolean {
                if (gen != generation || automaticProjectionOwner !== owner) return false
                synchronized(confirmationLock) {
                    if (resumeGeneration == gen) {
                        resumeRequestSerial = callbackSerial
                        resumeFullSeen = false
                        resumeOpenSeen = false
                    }
                }
                return projectionControl.requestLayout(layout)
            }
            override fun invalidateConfirmation() {
                if (gen != generation || automaticProjectionOwner !== owner) return
                synchronized(confirmationLock) { resumeGeneration = null }
                lastCallbackValue.remove(CALLBACK_CODE_STATE)
                persistProjecting(false)
                generation++ // Reject queued replies and controls from the expired owner.
                logSeq(generation, "retained confirmation expired; bounded channel watcher may recover")
            }
        }
        if (needsFresh) {
            synchronized(confirmationLock) {
                resumeGeneration = gen
                resumeRequestSerial = Long.MAX_VALUE
                resumeFullSeen = false
                resumeOpenSeen = false
            }
            if (VehicleCustomizations.onProjectionResumed(ctx, scoped)) {
                logSeq(gen, "new window on retained channel; waiting for fresh layout and channel replies")
                return
            }
            synchronized(confirmationLock) { resumeGeneration = null }
        }
        VehicleCustomizations.onProjectionStarted(ctx, true, scoped)
        val layout = when (lastCallbackValue[1]) {
            1 -> ProjectionLayout.CARD
            3 -> ProjectionLayout.FULLSCREEN
            else -> null
        }
        if (layout != null) VehicleCustomizations.onProjectionLayoutRequested(layout)
        logSeq(gen, "reattach retained projection strategy; same window layout=$layout")
    }

    /** Detach page/phone ownership without closing the retained physical channel. */
    fun detachAutomaticProjection(owner: Any) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (automaticProjectionOwner !== owner) return
        automaticProjectionOwner = null
        synchronized(confirmationLock) {
            if (resumeGeneration == generation) projectionNeedsReconfirmation = true
            resumeGeneration = null
        }
        generation++
        mainHandler.removeCallbacksAndMessages(token)
    }

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
                    val callbackGeneration = generation
                    val confirmation = sampleConfirmation(code, value)
                    callbackHeard = true
                    Log.i(TAG, "INavigateCallback code=$code value=$value")
                    // Persist the projecting state so a fresh process can tell a warm projection
                    // from a cold one without announcing (see the class doc).
                    if (code == CALLBACK_CODE_STATE) {
                        val projecting = value == CALLBACK_PROJECTING
                        if (projecting && releasedByUs() && withinR0AnswerWindow()) {
                            // The head unit answering our own r0 reports "projecting" for ITS OWN
                            // screen - the ext-screen is the head unit's again the moment the
                            // hand-back lands. Recording this as a projection to us is what kept
                            // the next open on the silent half, which never re-grabs what we just
                            // gave away (field 2026-10-18: blank dashboard after every reconnect
                            // and after every close swipe, until the driver re-enabled the
                            // projection channel on the head unit by hand).
                            Log.i(
                                TAG,
                                "r0 answer ignored for the open decision: value=3 " +
                                    "${SystemClock.uptimeMillis() - r0SentAtUptimeMs}ms after our hand-back",
                            )
                        } else {
                            // A projecting report outside that window used to end the released belief
                            // here, on the theory that the projection was ours again "by some other
                            // means". On this head unit it is never true: after every hand-back incall
                            // keeps answering 3 for its OWN screen (measured 2026-10-21: still value=3
                            // 5.9 s after the r0), so this line wiped the flag before the next open
                            // and sent that open down the silent half, which never re-grabs the
                            // ext-screen - the dashboard then stayed on "等待 CarPlay 地图". Only a
                            // sequence we actually ran (which resets r0SentAtUptimeMs to 0) may
                            // clear the belief, and runFullOpenSequence() already does that.
                            if (projecting && releasedByUs() && r0SentAtUptimeMs == 0L) {
                                setReleasedByUs(false)
                            }
                            persistProjecting(projecting)
                        }
                    }
                    // The head unit calling back is the only channel through which it can tell the
                    // app anything about the projection. Only transitions reach the exported log:
                    // during a state ladder the head unit answers every broadcast, and with the
                    // callback now registered before activation that would flood the report.
                    val previous = lastCallbackValue.put(code, value)
                    // code 1 is onRequestLVDS: 1=small, 3=full. Code 2 is a different status space.
                    if (code == 1) {
                        val layout = when (value) {
                            1 -> ProjectionLayout.CARD
                            3 -> ProjectionLayout.FULLSCREEN
                            else -> null
                        }
                        val gen = callbackGeneration
                        if (layout != null && !(confirmation.waiting && layout == ProjectionLayout.FULLSCREEN)) mainHandler.post {
                            if (gen == generation && openState) VehicleCustomizations.onProjectionLayoutRequested(layout)
                        }
                    }
                    if (confirmation.confirmed) mainHandler.post {
                        if (callbackGeneration == generation && openState) {
                            logSeq(generation, "fresh fullscreen and channel confirmed serial=${confirmation.serial}")
                            VehicleCustomizations.onProjectionLayoutRequested(ProjectionLayout.FULLSCREEN)
                            runCatching { projectingListener?.invoke(true) }
                        }
                    }
                    if (previous == null || previous != value) {
                        runCatching {
                            diagnosticSink?.invoke(
                                "ClusterBridge callback: code=$code value=$value" +
                                    (previous?.let { " (was $it)" } ?: ""),
                            )
                        }
                        if (code == CALLBACK_CODE_STATE) {
                            // The only trustworthy "the panel is ours now" signal. Until it
                            // arrives incall is still laying the ext-screen out as its preview box
                            // at the right of the panel, so anything drawn there lands in a small
                            // square (measured 2026-10-20: window up at 20:01:52, projection
                            // confirmed at 20:02:39).
                            val nowProjecting = incallReportsProjecting()
                            if (!confirmation.confirmed && (!confirmation.waiting || !nowProjecting)) {
                                runCatching { projectingListener?.invoke(nowProjecting) }
                            }
                        }
                    }
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
            if (observeCallbackRequested && !callbackRegistered) {
                val ok = registerCallback(service ?: return)
                logSeq(generation, "observe: registered INavigateCallback before activation -> $ok")
            }
            if (openState) {
                if (silentOpenOnly) runSilentOpen() else runFullOpenSequence()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "service disconnected: $name")
            forgetRemoteConnection()
            // Android retains this binding and may reconnect it. Do not duplicate bindService.
        }

        override fun onBindingDied(name: ComponentName?) {
            forgetRemoteConnection()
            val ctx = contextRef ?: return
            if (bound) runCatching { ctx.unbindService(this) }
            bound = false
            if (observeCallbackRequested) ensureBound(ctx)
        }

        override fun onNullBinding(name: ComponentName?) {
            forgetRemoteConnection()
            val ctx = contextRef ?: return
            if (bound) runCatching { ctx.unbindService(this) }
            bound = false // A later observation/open may retry; never spin on a null binding.
        }
    }

    private fun forgetRemoteConnection() {
        binder = null
        callbackRegistered = false
        callbackHeard = false
        lastCallbackValue.clear()
        synchronized(confirmationLock) { resumeGeneration = null }
        generation++
        VehicleCustomizations.onProjectionReleased()
    }

    /**
     * Watches the head unit's INavigateCallback without sending a single projection command.
     *
     * Whether the dashboard display exists is not the question: our Presentation is on display 1
     * within about 300 ms of the host starting, in sessions where openProjection is never called at
     * all, so display topology is always "present" and cannot say whether incall is routing the
     * cluster to our ext-screen. That is incall's own state, and this callback is the only channel
     * through which it can report it.
     *
     * Registering here, rather than inside the open sequence, is what makes the next field run a
     * measurement of the one thing that would let the open sequence be skipped when the driver has
     * already brought projection up by hand: does incall report a state before we ask it for one?
     */
    fun observeCallback(context: Context, owner: Any = context) {
        contextRef = context.applicationContext
        if (observationOwner?.get() !== owner) {
            if (openState) projectionNeedsReconfirmation = true
            observationOwner = WeakReference(owner)
        }
        val firstObservation = !observeCallbackRequested
        observeCallbackRequested = true
        if (firstObservation) logSeq(
            generation,
            "observe: persisted state projecting=${persistedProjecting()} " +
                "released=${persistedReleased()} releasedByUs=${releasedByUs()} " +
                "heard=${callbackHeard}",
        )
        val existing = binder
        if (existing?.isBinderAlive == true && !callbackRegistered) {
            val ok = runCatching { registerCallback(existing) }.getOrDefault(false)
            logSeq(generation, "observe: registered INavigateCallback before activation -> $ok")
        } else if (existing?.isBinderAlive != true) {
            logSeq(generation, "observe: binding to watch INavigateCallback before activation")
            ensureBound(context.applicationContext)
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

    /** Adopt a channel already reported by incall without re-sending its activation sequence. */
    fun adoptObservedProjection(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (openState || !reportsProjecting()) return
        contextRef = context.applicationContext
        val ctx = contextRef ?: return
        openState = true
        generation++
        VehicleCustomizations.onProjectionStarted(ctx, true, projectionControl)
        val layout = when (lastCallbackValue[1]) {
            1 -> ProjectionLayout.CARD
            3 -> ProjectionLayout.FULLSCREEN
            else -> null
        }
        if (layout != null) VehicleCustomizations.onProjectionLayoutRequested(layout)
        logSeq(generation, "adopt observed channel; no activation sequence; layout=$layout")
    }

    /**
     * Open the dashboard projection (the full W→E0+G0 sequence from the box).
     * Returns immediately; the handshake continues on the binder thread.
     *
     * The whole sequence is bind-gated. Previously the broadcast half (including the A0 10019
     * projection-mode ladder - the part the head-unit actually acts on) was fired immediately and
     * only the binder half was re-run when the service connected. On a cold boot the incall
     * service is often still starting, so that first ladder went nowhere and the dashboard stayed
     * blank until the driver swiped by hand (the "cold-start auto projection occasionally fails"
     * field report). Now: wait for the connection, then send the full sequence; if the service
     * never connects, fall back to the broadcast-only half so behaviour is never worse than before.
     */
    fun openProjection(context: Context, automatic: Boolean = false) {
        contextRef = context.applicationContext
        val ctx = contextRef ?: return
        val keepConfirmedFull = openState && incallReportsProjecting() && lastCallbackValue[1] == 3
        mainHandler.removeCallbacksAndMessages(token)
        generation++
        // Read incall's own report before we send anything: if the driver has already brought
        // projection up (the cluster shows "等待 CarPlay 连接"), the source is already ours and the
        // open sequence's switch would be a switch to the same source - which is what makes incall
        // announce "全屏导航已开启" for nothing. Our OWN r0 outranks any report: after a hand-back
        // the ext-screen is the head unit's, and a value-3 that arrived inside the r0 answer
        // window is the head unit describing its own screen, not ours.
        val alreadyProjecting = incallReportsProjecting() && !releasedByUs()
        openState = true
        broadcastHalfSent = false
        sourceEnterSent = false
        silentHoldScheduled = false
        silentOpenOnly = alreadyProjecting
        VehicleCustomizations.onProjectionStarted(ctx, automatic, projectionControl)
        ensureBound(ctx)
        if (keepConfirmedFull) {
            logSeq(generation, "open: preserve confirmed fullscreen; no card-mode sequence on reconnect")
            VehicleCustomizations.onProjectionLayoutRequested(ProjectionLayout.FULLSCREEN)
            return
        }
        if (alreadyProjecting) {
            logSeq(generation, "open: incall already reports projecting; silent half only, no source switch")
            runSilentOpen()
            return
        }
        // No report from incall in this process yet. Only the force-kill warm case - we never
        // handed the ext-screen back - may try the silent half first: there the ext-screen is
        // (probably) still ours and a re-assert is all that is needed. After our OWN r0 the
        // ext-screen belongs to the head unit, and the silent half has been measured NOT to take
        // it back (field 2026-10-18: the dashboard stayed on the head unit's own screen until the
        // driver re-enabled the projection channel by hand), so that case must fall through to the
        // full sequence. A proactive report (value 0, like a head unit that dropped the projection
        // while we were dead) outranks the persisted state and lands in the branch above, so only a
        // truly unheard-from process gets here.
        if (!lastCallbackValue.containsKey(CALLBACK_CODE_STATE) && warmStart() && !releasedByUs()) {
            silentOpenOnly = true
            logSeq(
                generation,
                "open: fresh process, persisted state projecting=${persistedProjecting()} " +
                    "released=${persistedReleased()}; silent half first, full sequence in " +
                    "${SILENT_CONFIRM_MS}ms unless incall confirms",
            )
            runSilentOpen()
            armSilentOpenFallback()
            return
        }
        if (binder != null) {
            runFullOpenSequence()
        } else {
            logSeq(generation, "incall service not connected; waiting up to ${SERVICE_WAIT_FALLBACK_MS}ms")
            val gen = generation
            mainHandler.postAtTime({
                if (gen != generation) return@postAtTime
                if (binder != null) return@postAtTime
                logSeq(gen, "incall service still not connected; broadcast half only")
                runBroadcastHalf()
            }, token, SystemClock.uptimeMillis() + SERVICE_WAIT_FALLBACK_MS)
        }
    }

    /**
     * Light "the projection should be up" re-assert: F0 (ext-screen clear 0/2) + C0 + Z, the parts
     * of the open sequence the box itself repeats many times. Used by the auto-project retry ladder
     * as its nudge.
     *
     * It genuinely sends no source-switch signal: not the 10019 projection-source ladder, and not
     * the tx2[1] confirmation. The older version of this function claimed to leave tx2 out while
     * its body still sent it, and the field log caught the consequence - one activation announced
     * three times, ~11 s and ~30 s apart, which is exactly the open sequence plus the two retries
     * that had run before the driver swiped:
     *
     *     20:38:30.779  openProjection()                     -> announcement 1
     *     20:38:41.854  Auto project: 重试 1/3，静默重申        -> announcement 2
     *     20:39:11.883  Auto project: 重试 2/3，静默重申        -> announcement 3
     *
     * A dropped open still has a guaranteed recovery: the driver's swipe runs the full sequence,
     * which keeps tx2 for the case where the source really is not ours yet.
     */
    fun reassertProjection(context: Context) {
        contextRef = context.applicationContext
        if (!openState) {
            // No open sequence is active (e.g. after releaseExternalScreen): nothing to re-assert.
            logSeq(generation, "reassert skipped: projection not open")
            return
        }
        logSeq(generation, "reassert: F0 + C0 + Z (broadcast only, no source switch, no announcement)")
        clearScreenOpen()
        setCarProperties()
        if (binder == null) return
        setMode(1)
        setMode(3)
    }

    /**
     * Hand the instrument-cluster ext-screen back to the head unit: r0 of the box's close path
     * (10116 op/status 2,2 to the four packages, then op/status 0,1 to incall alone) and NOTHING
     * else. Without it a "close" swipe only hides our projection window, and because the dashboard
     * shows that (now invisible) window while incall is in projection mode, the driver got a black
     * dashboard instead of the head unit's own cluster UI. The binder session, the registered
     * callback and both bound stream surfaces stay alive, so the next open takes the ext-screen
     * back with the normal open sequence.
     */
    fun releaseExternalScreen(context: Context) {
        contextRef = context.applicationContext
        VehicleCustomizations.onProjectionReleased()
        logSeq(generation, "releaseExternalScreen: r0 ext-screen hand-back (no binder teardown)")
        // A pending open-sequence post would send F0 and take the ext-screen straight back, so the
        // open chain is invalidated first (generation bump + pending posts dropped).
        openState = false
        generation++
        mainHandler.removeCallbacksAndMessages(token)
        // Our own belief of incall's state is stale the moment we hand the ext-screen back: the
        // next openProjection() in THIS process must not read the old value 3 and take the silent
        // half, which never re-grabs the ext-screen we just gave away. Persist the hand-back too,
        // so a fresh process knows the binder session is still warm. What the persisted state must
        // NOT do is route the next open to the silent half: only a force-kill mid-projection (the
        // ext-screen never left us) is a silent-half case, and after our own r0 the ext-screen is
        // the head unit's, so only the full sequence's source switch takes it back.
        lastCallbackValue[CALLBACK_CODE_STATE] = 0
        r0SentAtUptimeMs = SystemClock.uptimeMillis()
        prefs()?.edit()
            ?.putBoolean(KEY_INCALL_PROJECTING, false)
            ?.putBoolean(KEY_EXT_SCREEN_RELEASED, true)
            ?.putBoolean(KEY_EXT_SCREEN_RELEASED_BY_US, true)
            ?.apply()
        broadcastOperateAll(2, 2)
        broadcastStatusAll(2, 2)
        broadcastOperateTo(PKG_INCALL, 0, 1)
        broadcastStatusTo(PKG_INCALL, 0, 1)
    }

    private fun runFullOpenSequence() {
        // We are taking the ext-screen back from the head unit, so the released belief ends here:
        // from now on a value-3 report is a projection to us again, and the r0 answer window
        // closes with it.
        r0SentAtUptimeMs = 0L
        setReleasedByUs(false)
        runBroadcastHalf()
        runOpenLocked()
    }

    /**
     * The parts of the open sequence that cannot switch incall's projection source: F0 (ext-screen
     * clear), C0(1), C0(3) and the CarProperty write, plus the box's silent hold loop
     * (F0 + C0(3) + Z).
     *
     * Everything that might be the announcement trigger is omitted: the 10019 projection-source
     * ladder (A0), the 9/12/40 ladder (p0) and the tx2[1] source-switch confirmation. That is
     * deliberate and stronger than [reassertProjection], whose own comment claims to leave tx2 out
     * while the code still sends it - so no path here had actually been verified silent. This one is
     * built from the broadcasts alone, which are the parts the box itself repeats many times.
     *
     * Used in two cases: when incall reports it is already projecting (no source to switch), and
     * on a fresh process whose persisted state says the projection is still warm - a force-kill or
     * crash mid-projection, i.e. we never handed the ext-screen back. In the second case the
     * attempt is bounded by [armSilentOpenFallback]; the worst case of a
     * silent half that does not take is a cluster that keeps showing the head unit's own screen,
     * which the fallback (or the driver's swipe, which always sends the full sequence) recovers.
     *
     * Deliberately NOT used after our own r0 hand-back: field-measured (2026-10-18, previous-2
     * 13:26:23) the silent half never re-grabs the ext-screen once r0 gave it away - only the full
     * sequence's source switch takes it back - so a r0'ed session goes straight to
     * [runFullOpenSequence] instead. See [releasedByUs].
     */
    private fun runSilentOpen() {
        broadcastHalfSent = true   // the broadcast ladder half is exactly what is being skipped
        clearScreenOpen()
        setCarProperties()
        setMode(1)
        setMode(3)
        // Called again when the service connects late; the binder half should run then, but the hold
        // loop must not be scheduled twice.
        if (silentHoldScheduled) return
        silentHoldScheduled = true
        for (at in HOLD_RETRY_DELAYS) {
            mainHandler.postAtTime({
                if (!alive()) return@postAtTime
                clearScreenOpen()
                setMode(3)
                setCarProperties()
            }, token, SystemClock.uptimeMillis() + at)
        }
    }

    /**
     * Bounded fallback for the fresh-process silent open: if incall has not confirmed it is
     * projecting within [SILENT_CONFIRM_MS], the silent half did not take - the head unit may have
     * dropped the projection while our process was dead, or the warm session is gone - so the full
     * open sequence runs after all. The announcement that costs is the same one a cold open makes;
     * the alternative, never falling back, risks a dashboard stuck on the head unit's own screen.
     *
     * Only the persisted-state path arms this. When incall itself reported value 3 the silent half
     * is certainly correct and needs no fallback.
     */
    private fun armSilentOpenFallback() {
        val gen = generation
        mainHandler.postAtTime({
            if (gen != generation || !openState) return@postAtTime
            if (incallReportsProjecting()) {
                logSeq(gen, "silent open confirmed by incall (projecting); no full sequence needed")
                return@postAtTime
            }
            logSeq(gen, "silent open not confirmed within ${SILENT_CONFIRM_MS}ms; running the full open sequence")
            // The silent path marked the broadcast half as sent and consumed the source-enter;
            // clear both so the fallback is a complete open, not a binder-only half.
            silentOpenOnly = false
            broadcastHalfSent = false
            sourceEnterSent = false
            runFullOpenSequence()
        }, token, SystemClock.uptimeMillis() + SILENT_CONFIRM_MS)
    }

    private fun runBroadcastHalf() {
        if (broadcastHalfSent) return
        broadcastHalfSent = true
        runOpenBroadcastSide()
    }

    private fun ensureBound(ctx: Context) {
        val app = ctx.applicationContext
        if (bound && binder?.isBinderAlive == true) return
        if (bound && binder == null) return // Binding is pending or Android is reconnecting it.
        if (bound) {
            try {
                app.unbindService(connection)
            } catch (_: IllegalArgumentException) {
            }
            bound = false
            forgetRemoteConnection()
        }
        if (bound) return
        val intent = Intent(SERVICE_ACTION).setComponent(
            ComponentName(PKG_INCALL, SERVICE_CLASS),
        )
        try {
            bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
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
        sendSourceEnter()
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
        sendSourceEnter()
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
        // Forward to the host's diagnostic log. Without this the whole activation sequence is
        // invisible in the exported report: the host only logs that it called openProjection(),
        // never which transacts/broadcasts landed or what the head unit reported back, which is
        // exactly what a field report needs to tell "the head unit ignored us" from "we sent the
        // wrong thing".
        runCatching { diagnosticSink?.invoke("ClusterBridge: $msg") }
    }

    /**
     * Diagnostic sink for the host's exported log. Set once when the CarPlay session starts.
     */
    @Volatile
    var diagnosticSink: ((String) -> Unit)? = null

    /**
     * Called with true when incall reports that the dashboard really is projecting our ext-screen.
     *
     * Everything before that first true is incall's preview layout, so the host keeps its window
     * invisible until then (see the callback handler).
     */
    @Volatile
    var projectingListener: ((Boolean) -> Unit)? = null

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

    private fun setMode(mode: Int): Boolean {
        val b = binder ?: return false
        val slide = txWrite(b, 21, intArrayOf(1, 0))
        val acknowledge = txWrite(b, 8, intArrayOf(mode, 0))
        val finish = txWrite(b, 1, intArrayOf(0))
        val dispatched = slide && acknowledge && finish
        logSeq(generation, "setMode($mode) dispatched=$dispatched")
        return dispatched
    }

    // F0(): open-sequence ext-screen clear — 10116 op=0 mode=2 + 10117 status=0 mode=2 to all
    // 4 packages (InstrumentBridge.F0 = w0(0,2) + y0(0,2)). NOTE: the earlier port wrongly
    // used the close-side r0() values here; the head-unit expects 0/2 during open.
    private fun clearScreenOpen() {
        broadcastOperateAll(0, 2)
        broadcastStatusAll(0, 2)
    }

    /**
     * The "entering projection" state (9), sent at most once per open.
     *
     * Both halves of the open sequence send it - the broadcast half as the head of its p0 ladder
     * and the binder half as its own step - which put two copies of a source-switch signal ~30 ms
     * apart inside one activation. Only the first caller's copy is sent.
     */
    private fun sendSourceEnter() {
        if (sourceEnterSent) return
        sourceEnterSent = true
        broadcastSource(9)
    }

    private fun broadcastSource(state: Int) {
        val ctx = contextRef ?: return
        // S311 uses these same navigation states for its windshield HUD. Keep the
        // projection handshake intact; the HUD output resumes once it has settled.
        com.freddyq.diplay.customization.VehicleCustomizations.onProjectionNavigationState(state)
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

    private var carPropertyReflectionUnavailable = false
    private fun setCarProperties() {
        if (carPropertyReflectionUnavailable) return
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
            if (e is NoSuchMethodException || e is ClassNotFoundException) {
                carPropertyReflectionUnavailable = true
                Log.i(TAG,"Optional CarProperty API unavailable; use existing InCall protocol: ${e.javaClass.simpleName}")
            } else Log.w(TAG, "Optional CarProperty call failed; InCall protocol remains available", e)
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
            if (!ok) return false
            reply.readException()
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
