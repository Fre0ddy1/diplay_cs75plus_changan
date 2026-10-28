package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.Settings
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger

/** Uses the car's saved hotspot configuration. This never stops or reconfigures the hotspot. */
object CarHotspotTethering {
    enum class Result(val diagnostic: String) {
        READY("Car hotspot is on"),
        PERMISSION_REQUIRED("Hotspot control permission is missing"),
        UNSUPPORTED("Automatic hotspot control interface is unavailable"),
        FAILED("The car could not start its hotspot"),
        TIMED_OUT("Timed out waiting for the car hotspot"),
        CANCELLED("Hotspot startup was cancelled"),
    }

    fun permitted(context: Context): Boolean = Settings.System.canWrite(context)

    /** Blocking; serialize startup and connection requests, checking cancellation after acquiring the lock. */
    fun enable(context: Context, isCancelled: () -> Boolean, log: (String) -> Unit): Result =
        enable(15_000L, isCancelled, { permitted(context) }, { CarHotspotStatus.isEnabled(context) }, log) { receiver ->
            val platform = com.freddyq.diplay.customization.VehicleCustomizations.platformHotspotStarter(context)
            if (platform != null) {
                log("car hotspot control strategy=platform-wrapper")
                platform.start(receiver)
                log("car hotspot control request dispatched; awaiting actual AP ON")
                return@enable
            }
            log("car hotspot control stage=read-connectivity-service")
            val service = ConnectivityManager::class.java.getDeclaredField("mService")
                .apply { isAccessible = true }
                .get(context.getSystemService(ConnectivityManager::class.java))
                ?: throw NoSuchMethodException("Connectivity service unavailable")
            log("car hotspot control stage=public-interface-dispatch implementation=${service.javaClass.name}")
            AndroidHotspotStarter.start(service, receiver, context.packageName)
            log("car hotspot control request dispatched; awaiting actual AP ON")
        }.also { log("car hotspot auto-enable: ${it.diagnostic}") }

    internal fun enable(
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        start: (ResultReceiver) -> Unit,
    ): Result = enable(timeoutMillis, isCancelled, canWrite, isEnabled, {}, start)

    @Synchronized
    internal fun enable(
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
        canWrite: () -> Boolean,
        isEnabled: () -> Boolean?,
        log: (String) -> Unit,
        start: (ResultReceiver) -> Unit,
    ): Result {
        if (isCancelled()) return Result.CANCELLED
        if (isEnabled() == true) return Result.READY
        if (!canWrite()) { log("car hotspot control permission WRITE_SETTINGS unavailable"); return Result.PERMISSION_REQUIRED }
        if (isEnabled() == null) { log("car hotspot control AP state unreadable"); return Result.UNSUPPORTED }
        val response = AtomicInteger(-1)
        try {
            if (isCancelled()) return Result.CANCELLED
            start(object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    log("car hotspot control platform resultCode=$resultCode")
                    response.set(resultCode)
                }
            })
        } catch (error: InvocationTargetException) {
            val cause = error.targetException
            val permissions = Regex("android\\.permission\\.[A-Z_]+").findAll(cause.message.orEmpty())
                .map { it.value }.distinct().take(5).toList()
            log("car hotspot control remote error=${cause.javaClass.simpleName} permissions=$permissions")
            return if (error.targetException is SecurityException) Result.PERMISSION_REQUIRED else Result.FAILED
        } catch (error: IllegalAccessException) {
            log("car hotspot control Java access error=${error.javaClass.simpleName}")
            return Result.FAILED
        } catch (error: ReflectiveOperationException) {
            log("car hotspot control lookup error=${error.javaClass.simpleName}")
            return Result.UNSUPPORTED
        } catch (error: SecurityException) {
            log("car hotspot control access denied error=${error.javaClass.simpleName}")
            return Result.PERMISSION_REQUIRED
        } catch (error: RuntimeException) {
            log("car hotspot control request error=${error.javaClass.simpleName}")
            return Result.FAILED
        }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (isCancelled()) return Result.CANCELLED
            if (isEnabled() == true) return Result.READY
            if (response.get() > 0) return Result.FAILED
            val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMillis <= 0) { log("car hotspot control timeout: AP did not report ON"); return Result.TIMED_OUT }
            try {
                Thread.sleep(minOf(250L, remainingMillis))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result.CANCELLED
            }
        }
    }
}
