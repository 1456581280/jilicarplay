// carlito | Generic map display selection and a visual/numeric viewport editor.
package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shilapi.xcertplay.host.R
import kotlin.math.min
import kotlin.math.roundToInt

class VehicleMapProjectionActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))
    private lateinit var inputs: List<EditText>
    private lateinit var preview: MapViewportPreview
    private lateinit var target: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(24)) }
        val scroll = ScrollView(this).apply { addView(content); setBackgroundColor(Color.rgb(12, 17, 27)) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        fun text(resource: Int, size: Float = 16f) = TextView(this).apply { setText(resource); textSize = size; setTextColor(Color.WHITE) }
        fun button(resource: Int, action: () -> Unit) = Button(this).apply { setText(resource); isAllCaps = false; minHeight = dp(56); setOnClickListener { action() } }
        content.addView(text(R.string.vehicle_map_title, 26f))
        content.addView(text(R.string.vehicle_map_setup_hint), params())
        content.addView(Switch(this).apply {
            setText(R.string.vehicle_map_enabled); setTextColor(Color.WHITE); minHeight = dp(56)
            isChecked = VehicleMapSettings.enabled(this@VehicleMapProjectionActivity)
            setOnCheckedChangeListener { _, value -> VehicleMapSettings.setEnabled(this@VehicleMapProjectionActivity, value) }
        }, params())
        target = button(R.string.geely_hud_projection_screen) {
            val displays = GeelyHudProjection.availableDisplays(this)
            if (displays.isEmpty()) {
                AlertDialog.Builder(this).setMessage(R.string.geely_hud_no_projection_display).setPositiveButton(R.string.close, null).show()
            } else AlertDialog.Builder(this).setTitle(R.string.geely_hud_projection_screen)
                .setItems(displays.map { "${it.name} · ${it.width} × ${it.height}" }.toTypedArray()) { _, index ->
                    VehicleMapSettings.select(this, displays[index]); updateTarget(); fillGeometry()
                }.setNegativeButton(R.string.cancel, null).show()
        }
        content.addView(target, params())
        content.addView(Switch(this).apply {
            setText(R.string.vehicle_map_native_mode); setTextColor(Color.WHITE); minHeight = dp(56)
            isChecked = VehicleMapSettings.vehicleMode(this@VehicleMapProjectionActivity)
            setOnCheckedChangeListener { _, value -> VehicleMapSettings.setVehicleMode(this@VehicleMapProjectionActivity, value) }
        }, params())
        content.addView(text(R.string.vehicle_map_native_hint), params())
        content.addView(button(R.string.vehicle_map_permission) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        }, params())
        preview = MapViewportPreview(this) { visible -> inputs[2].setText(visible.toString()) }
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(16) })
        content.addView(text(R.string.vehicle_map_drag_hint), params())
        val values = VehicleMapSettings.geometry(this)
        inputs = listOf(R.string.vehicle_map_canvas_width, R.string.vehicle_map_canvas_height, R.string.vehicle_map_visible_height).mapIndexed { index, resource ->
            content.addView(text(resource), params())
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(Color.WHITE); textSize = 18f
                setSingleLine(); minHeight = dp(56); filters = arrayOf(InputFilter.LengthFilter(4))
                contentDescription = getString(resource); setText(values[index].toString())
                content.addView(this, params())
            }
        }
        savedInstanceState?.getStringArrayList("geometry")?.takeIf { it.size == inputs.size }?.let { values ->
            inputs.forEachIndexed { i, input -> input.setText(values[i]) }
        }
        content.addView(button(R.string.projection_apply_geometry) { applyGeometry(save = false) }, params())
        content.addView(button(R.string.projection_save) { if (applyGeometry(save = true)) finish() }, params())
        content.addView(button(R.string.back) { finish() }, params())
        updateTarget(); applyGeometry(save = false); setContentView(scroll)
    }

    private fun updateTarget() {
        target.text = VehicleMapSettings.screen(this)?.let { "${it.name} · ${it.width} × ${it.height}" }
            ?: getString(R.string.projection_select_display)
    }
    private fun fillGeometry() {
        VehicleMapSettings.geometry(this).forEachIndexed { i, value -> inputs[i].setText(value.toString()) }
        applyGeometry(save = false)
    }
    private fun applyGeometry(save: Boolean): Boolean {
        val screen = VehicleMapSettings.screen(this) ?: return false
        val values = inputs.map { it.text.toString().toIntOrNull() }
        if (values.any { it == null || it != 0 && it !in 64..4096 }) {
            inputs.forEachIndexed { i, input -> if (values[i] == null || values[i] != 0 && values[i] !in 64..4096)
                input.error = getString(R.string.vehicle_map_geometry_invalid) }
            return false
        }
        val actual = values.map { requireNotNull(it) }
        val height = actual[1].takeIf { it > 0 } ?: screen.height
        val visible = actual[2].takeIf { it > 0 } ?: height
        if (visible > height) { inputs[2].error = getString(R.string.vehicle_map_geometry_invalid); return false }
        inputs.forEach { it.error = null }
        preview.canvasWidth = actual[0].takeIf { it > 0 } ?: screen.width
        preview.canvasHeight = height; preview.visibleHeight = visible; preview.invalidate()
        if (save) VehicleMapSettings.saveGeometry(this, actual)
        return true
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("geometry", ArrayList(inputs.map { it.text.toString() }))
        super.onSaveInstanceState(outState)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun params() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
}

private class MapViewportPreview(context: Context, private val changed: (Int) -> Unit) : View(context) {
    var canvasWidth = 1920
    var canvasHeight = 1080
    var visibleHeight = 1080
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var dragging = false
    init { contentDescription = context.getString(R.string.vehicle_map_drag_hint) }
    private fun mapBounds(): RectF {
        val inset = 12 * resources.displayMetrics.density
        val spaceWidth = (width - 2 * inset).coerceAtLeast(1f)
        val spaceHeight = (height - 2 * inset).coerceAtLeast(1f)
        val scale = min(spaceWidth / canvasWidth, spaceHeight / canvasHeight)
        val w = canvasWidth * scale
        val h = canvasHeight * scale
        bounds.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        return bounds
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(31, 42, 58))
        val area = mapBounds()
        paint.color = Color.rgb(12, 17, 27)
        canvas.drawRect(area, paint)
        paint.color = Color.rgb(71, 126, 126)
        val bottom = area.top + area.height() * visibleHeight.toFloat() / canvasHeight
        canvas.drawRect(area.left, area.top, area.right, bottom, paint)
        paint.color = Color.WHITE; paint.textSize = 16 * resources.displayMetrics.scaledDensity
        paint.textSize = min(paint.textSize, area.width() / context.getString(R.string.vehicle_map_viewport).length)
        canvas.drawText(context.getString(R.string.vehicle_map_viewport), area.left + 4f, area.top + paint.textSize * 1.2f, paint)
        paint.strokeWidth = 4 * resources.displayMetrics.density
        canvas.drawLine(area.left, bottom, area.right, bottom, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val area = mapBounds()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            dragging = area.contains(event.x, event.y)
            if (!dragging) return false
        }
        if (!dragging) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            visibleHeight = ((event.y - area.top) / area.height() * canvasHeight).roundToInt().coerceIn(64, canvasHeight)
            changed(visibleHeight); invalidate(); return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            dragging = false; parent?.requestDisallowInterceptTouchEvent(false)
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
