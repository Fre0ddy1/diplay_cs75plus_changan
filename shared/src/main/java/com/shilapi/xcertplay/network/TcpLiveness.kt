package com.shilapi.xcertplay.network

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.net.Socket

/**
 * Detect a vanished peer, without treating a legitimately idle CarPlay screen as a failure.
 *
 * Before API 29 `ParcelFileDescriptor.fromSocket` owns the original descriptor instead of a
 * duplicate, so the old code skipped tuning entirely on Android 9. On those devices (e.g.
 * Changan Feiyu OS) a half-open TCP connection — iPhone walks away or drops Wi-Fi without
 * sending FIN/RST — left the AirPlay read loop blocked forever, the session never ended and
 * the CarPlay UI froze on the last frame. We now duplicate the fd on API < 29 and apply the
 * same kernel keepalive + TCP_USER_TIMEOUT deadline, so a vanished peer is declared dead and
 * the normal reconnect path runs.
 *
 * The deadline must be generous: on Changan the AirPlay control socket can sit idle for a few
 * seconds while the iPhone negotiates the H.264 stream after the TCP handshake (artwork,
 * video-sync, first frame). A 12 s kernel deadline killed the session right as CarPlay was
 * about to appear ("connects but flashes away"). A live peer answers keepalive probes with
 * kernel ACKs, so a legitimately idle CarPlay screen is never killed; the deadline only fires
 * for a genuinely vanished peer.
 */
internal object TcpLiveness {
    // Linux UAPI <linux/tcp.h>; Android's SDK omits these three OsConstants fields.
    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6

    // idle=30s -> probe every 5s -> 3 failed probes, USER_TIMEOUT 50s caps total.
    // Death in ~45-50s only for a vanished peer; live CarPlay traffic (video frames,
    // control messages) keeps the socket busy and the deadline never fires.
    private const val KEEP_IDLE_SECONDS = 30
    private const val KEEP_INTERVAL_SECONDS = 5
    private const val KEEP_COUNT = 3
    private const val USER_TIMEOUT_MILLIS = 50_000

    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
        // Changan regression guard: on API < 29 the kernel USER_TIMEOUT/keepalive deadline
        // killed the session during the pre-stream handshake ("connects but flashes away"),
        // even with the deadline raised to 50 s. The pre-TcpLiveness build connected fine,
        // so on Android 9 we skip peer-health tuning entirely and rely on EOF/transport
        // errors to drive reconnect. The fd-management code below stays for API >= 29.
        if (Build.VERSION.SDK_INT < 29) {
            diagnostic("TCP peer health skipped on API<29 (Changan compatibility)")
            return
        }
        socket.keepAlive = true
        val pfd = try {
            ParcelFileDescriptor.fromSocket(socket)
        } catch (failure: Exception) {
            diagnostic("TCP peer health tuning unavailable: ${failure.javaClass.simpleName}")
            return
        }
        var duplicate: java.io.FileDescriptor? = null
        try {
            val target = if (Build.VERSION.SDK_INT >= 29) {
                pfd.fileDescriptor
            } else {
                // Before 29, fromSocket owns the original descriptor. Duplicate it so the
                // setsockopt work below can never accidentally close the live socket.
                Os.dup(pfd.fileDescriptor).also { duplicate = it }
            }
            Os.setsockoptInt(target, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, KEEP_IDLE_SECONDS)
            Os.setsockoptInt(target, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, KEEP_INTERVAL_SECONDS)
            Os.setsockoptInt(target, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, KEEP_COUNT)
            Os.setsockoptInt(target, OsConstants.IPPROTO_TCP, OsConstants.TCP_USER_TIMEOUT, USER_TIMEOUT_MILLIS)
            diagnostic(
                "TCP peer health enabled idle=${KEEP_IDLE_SECONDS}s interval=${KEEP_INTERVAL_SECONDS}s " +
                    "count=$KEEP_COUNT deadline=${USER_TIMEOUT_MILLIS}ms; no video-idle timeout",
            )
        } catch (failure: Exception) {
            diagnostic("TCP peer health tuning unavailable: ${failure.javaClass.simpleName}")
        } finally {
            runCatching { duplicate?.let { Os.close(it) } }
            if (Build.VERSION.SDK_INT >= 29) {
                runCatching { pfd.close() }
            } else {
                // Release ownership of the original descriptor without closing the live socket.
                runCatching { pfd.detachFd() }
            }
        }
    }
}
