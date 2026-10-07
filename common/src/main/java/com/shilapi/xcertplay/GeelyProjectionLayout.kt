// carlito | Editable, read-only secondary-display composition.
package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.CarPlayHudGuidance
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

internal enum class ProjectionField(val label: Int, val property: String? = null) {
    NAVIGATION(R.string.projection_navigation), SPEED(R.string.projection_speed, "SPEED"),
    RPM(R.string.projection_rpm, "RPM"), LEFT_INDICATOR(R.string.projection_left_indicator, "LEFT_INDICATOR"),
    RIGHT_INDICATOR(R.string.projection_right_indicator, "RIGHT_INDICATOR"),
    HIGH_BEAM(R.string.projection_high_beam, "HIGH_BEAM"), LOW_BEAM(R.string.projection_low_beam, "LOW_BEAM"),
    CLOCK(R.string.projection_clock),
}

internal data class ProjectionElement(
    val field: ProjectionField, val x: Float, val y: Float, val width: Float, val height: Float,
    val visible: Boolean = true,
) {
    fun validated(): ProjectionElement = apply {
        require(listOf(x, y, width, height).all(Float::isFinite))
        require(x >= 0f && y >= 0f && width in 0.08f..1f && height in 0.08f..1f)
        require(x + width <= 1.00001f && y + height <= 1.00001f)
    }
}

internal object GeelyProjectionLayout {
    fun defaults() = listOf(ProjectionElement(ProjectionField.NAVIGATION, 0f, 0f, 1f, 1f))
    private fun preferences(context: Context) = context.getSharedPreferences("geely_projection_layout", Context.MODE_PRIVATE)
    fun load(context: Context): List<ProjectionElement> = preferences(context).getString("layout", null)
        ?.let { runCatching { decode(it) }.getOrNull() } ?: defaults()
    fun save(context: Context, elements: List<ProjectionElement>) {
        check(preferences(context).edit().putString("layout", encode(elements)).commit())
    }
    fun encode(elements: List<ProjectionElement>): String {
        require(elements.size <= ProjectionField.entries.size && elements.map { it.field }.distinct().size == elements.size)
        val array = JSONArray()
        elements.forEach { item -> item.validated(); array.put(JSONObject().apply {
            put("field", item.field.name); put("x", item.x); put("y", item.y)
            put("width", item.width); put("height", item.height); put("visible", item.visible)
        }) }
        return JSONObject().put("schema", 1).put("author", "carlito").put("elements", array).toString()
    }
    fun decode(text: String): List<ProjectionElement> {
        require(text.length <= 16_384)
        val json = JSONObject(text); require(json.getInt("schema") == 1)
        val array = json.getJSONArray("elements"); require(array.length() <= ProjectionField.entries.size)
        return (0 until array.length()).map { i -> array.getJSONObject(i).let { row ->
            ProjectionElement(ProjectionField.valueOf(row.getString("field")), row.getDouble("x").toFloat(),
                row.getDouble("y").toFloat(), row.getDouble("width").toFloat(), row.getDouble("height").toFloat(),
                row.getBoolean("visible")).validated()
        } }.also { require(it.map { value -> value.field }.distinct().size == it.size) }
    }
}

internal data class ProjectionVehicleValue(val number: Double, val unit: String) {
    fun metersPerSecond(): Double? {
        val speed = when (unit.lowercase(Locale.ROOT).replace(" ", "")) {
            "km/h", "kph", "kmh" -> number / 3.6
            "m/s" -> number
            else -> return null
        }
        return speed.takeIf { it.isFinite() && it in 0.0..(300.0 / 3.6) }
    }
}
internal data class ProjectionVehicleFrame(
    val sampledAt: Long = 0L, val values: Map<String, ProjectionVehicleValue> = emptyMap(),
) {
    fun value(field: ProjectionField): ProjectionVehicleValue? = field.property?.let(::value)
    fun value(name: String): ProjectionVehicleValue? = values[name]
        ?.takeIf { SystemClock.elapsedRealtime() - sampledAt in 0L..3_000L }
}

/** Reuses the existing navigation renderer; coordinates remain proportional across screens. */
internal class GeelyProjectionView(context: Context) : View(context) {
    var elements = GeelyProjectionLayout.defaults()
        set(value) { field = value; invalidate() }
    var guidance: CarPlayHudGuidance? = null
        set(value) { field = value; invalidate() }
    var vehicle = ProjectionVehicleFrame()
        set(value) { field = value; invalidate() }
    var scalePercent = 100
        set(value) { field = value.coerceIn(50, 100); invalidate() }
    var editing = false
    var previewAspect: Float? = null
    var selected: ProjectionField? = null
        set(value) { field = value; invalidate() }
    var onSelection: ((ProjectionField) -> Boolean)? = null
    var onGeometryChanged: (() -> Unit)? = null
    private val navigation = GeelyHudGuidanceView(context, 100)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 2f
    }
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var drag: ProjectionField? = null
    private var lastX = 0f
    private var lastY = 0f

    private fun contentArea(): RectF {
        val aspect = previewAspect?.takeIf { it.isFinite() && it > 0f } ?: return RectF(0f, 0f, width.toFloat(), height.toFloat())
        val w = min(width.toFloat(), height * aspect)
        val h = w / aspect
        return RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }
    private fun bounds(item: ProjectionElement): RectF {
        val area = contentArea()
        return RectF(area.left + item.x * area.width(), area.top + item.y * area.height(),
            area.left + (item.x + item.width) * area.width(), area.top + (item.y + item.height) * area.height())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (editing) canvas.drawColor(Color.BLACK)
        elements.filter { it.visible }.forEach { item ->
            val rect = bounds(item)
            if (rect.width() < 1f || rect.height() < 1f) return@forEach
            val checkpoint = canvas.save()
            canvas.clipRect(rect)
            if (editing) {
                outline.color = if (selected == item.field) Color.rgb(166, 200, 255) else Color.rgb(80, 96, 118)
                canvas.drawRect(rect.left + 2f, rect.top + 2f, rect.right - 2f, rect.bottom - 2f, outline)
            }
            val scale = scalePercent / 100f
            canvas.scale(scale, scale, rect.centerX(), rect.centerY())
            if (item.field == ProjectionField.NAVIGATION) {
                if (guidance != null) {
                    navigation.guidance = guidance
                    navigation.layout(0, 0, rect.width().toInt(), rect.height().toInt())
                    canvas.translate(rect.left, rect.top); navigation.draw(canvas)
                } else if (editing) drawText(canvas, rect, context.getString(item.field.label), "—")
            } else {
                val value = if (item.field == ProjectionField.CLOCK) clock.format(Date()) else formatted(item.field)
                drawText(canvas, rect, context.getString(item.field.label), value)
            }
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun drawText(canvas: Canvas, rect: RectF, title: String, value: String) {
        text.color = Color.rgb(168, 182, 202); text.textSize = min(rect.height() * 0.18f, rect.width() * 0.10f)
        canvas.drawText(TextUtils.ellipsize(title, text, rect.width() * 0.94f, TextUtils.TruncateAt.END).toString(),
            rect.centerX(), rect.top + rect.height() * 0.28f, text)
        text.color = Color.WHITE; text.textSize = min(rect.height() * 0.38f, rect.width() * 0.22f)
        canvas.drawText(TextUtils.ellipsize(value, text, rect.width() * 0.94f, TextUtils.TruncateAt.END).toString(),
            rect.centerX(), rect.top + rect.height() * 0.76f, text)
    }

    private fun formatted(field: ProjectionField): String {
        val value = vehicle.value(field) ?: return "—"
        val unit = value.unit.lowercase(Locale.ROOT).replace(" ", "")
        return when (field) {
            ProjectionField.SPEED -> {
                val kph = value.metersPerSecond()?.times(3.6) ?: return "—"
                String.format(Locale.getDefault(), "%.0f km/h", kph)
            }
            ProjectionField.RPM -> if (unit in setOf("rpm", "r/min", "转/分") && value.number in 0.0..15_000.0)
                String.format(Locale.getDefault(), "%.0f rpm", value.number) else "—"
            else -> when (value.number) {
                0.0 -> context.getString(R.string.projection_off)
                1.0 -> context.getString(R.string.projection_on)
                else -> "—"
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editing || width <= 0 || height <= 0) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val item = elements.asReversed().firstOrNull { it.visible && bounds(it).contains(event.x, event.y) } ?: return false
                if (onSelection?.invoke(item.field) == false) return false
                drag = item.field; selected = item.field
                lastX = event.x; lastY = event.y; parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val target = drag ?: return false
                val area = contentArea()
                val dx = (event.x - lastX) / area.width().coerceAtLeast(1f)
                val dy = (event.y - lastY) / area.height().coerceAtLeast(1f)
                elements = elements.map { if (it.field != target) it else it.copy(
                    x = (it.x + dx).coerceIn(0f, 1f - it.width), y = (it.y + dy).coerceIn(0f, 1f - it.height)) }
                lastX = event.x; lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = null; parent?.requestDisallowInterceptTouchEvent(false); onGeometryChanged?.invoke()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
