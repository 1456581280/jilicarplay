// carlito | Visual layout editing; vehicle controls and OEM implementations stay in GD.
package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.AdapterView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.host.R
import java.util.Locale

/** Edits a local draft. Save applies it; Back leaves the saved layout intact. */
class GeelyProjectionEditorActivity : ComponentActivity() {
    private var draft = GeelyProjectionLayout.defaults()
    private var selected = ProjectionField.NAVIGATION
    private lateinit var preview: GeelyProjectionView
    private lateinit var choices: Spinner
    private lateinit var visible: Switch
    private lateinit var status: TextView
    private lateinit var fields: List<EditText>
    private var filling = false
    private var reader: ProjectionVehicleReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            preview.guidance = GeelyHudProjection.currentGuidance()
            preview.invalidate(); handler.postDelayed(this, 1_000L)
        }
    }
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draft = savedInstanceState?.getString("draft")?.let { runCatching { GeelyProjectionLayout.decode(it) }.getOrNull() }
            ?: GeelyProjectionLayout.load(this)
        selected = savedInstanceState?.getString("selected")?.let { runCatching { ProjectionField.valueOf(it) }.getOrNull() }
            ?: draft.firstOrNull()?.field ?: ProjectionField.NAVIGATION
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        buildPanel()
    }
    override fun onStart() {
        super.onStart()
        reader = ProjectionVehicleReader(this) { frame -> preview.vehicle = frame }
        handler.post(tick)
    }
    override fun onStop() {
        handler.removeCallbacks(tick); reader?.close(); reader = null
        preview.vehicle = ProjectionVehicleFrame()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        readGeometry(showErrors = false)
        outState.putString("draft", GeelyProjectionLayout.encode(draft)); outState.putString("selected", selected.name)
        super.onSaveInstanceState(outState)
    }

    private fun buildPanel() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        val content = column().apply { setPadding(dp(24), dp(20), dp(24), dp(28)) }
        scroll.addView(content)
        val header = row()
        header.addView(label(R.string.projection_editor, 26f), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button(R.string.back) { finish() })
        content.addView(header)
        content.addView(label(R.string.projection_editor_hint, 16f, MUTED), params(12))
        val screen = GeelyHudProjection.availableDisplays(this).firstOrNull {
            it.id == AirPlayPersistence.loadGeelyHudDisplayId(this) || it.name == AirPlayPersistence.loadGeelyHudDisplayName(this)
        } ?: GeelyHudProjection.availableDisplays(this).filter { it.name.contains("hud", true) }.singleOrNull()
        preview = GeelyProjectionView(this).apply {
            editing = true; elements = draft; selected = this@GeelyProjectionEditorActivity.selected
            guidance = GeelyHudProjection.currentGuidance()
            vehicle = GeelyHudProjection.currentVehicleFrame()
            contentDescription = getString(R.string.projection_editor_hint)
            onSelection = { value -> select(value) }
            onGeometryChanged = { draft = elements; fillControls() }
        }
        // Preserve the target aspect, including very wide HUD panels, inside the scrollable editor.
        val aspect = screen?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 3f
        preview.previewAspect = aspect
        val editorWidth = resources.displayMetrics.widthPixels - dp(48)
        val previewHeight = (editorWidth / aspect.coerceIn(0.5f, 16f)).toInt().coerceIn(dp(96), dp(320))
        content.addView(preview, LinearLayout.LayoutParams(-1, previewHeight).apply { topMargin = dp(20) })
        choices = Spinner(this).apply { minimumHeight = dp(56) }
        content.addView(choices, params(16))
        fields = listOf(R.string.projection_x, R.string.projection_y, R.string.projection_width, R.string.projection_height).map { title ->
            val container = column(); container.addView(label(title, 16f))
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                textSize = 18f; setTextColor(TEXT); isSingleLine = true; minHeight = dp(56)
                filters = arrayOf(InputFilter.LengthFilter(8)); contentDescription = getString(title)
            }
            container.addView(input); content.addView(container, params(12)); input
        }
        visible = Switch(this).apply {
            text = getString(R.string.projection_visible); textSize = 18f; setTextColor(TEXT); minHeight = dp(56)
            setOnCheckedChangeListener { _, checked -> if (!filling) {
                draft = draft.map { if (it.field == selected) it.copy(visible = checked) else it }; preview.elements = draft
            } }
        }
        content.addView(visible, params(12))
        status = label(R.string.projection_values_hint, 15f, MUTED)
        content.addView(status, params(12))
        content.addView(button(R.string.projection_apply_geometry) { readGeometry() }, params(12))
        content.addView(button(R.string.projection_add) { addElement() }, params(12))
        content.addView(button(R.string.projection_remove) {
            draft = draft.filterNot { it.field == selected }; rebuildChoices()
        }, params(12))
        content.addView(button(R.string.projection_restore) { draft = GeelyProjectionLayout.defaults(); rebuildChoices() }, params(12))
        content.addView(button(R.string.projection_save, primary = true) {
            if (!readGeometry()) return@button
            runCatching { GeelyProjectionLayout.save(this, draft) }.onSuccess {
                GeelyHudProjection.applyLayout(); finish()
            }.onFailure { status.setText(R.string.projection_save_failed) }
        }, params(20))
        rebuildChoices(); setContentView(scroll)
    }

    private fun rebuildChoices() {
        filling = true
        if (draft.none { it.field == selected }) selected = draft.firstOrNull()?.field ?: ProjectionField.NAVIGATION
        choices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            draft.map { getString(it.field.label) })
        choices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!filling) draft.getOrNull(position)?.let { item ->
                    if (item.field != selected && !select(item.field))
                        choices.setSelection(draft.indexOfFirst { it.field == selected }.coerceAtLeast(0))
                }
            }
        }
        choices.setSelection(draft.indexOfFirst { it.field == selected }.coerceAtLeast(0))
        filling = false; preview.elements = draft; fillControls()
    }
    private fun select(field: ProjectionField): Boolean {
        if (!readGeometry()) return false
        selected = field; choices.setSelection(draft.indexOfFirst { it.field == field }.coerceAtLeast(0)); fillControls()
        return true
    }
    private fun fillControls() {
        filling = true
        val item = draft.firstOrNull { it.field == selected }
        val numbers = item?.let { listOf(it.x, it.y, it.width, it.height) }
        fields.forEachIndexed { index, input ->
            input.isEnabled = item != null; input.error = null
            input.setText(numbers?.get(index)?.let { String.format(Locale.US, "%.2f", it * 100f) }.orEmpty())
        }
        visible.isEnabled = item != null; visible.isChecked = item?.visible == true
        preview.selected = item?.field; filling = false
    }
    private fun readGeometry(showErrors: Boolean = true): Boolean {
        val item = draft.firstOrNull { it.field == selected } ?: return true
        val numbers = fields.map { it.text.toString().trim().replace(',', '.').toFloatOrNull()?.div(100f) }
        if (numbers.any { it == null || !it.isFinite() }) {
            if (showErrors) fields.forEachIndexed { i, input -> if (numbers[i] == null || numbers[i]?.isFinite() != true)
                input.error = getString(R.string.projection_geometry_invalid) }
            return false
        }
        val updated = item.copy(x = numbers[0]!!, y = numbers[1]!!, width = numbers[2]!!, height = numbers[3]!!)
        if (runCatching { updated.validated() }.isFailure) {
            if (showErrors) status.setText(R.string.projection_geometry_invalid)
            return false
        }
        fields.forEach { it.error = null }; status.setText(R.string.projection_values_hint)
        draft = draft.map { if (it.field == selected) updated else it }; preview.elements = draft
        return true
    }
    private fun addElement() {
        if (!readGeometry()) return
        val available = ProjectionField.entries.filter { value -> draft.none { it.field == value } }
        if (available.isEmpty()) return
        AlertDialog.Builder(this).setTitle(R.string.projection_add).setItems(available.map { getString(it.label) }.toTypedArray()) { _, index ->
            val field = available[index]
            draft = draft + ProjectionElement(field, 0.05f, 0.1f, 0.25f, 0.4f)
            selected = field; rebuildChoices()
        }.setNegativeButton(R.string.cancel, null).show()
    }
    private fun label(resource: Int, size: Float, color: Int = TEXT) = TextView(this).apply {
        setText(resource); textSize = size; setTextColor(color)
    }
    private fun button(resource: Int, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        setText(resource); isAllCaps = false; textSize = 17f; minHeight = dp(56)
        setTextColor(if (primary) BG else TEXT)
        backgroundTintList = ColorStateList.valueOf(if (primary) ACCENT else SURFACE)
        setOnClickListener { action() }
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun params(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private val BG = Color.rgb(12, 17, 27)
        private val SURFACE = Color.rgb(21, 30, 44)
        private val ACCENT = Color.rgb(166, 200, 255)
        private val TEXT = Color.rgb(241, 245, 252)
        private val MUTED = Color.rgb(168, 182, 202)
    }
}
