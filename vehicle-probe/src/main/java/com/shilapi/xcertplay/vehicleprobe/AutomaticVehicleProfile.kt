// carlito | Scan-to-profile automation. Only verified read-only bindings are applied.
package com.shilapi.xcertplay.vehicleprobe

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

internal data class ProfileApplication(
    val scanTime: Long,
    val applied: Boolean = false,
    val configured: Int = 0,
    val readable: Int = 0,
    val message: String,
)

internal object AutomaticVehicleProfile {
    fun apply(context: Context, client: VehicleBridgeClient, report: String, time: Long,
              progress: (String) -> Unit): ProfileApplication {
        progress("正在分析属性")
        val existing = client.activeProfile()
        val extracted = ReportPropertyImporter.extract(report)
        // Preset tables stay in the closed bridge. Only addresses present in this scan qualify.
        val addresses = report.lineSequence().filter { it.startsWith('"') }.map(::csvFields)
            .filter { it.size == 14 && it[8] == "READ_OK" }
            .mapNotNull { row -> row[4].toIntOrNull()?.let { listOf(row[0], row[1], row[2], it) } }.toSet()
        val presets = client.presets()
        val detectedModel = presets.filter { preset ->
            val codes = Regex("[A-Za-z]+[0-9]+", RegexOption.IGNORE_CASE).findAll(preset.model)
                .map { it.value }.toList()
            codes.any { Regex("(?<![A-Za-z0-9])${Regex.escape(it)}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
                .containsMatchIn(Build.MODEL) }
        }.singleOrNull()
        progress("正在提取可用属性")
        val recognized = (detectedModel?.let(::listOf) ?: presets).flatMap { it.bindings }
            .filter { binding -> addresses.any { address ->
                address[3] == binding.propertyId && (address[2] == binding.area ||
                    binding.area == "0" && address[2] in setOf("global", "auto", "auto(1,0)")) &&
                    (address[0] == binding.chain && address[1] == binding.kind ||
                        binding.chain == "gd_vehicle_bridge" && address[0] in setOf("ecarx_service", "direct_binder", "vhal") &&
                        address[1] in setOf("property", "signal"))
            } }
            .groupBy { it.field }.mapNotNull { (_, matches) ->
                // Ambiguous model interpretations are never guessed from a stationary sample.
                matches.distinctBy { listOf(it.chain, it.kind, it.area, it.propertyId,
                    it.scale, it.offset, it.unit, it.encoding) }.singleOrNull()
            }
        val combined = (existing?.bindings.orEmpty() + recognized + extracted).distinctBy { it.field }
        if (combined.isEmpty()) return ProfileApplication(time,
            message = "报告已保存，暂未识别到可应用属性。可在车型属性配置中选择车型预设。")
        val candidate = VehiclePropertyProfile(existing?.model ?: detectedModel?.model
            ?: Build.MODEL.trim().take(80).ifBlank { "当前车型" }, combined)
        progress("正在核对当前读数")
        val values = client.readProfile(candidate)
        val previous = existing?.bindings.orEmpty().map { it.field }.toSet()
        val accepted = combined.filter { binding -> binding.field in previous ||
            values[binding.field]?.let { value ->
                value != 255.0 || binding.field !in setOf(VehicleField.HIGH_BEAM, VehicleField.LOW_BEAM,
                    VehicleField.LEFT_INDICATOR, VehicleField.RIGHT_INDICATOR, VehicleField.STEERING_BUTTON)
            } == true }
        if (accepted.isEmpty()) return ProfileApplication(time,
            message = "报告已保存，已识别属性暂时无法读取，未应用新配置。请检查车辆访问授权后重试。")
        val profile = candidate.copy(bindings = accepted)
        progress("正在应用属性")
        client.saveProfile(profile)
        progress("正在保存配置")
        val localSaved = runCatching { VehiclePropertyProfiles.save(context, profile) }.isSuccess
        val readable = accepted.count { values[it.field] != null }
        return ProfileApplication(time, true, accepted.size, readable,
            if (localSaved) "已应用并保存 ${accepted.size} 项属性。可在“查看车辆数据”查看，在“投屏编辑器”添加显示项目。"
            else "已应用 ${accepted.size} 项属性，车辆数据桥已保存配置。应用内副本保存失败，请重试。")
    }

    private fun file(context: Context) = AtomicFile(File(context.filesDir, "vehicle-profile-application.json"))
    fun load(context: Context, time: Long): ProfileApplication? = runCatching {
        val json = JSONObject(file(context).openRead().bufferedReader().use { it.readText() })
        require(json.getLong("scanTime") == time)
        ProfileApplication(time, json.getBoolean("applied"), json.getInt("configured"),
            json.getInt("readable"), json.getString("message"))
    }.getOrNull()
    fun save(context: Context, result: ProfileApplication) {
        val atomic = file(context)
        val output = atomic.startWrite()
        try {
            val json = JSONObject().put("scanTime", result.scanTime).put("applied", result.applied)
                .put("configured", result.configured).put("readable", result.readable).put("message", result.message)
            output.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output)
        } catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
}
