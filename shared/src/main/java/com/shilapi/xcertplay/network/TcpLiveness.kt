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
 * same kernel keepalive + TCP_USER_TIMEOUT deadline, so a vanished peer is declared dead in
 * about 12 seconds and the normal reconnect path runs. A live peer answers the keepalive
 * probes with kernel ACKs, so a legitimately idle CarPlay screen is never killed.
 */
internal object TcpLiveness {
    // Linux UAPI <linux/tcp.h>; Android's SDK omits these three OsConstants fields.
    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6

    // idle=8s -> probe every 2s -> 2 failed probes + timeout 12s -> death in ~12s.
    private const val KEEP_IDLE_SECONDS = 8
    private const val KEEP_INTERVAL_SECONDS = 2
    private const val KEEP_COUNT = 2
    private const val USER_TIMEOUT_MILLIS = 12_000

    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
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
