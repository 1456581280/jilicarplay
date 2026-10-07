// carlito | Read-only report-to-profile integration for DiPlay. GPL-3.0.
package com.shilapi.xcertplay.vehicleprobe

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

internal enum class VehicleField(val title: String, vararg val symbols: String) {
    SPEED("车速", "SENSOR_TYPE_CAR_SPEED", "SignalId_VehSpdLgtA", "car_speed"),
    RPM("发动机转速", "SENSOR_TYPE_RPM", "SignalId_EngNSafeEngN"),
    ODOMETER("总里程", "SENSOR_TYPE_ODOMETER"),
    RANGE("续航里程", "SENSOR_TYPE_ENDURANCE_MILEAGE"),
    BATTERY("电池电量", "SENSOR_TYPE_EV_BATTERY_LEVEL"),
    AMBIENT_TEMPERATURE("车外温度", "SENSOR_TYPE_TEMPERATURE_AMBIENT"),
    CABIN_TEMPERATURE("车内温度", "SENSOR_TYPE_TEMPERATURE_INDOOR"),
    HIGH_BEAM("远光灯", "SignalId_ExtrLtgStsHiBeam"),
    LOW_BEAM("近光灯", "SignalId_ExtrLtgStsLoBeam"),
    LEFT_INDICATOR("左转向灯", "SignalId_ExtrLtgStsTurnIndrLe"),
    RIGHT_INDICATOR("右转向灯", "SignalId_ExtrLtgStsTurnIndrRi"),
    STEERING_BUTTON("方向盘按键属性", "SignalId_SteerWhlBtnPsd"),
    GEAR("挡位"),
    GEAR_STAGE("变速箱挡位级别"),
    POSITION_LIGHT("示宽灯"),
    REAR_FOG_LIGHT("后雾灯"),
    DOOR_FRONT_LEFT("左前车门"),
    DOOR_FRONT_RIGHT("右前车门"),
    DOOR_REAR_LEFT("左后车门"),
    DOOR_REAR_RIGHT("右后车门"),
    DOOR_LOCK("车门锁"),
    WINDOW_FRONT_LEFT("左前车窗"),
    WINDOW_FRONT_RIGHT("右前车窗"),
    WINDOW_REAR_LEFT("左后车窗"),
    WINDOW_REAR_RIGHT("右后车窗"),
    MIRROR_FOLDED("后视镜折叠"),
    TEMPERATURE_STATUS("空调温度状态"),
    TIRE_FRONT_LEFT("左前胎压"),
    TIRE_FRONT_RIGHT("右前胎压"),
    TIRE_REAR_LEFT("左后胎压"),
    TIRE_REAR_RIGHT("右后胎压"),
    RADAR_FRONT_LEFT("左前雷达"),
    RADAR_FRONT_RIGHT("右前雷达"),
    RADAR_REAR_LEFT_OUTER("左后外侧雷达"),
    RADAR_REAR_LEFT_INNER("左后内侧雷达"),
    RADAR_REAR_RIGHT_INNER("右后内侧雷达"),
    RADAR_REAR_RIGHT_OUTER("右后外侧雷达"),
    ;

    val category: String get() = when {
        this == STEERING_BUTTON -> "方向盘按键"
        name.startsWith("TIRE_") -> "胎压"
        name.startsWith("RADAR_") -> "泊车雷达"
        name.startsWith("DOOR_") || name.startsWith("WINDOW_") || this == MIRROR_FOLDED -> "车门与车窗"
        this in setOf(HIGH_BEAM, LOW_BEAM, LEFT_INDICATOR, RIGHT_INDICATOR, POSITION_LIGHT, REAR_FOG_LIGHT) -> "灯光"
        this in setOf(AMBIENT_TEMPERATURE, CABIN_TEMPERATURE, TEMPERATURE_STATUS) -> "温度"
        else -> "行驶数据"
    }
}

/** An address and its read channel, never a replay of the report's stale reading. */
internal data class VehiclePropertyBinding(
    val field: VehicleField,
    val chain: String,
    val kind: String,
    val area: String,
    val propertyId: Int,
    val name: String,
    val origin: String,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val unit: String = "原始值",
    val encoding: String = "LINEAR",
) {
    val hexId: String get() = "0x${propertyId.toUInt().toString(16).uppercase()}"
}

internal data class VehiclePropertyProfile(val model: String, val bindings: List<VehiclePropertyBinding>)

internal object ReportPropertyImporter {
    const val MAX_BYTES = 8 * 1024 * 1024

    /** Only exact published symbols can fill fields; checksum/counter/lookalike names cannot. */
    fun extract(report: String): List<VehiclePropertyBinding> {
        require(report.contains("schema=geely_property_probe_v2")) { "不是支持的车辆扫描报告" }
        val result = linkedMapOf<VehicleField, VehiclePropertyBinding>()
        report.lineSequence().filter { it.startsWith('"') }.forEach { line ->
            val row = csvFields(line)
            if (row.size != 14 || row[8] != "READ_OK") return@forEach
            val chain = row[0]
            val kind = row[1]
            if (chain !in setOf("adapt_api", "ecarx_service", "direct_binder") ||
                kind !in setOf("sensor", "signal", "property")) return@forEach
            val raw = row[7].toDoubleOrNull() ?: return@forEach
            if (!raw.isFinite() || (raw != 0.0 && abs(raw) < 1e-20)) return@forEach
            val symbol = row[3].substringAfterLast('.')
            val field = VehicleField.entries.firstOrNull { symbol in it.symbols } ?: return@forEach
            // Readability of this sentinel is never evidence that the property is usable.
            if (field in setOf(VehicleField.HIGH_BEAM, VehicleField.LOW_BEAM,
                    VehicleField.LEFT_INDICATOR, VehicleField.RIGHT_INDICATOR, VehicleField.STEERING_BUTTON) && raw == 255.0) return@forEach
            val id = row[4].toIntOrNull()?.takeIf { it != 0 } ?: return@forEach
            val hex = runCatching { java.lang.Long.decode(row[5]).toInt() }.getOrNull()
            if (hex != id || row[2] !in setOf("global", "auto", "auto(1,0)") && row[2].toIntOrNull() == null) return@forEach
            val binding = VehiclePropertyBinding(field, chain, kind, row[2], id, row[3], row[12])
            val existing = result[field]
            if (existing == null || field.symbols.indexOf(symbol) < field.symbols.indexOf(existing.name.substringAfterLast('.'))) {
                result[field] = binding
            }
        }
        return VehicleField.entries.mapNotNull(result::get)
    }
}

/** Atomic files are read afresh because the probe and CarPlay use different processes. */
internal object VehiclePropertyProfiles {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "vehicle-property-profile.json"))

    fun load(context: Context): VehiclePropertyProfile? = runCatching {
        decode(file(context).openRead().bufferedReader().use { it.readText() })
    }.getOrNull()

    fun decode(text: String): VehiclePropertyProfile {
        val json = JSONObject(text)
        require(json.getInt("schema") == 1)
        val rows = json.getJSONArray("bindings")
        val bindings = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            VehiclePropertyBinding(VehicleField.valueOf(row.getString("field")), row.getString("chain"),
                row.getString("kind"), row.getString("area"), row.getInt("propertyId"),
                row.getString("name"), row.getString("origin"), row.getDouble("scale"),
                row.getDouble("offset"), row.getString("unit"),
                row.optString("encoding", "LINEAR"))
        }
        return VehiclePropertyProfile(json.getString("model"), bindings).also(::validate)
    }

    fun encode(profile: VehiclePropertyProfile): String {
        validate(profile)
        val rows = JSONArray()
        profile.bindings.forEach { value -> rows.put(JSONObject().apply {
            put("field", value.field.name); put("chain", value.chain); put("kind", value.kind)
            put("area", value.area); put("propertyId", value.propertyId); put("name", value.name)
            put("origin", value.origin); put("scale", value.scale); put("offset", value.offset); put("unit", value.unit)
            put("encoding", value.encoding)
        }) }
        val json = JSONObject().put("schema", 1).put("author", "carlito")
            .put("model", profile.model.trim()).put("bindings", rows)
        return json.toString()
    }

    fun save(context: Context, profile: VehiclePropertyProfile) {
        val atomic = file(context)
        val output = atomic.startWrite()
        try { output.write(encode(profile).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }

    private fun validate(profile: VehiclePropertyProfile) {
        require(profile.model.trim().length in 1..80) { "请填写车型名称" }
        require(profile.bindings.isNotEmpty() && profile.bindings.size <= VehicleField.entries.size)
        require(profile.bindings.map { it.field }.distinct().size == profile.bindings.size)
        require(profile.bindings.all { it.scale.isFinite() && it.scale > 0 && it.offset.isFinite() && it.unit.length <= 16 })
    }
}
