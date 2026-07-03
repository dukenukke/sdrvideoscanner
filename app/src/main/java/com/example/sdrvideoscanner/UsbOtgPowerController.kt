package com.example.sdrvideoscanner

import android.util.Log
import java.io.File

data class UsbOtgPowerCycleResult(
    val success: Boolean,
    val message: String,
)

interface UsbOtgPowerController {
    fun powerCycleUsbOtg(powerOffMs: Long, settleMs: Long): UsbOtgPowerCycleResult
}

/**
 * Android does not expose public UsbManager APIs for cutting OTG VBUS.
 * This implementation supports rooted/embedded builds where USB power can be
 * controlled through common Linux sysfs authorization nodes.
 */
class RootSysfsUsbOtgPowerController(
    private val candidatePaths: List<String> = DEFAULT_USB_AUTHORIZATION_PATHS,
) : UsbOtgPowerController {
    override fun powerCycleUsbOtg(powerOffMs: Long, settleMs: Long): UsbOtgPowerCycleResult {
        val diagnostics = StringBuilder()
        for (path in candidatePaths) {
            val file = File(path)
            if (!file.exists()) {
                diagnostics.append("$path: missing\n")
                continue
            }

            val directResult = runCatching {
                file.writeText("0")
                Thread.sleep(powerOffMs)
                file.writeText("1")
                Thread.sleep(settleMs)
            }
            if (directResult.isSuccess) {
                val message = "USB OTG power-cycle via writable sysfs node: $path"
                Log.w(LOG_TAG, message)
                return UsbOtgPowerCycleResult(success = true, message = message)
            }
            diagnostics.append("$path: direct write failed: ${directResult.exceptionOrNull()?.message ?: "unknown"}\n")

            val rootResult = runCatching {
                setAuthorizedViaSu(path, authorized = false)
                Thread.sleep(powerOffMs)
                setAuthorizedViaSu(path, authorized = true)
                Thread.sleep(settleMs)
            }
            if (rootResult.isSuccess) {
                val message = "USB OTG power-cycle via su/sysfs node: $path"
                Log.w(LOG_TAG, message)
                return UsbOtgPowerCycleResult(success = true, message = message)
            }
            diagnostics.append("$path: su write failed: ${rootResult.exceptionOrNull()?.message ?: "unknown"}\n")
        }

        val message = "USB OTG power-cycle unavailable. No candidate sysfs authorization node could be toggled.\n" +
            diagnostics.toString().trimEnd()
        Log.w(LOG_TAG, message)
        return UsbOtgPowerCycleResult(success = false, message = message)
    }

    private fun setAuthorizedViaSu(path: String, authorized: Boolean) {
        require(path in candidatePaths) { "Refusing to write non-candidate USB sysfs path: $path" }
        val value = if (authorized) "1" else "0"
        val process = ProcessBuilder("su", "-c", "printf $value > $path")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("su exited $exitCode: ${output.trim()}")
        }
    }

    private companion object {
        private const val LOG_TAG = "SDRVideoScanner.UsbOtg"
        private val DEFAULT_USB_AUTHORIZATION_PATHS = listOf(
            "/sys/bus/usb/devices/usb1/authorized",
            "/sys/bus/usb/devices/usb2/authorized",
            "/sys/bus/usb/devices/1-0:1.0/authorized",
            "/sys/bus/usb/devices/2-0:1.0/authorized",
        )
    }
}
