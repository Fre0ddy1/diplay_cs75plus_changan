package com.shilapi.xcertplay.transport

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.network.CarPlayVpnService
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One-shot USB bring-up report for field diagnosis.
 *
 * "Wired CarPlay does not connect" has at least five independent gates before any protocol code
 * runs, and on the Chang'an head unit they fail silently: the device is enumerated, no permission
 * dialog appears, and nothing is written to the log. This report records the state of every gate
 * (permission UI availability, the app's own attach filter, ADB and root fallbacks, and the raw USB
 * descriptors the iPhone exposes) so one export tells us which gate closed.
 *
 * Never runs automatically and never writes to the log on its own: the settings page calls
 * [report] on a background thread. Serial numbers are never read or logged.
 */
object UsbDiagnostics {

    const val PREFIX = "USB-DIAG"

    /**
     * Device filter declared in common/src/main/res/xml/usb_device_filter.xml. An iPhone is NOT in
     * it, so plugging one in does not raise the system's "open with?" grant dialog for DiPlay -
     * that is a normal, deliberate state on this project, but it must be visible in the report
     * because it means the attach path can never grant USB permission by itself. Keep in sync.
     */
    private const val ATTACH_FILTER_SUMMARY = "CH341 0x1a86/0x5512 only (no Apple VID 0x05ac)"

    private const val APPLE_VENDOR_ID = 0x05ac
    private const val MAX_APPLE_DEVICES = 4
    private const val SHELL_TIMEOUT_MILLIS = 1_500L
    private const val SU_TIMEOUT_MILLIS = 4_000L

    fun report(context: Context): String {
        val app = context.applicationContext
        val lines = mutableListOf<String>()
        fun add(line: String) = lines.add("$PREFIX $line")

        add("===== USB 诊断开始 =====")
        add("build: sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} model=${Build.MODEL}")
        add("fingerprint=${Build.FINGERPRINT}")
        add("props: ${properties(listOf("ro.debuggable", "ro.secure", "ro.build.characteristics", "service.adb.tcp.port", "init.svc.adbd", "sys.usb.config", "persist.sys.usb.config", "ro.boot.selinux"))}")

        val usb = app.getSystemService(UsbManager::class.java)
        if (usb == null) {
            add("UsbManager unavailable - no USB host support on this ROM")
            add("===== USB 诊断结束 =====")
            return lines.joinToString("\n")
        }

        // ---- gate 1: the system permission UI (what a normal Android app relies on) ----
        val permissionUi = UsbPermissionActivityProbe.inspectAll(app)
        add("permission-ui: $permissionUi")

        // ---- gate 2: the app's own attach intent filter ----
        add("attach-filter: $ATTACH_FILTER_SUMMARY")

        // ---- gate 3: fallbacks that do not need a dialog ----
        val adb = adbState(app)
        add("adb(127.0.0.1:5555): $adb")
        val adbGrantSupported = IphoneUsbAdbGrant.supported()
        add("adb-grant-route: supported=$adbGrantSupported (needs Android 7.1-9 AND an approved adb key)")
        add("root: ${rootState()}")

        // ---- gate 5: the VPN/NCM tunnel the wired path ends with ----
        // Permission alone does not finish the wired bring-up: the last step establishes a
        // VpnService tunnel for the NCM/Apple Ethernet traffic, and a ROM that deletes
        // com.android.vpndialogs leaves an unapproved app whose establish() always returns null.
        add("vpn: ${CarPlayVpnService.diagnostic(app)}")

        // ---- gate 5: the VPN/NCM tunnel the wired path ends with ----
        // Permission alone does not finish the wired bring-up: the last step establishes a
        // VpnService tunnel for the NCM/Apple Ethernet traffic, and a ROM that deletes
        // com.android.vpndialogs leaves an unapproved app whose establish() always returns null.
        val vpn = CarPlayVpnService.diagnostic(app)
        add("vpn: $vpn")

        // ---- gate 4: what the iPhone actually exposes ----
        val devices = usb.deviceList.values
        add("devices: total=${devices.size} apple=${devices.count { it.vendorId == APPLE_VENDOR_ID }}")
        devices.sortedBy { it.deviceId }.forEach { device -> describeDevice(usb, device, ::add) }

        val appleDevices = devices.filter { it.vendorId == APPLE_VENDOR_ID }
        val granted = appleDevices.count { usb.hasPermission(it) }
        add("summary: appleDevices=${appleDevices.size} permissionGranted=$granted permissionUi=$permissionUi adb=$adb vpn=$vpn")
        add("verdict: ${verdict(appleDevices.size, granted, permissionUi, adb, vpn)}")
        add("===== USB 诊断结束 =====")
        return lines.joinToString("\n")
    }

    private fun verdict(
        appleDevices: Int,
        granted: Int,
        permissionUi: String,
        adb: String,
        vpn: String,
    ): String = when {
        appleDevices == 0 -> "未枚举到 Apple 设备：先确认 iPhone 已插好、车机 USB 口是数据口（不是纯充电口）"
        granted > 0 && vpn.startsWith("vpn approved=false") ->
            "权限已拿到但 VPN 未授权（同意框已被 ROM 删除）：这就是现在卡住的地方。修复=把 com.android.vpndialogs 装回/恢复，或用 root 把本应用设为 VPN 批准方；短期可试「无线连接」"
        granted > 0 && vpn.startsWith("vpn approved=true") ->
            "权限与 VPN 授权都正常：问题在配置切换/协议阶段，看 wired USB configuration 与 usbmux 日志行"
        granted > 0 ->
            "USB 权限已授予，VPN 状态需人工确认（$vpn）"
        permissionUi.startsWith("state=AVAILABLE") && adb.startsWith("READY") ->
            "有系统授权弹窗也有 ADB：优先在车机上点弹窗；弹窗没出现即说明被 ROM 吞掉，改走 ADB 授权"
        permissionUi.startsWith("state=AVAILABLE") ->
            "有系统授权弹窗但 ADB 不可用：弹窗应该出现；没出现就说明该 ROM 的弹窗被拦截（需核对 SystemUI）"
        else ->
            "枚举到 iPhone 但无权限，且系统 USB 授权弹窗不可用（$permissionUi）——需要 root 或 ADB 授权通道"
    }

    private fun describeDevice(usb: UsbManager, device: UsbDevice, add: (String) -> Unit) {
        val apple = device.vendorId == APPLE_VENDOR_ID
        add(
            "device id=${device.deviceId} name=${device.deviceName} " +
                "vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)} " +
                "class=${device.deviceClass}/${device.deviceSubclass}/${device.deviceProtocol} " +
                "usbVersion=${device.version} configs=${device.configurationCount} " +
                "permission=${usb.hasPermission(device)}",
        )
        // Full descriptors only for Apple devices (an iPhone's configuration layout is what decides
        // whether CarPlay can be brought up at all); other devices stay a single line.
        if (!apple) return
        if (device.configurationCount > 8) {
            add("device id=${device.deviceId} 配置数量异常(${device.configurationCount})，跳过详情")
            return
        }
        val configurations = (0 until device.configurationCount).map(device::getConfiguration)
        val carPlay = IphoneCarPlayConfiguration.find(device)
        add(
            "device id=${device.deviceId} carplayConfig=${carPlay?.id ?: "none"} " +
                "carplayMode=${carPlay?.let(IphoneCarPlayConfiguration::isCarPlayMode) == true} " +
                "configs=[${configurations.joinToString(" ") { "${it.id}:${IphoneCarPlayConfiguration.describe(it)}" }}]",
        )
        for (configuration in configurations) {
            add(
                "  config id=${configuration.id} selfPowered=${configuration.isSelfPowered} " +
                    "remoteWakeup=${configuration.isRemoteWakeup} interfaces=${configuration.interfaceCount}",
            )
            for (index in 0 until configuration.interfaceCount) {
                val usbInterface = configuration.getInterface(index)
                add(
                    "    iface id=${usbInterface.id} alt=${usbInterface.alternateSetting} " +
                        "class=${usbInterface.interfaceClass.toString(16)}/" +
                        "${usbInterface.interfaceSubclass.toString(16)}/" +
                        "${usbInterface.interfaceProtocol.toString(16)} " +
                        "endpoints=${usbInterface.endpointCount}",
                )
                for (endpointIndex in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(endpointIndex)
                    add(
                        "      ep addr=0x${endpoint.address.toString(16)} " +
                            "dir=${if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"} " +
                            "type=${endpoint.type} maxPacket=${endpoint.maxPacketSize} interval=${endpoint.interval}",
                    )
                }
            }
        }
    }

    private fun adbState(context: Context): String = try {
        LocalAdb(AdbKeys.load(context)).use { adb ->
            // Never offer a key here: an unapproved key must stay unapproved during diagnosis.
            when (adb.connect(mayAsk = false)) {
                LocalAdb.Access.READY -> "READY"
                LocalAdb.Access.NOT_APPROVED -> "NOT_APPROVED(需要车机确认调试授权)"
                LocalAdb.Access.UNREACHABLE -> "UNREACHABLE(adbd 未开或不是 5555 端口)"
                LocalAdb.Access.UNSUPPORTED -> "UNSUPPORTED"
            }
        }
    } catch (error: Throwable) {
        "ERROR(${error.javaClass.simpleName})"
    }

    /**
     * Root availability. The head unit is rooted; if `su` answers, USB permission can be granted
     * without any dialog, which would be the most reliable route on this ROM. The probe is
     * time-capped: a su that waits for an on-screen prompt is reported as unavailable rather than
     * left hanging.
     */
    private fun rootState(): String {
        val candidates = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/sbin/su",
        )
        val present = candidates.firstOrNull { File(it).exists() }
        if (present == null) return "su binary not found in ${candidates.joinToString(",")}"
        val uid = runShell(listOf("su", "-c", "id"), SU_TIMEOUT_MILLIS)?.let { output ->
            if (output.contains("uid=0")) "uid=0(可用)" else "no-root(${output.trim().take(40)})"
        } ?: "su 无响应（可能有授权弹窗/被拒）"
        return "binary=$present probe=$uid"
    }

    private fun properties(names: List<String>): String {
        val output = runShell(listOf("getprop"), SHELL_TIMEOUT_MILLIS) ?: return "unavailable"
        val wanted = names.toSet()
        return output.lineSequence()
            .mapNotNull { line ->
                val start = line.indexOf('[')
                val separator = line.indexOf("]: [")
                if (start != 0 || separator <= 0) null
                else {
                    val name = line.substring(1, separator)
                    if (name !in wanted) return@mapNotNull null
                    val value = line.substring(separator + 4, line.length - 1)
                    "$name=$value"
                }
            }
            .joinToString(" ")
            .ifEmpty { "unavailable" }
    }

    private fun runShell(command: List<String>, timeoutMillis: Long): String? = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        // Drain on a separate thread: a full pipe buffer would otherwise deadlock waitFor().
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null && output.length < 8_192) {
                        output.append(line).append('\n')
                        line = reader.readLine()
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            reader.join(SHELL_TIMEOUT_MILLIS)
            output.toString()
        } else {
            process.destroy()
            null
        }
    } catch (error: Throwable) {
        null
    }
}
