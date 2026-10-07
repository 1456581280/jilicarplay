// carlito | Self-contained scan metadata; raw property rows remain unchanged for import.
package com.shilapi.xcertplay.vehicleprobe

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object VehicleScanReport {
    fun fileName(time: Long): String =
        "DiPlay-Vehicle-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date(time))}.txt"

    fun format(context: Context, report: String, model: String?, modelSource: String): String {
        if (report.startsWith("DiPlay ") && "vehicle property scan report" in report.lineSequence().first()) return report
        fun safe(value: String?): String = value.orEmpty().replace('\r', ' ').replace('\n', ' ').take(160).ifBlank { "unknown" }
        fun version(packageName: String): String = runCatching {
            context.packageManager.getPackageInfo(packageName, 0).let { info ->
                @Suppress("DEPRECATION")
                "${safe(info.versionName)} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()})"
            }
        }.getOrDefault("unavailable")
        val metrics = context.resources.displayMetrics
        return buildString {
            appendLine("DiPlay ${version(context.packageName)} · vehicle property scan report")
            appendLine("Report type: vehicle_properties")
            appendLine("Scanned at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())}")
            appendLine("Vehicle model: ${model?.let(::safe) ?: "unidentified / 未识别"}")
            appendLine("Vehicle model source: ${if (model == null) "unavailable" else modelSource}")
            appendLine("Android ${safe(Build.VERSION.RELEASE)} / API ${Build.VERSION.SDK_INT}")
            appendLine("Head unit: ${safe(Build.MANUFACTURER)} ${safe(Build.MODEL)}")
            appendLine("Head-unit brand: ${safe(Build.BRAND)}; product: ${safe(Build.PRODUCT)}; device: ${safe(Build.DEVICE)}")
            appendLine("Head-unit board: ${safe(Build.BOARD)}; hardware: ${safe(Build.HARDWARE)}; build: ${safe(Build.DISPLAY)}")
            appendLine("CPU ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}; screen: ${metrics.widthPixels}x${metrics.heightPixels}; density: ${metrics.densityDpi}")
            appendLine("Vehicle bridge: ${version(VehicleBridgeClient.BRIDGE_PACKAGE)}")
            appendLine()
            appendLine("--- Vehicle property scan ---")
            append(report)
        }
    }
}
