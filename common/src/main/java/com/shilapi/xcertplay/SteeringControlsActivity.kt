package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.host.R
import org.json.JSONObject

/** One panel: identify a physical key, fill its mapping, apply, and return the named profile to cloud. */
class SteeringControlsActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var draft = SteeringProfile.draft()
    private lateinit var carModel: EditText
    private lateinit var headUnit: EditText
    private lateinit var instruction: TextView
    private lateinit var cloudState: TextView
    private lateinit var saveButton: Button
    private lateinit var accessButton: Button
    private lateinit var restoreButton: Button
    private var requestingAccess = false
    private var diagnosticText: TextView? = null
    // carlito: Privileged hotspot diagnostics belong to the unlocked developer page.
    private var hotspotJoinControls: HotspotJoinControls? = null
    private val bindingLabels = mutableMapOf<String, TextView>()
    private val identifyButtons = mutableListOf<Button>()
    private var learningOperation: String? = null
    private lateinit var cancelButton: Button
    private var exportProfile: SteeringProfile? = null
    private val developer get() = intent.getBooleanExtra("developer", false) && SteeringProfiles.developerUnlocked(this)
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val profile = exportProfile ?: SteeringProfiles.load(this)
        exportProfile = null
        if (uri != null) {
            runCatching {
                requireNotNull(profile)
                contentResolver.openOutputStream(uri)?.use { it.write(profile.json().toString(2).toByteArray(Charsets.UTF_8)) }
                    ?: error("No export stream")
            }.onSuccess { toast(R.string.steering_exported) }.onFailure { toast(R.string.steering_export_failed) }
        }
    }
    private val refresh = object : Runnable {
        override fun run() {
            updateCloudState()
            updateAccess()
            diagnosticText?.text = CarPlayMediaKeys.steeringDiagnostics()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draft = savedInstanceState?.getString("draft")?.let {
            runCatching { SteeringProfile.fromJson(JSONObject(it), validate = false) }.getOrNull()
        } ?: SteeringProfiles.load(this) ?: SteeringProfile.draft()
        exportProfile = savedInstanceState?.getString("exportProfile")?.let {
            runCatching { SteeringProfile.fromJson(JSONObject(it)) }.getOrNull()
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        buildPanel()
        SteeringProfiles.scheduleUpload(this)
    }

    override fun onResume() { super.onResume(); updateAccess(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onStop() {
        cancelIdentification()
        super.onStop()
    }
    // carlito: Cancel developer repair callbacks with this activity's lifetime.
    override fun onDestroy() {
        hotspotJoinControls?.close()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", currentDraft().json().toString())
        exportProfile?.let { outState.putString("exportProfile", it.json().toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun buildPanel() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        val content = column().apply { setPadding(dp(28), dp(20), dp(28), dp(28)) }
        scroll.addView(content)
        val header = row()
        header.addView(label(getString(R.string.steering_identification), 26f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button(getString(R.string.back)) { finish() }, LinearLayout.LayoutParams(-2, -2))
        content.addView(header)
        content.addView(label(getString(R.string.steering_panel_intro), 16f, MUTED), params(12))

        val metadata = LinearLayout(this).apply {
            orientation = if (resources.configuration.screenWidthDp >= 650) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val carField = column()
        carField.addView(label(getString(R.string.steering_car_model), 16f))
        carModel = input(draft.carModel, getString(R.string.steering_car_model_hint))
        carField.addView(carModel, params(6))
        val unitField = column()
        unitField.addView(label(getString(R.string.steering_head_unit), 16f))
        headUnit = input(draft.headUnitModel, getString(R.string.steering_head_unit))
        unitField.addView(headUnit, params(6))
        if (metadata.orientation == LinearLayout.HORIZONTAL) {
            metadata.addView(carField, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
            metadata.addView(unitField, LinearLayout.LayoutParams(0, -2, 1f))
        } else {
            metadata.addView(carField, params())
            metadata.addView(unitField, params(12))
        }
        content.addView(metadata, params(20))
        instruction = label(getString(if (SteeringProfiles.enabled(this)) R.string.steering_choose_button else R.string.steering_default_restored), 17f, ACCENT)
        content.addView(instruction, params(20))
        accessButton = button(getString(R.string.steering_allow_access)) { requestAccess() }
        content.addView(accessButton, params(10))

        for (operation in SteeringBinding.operations) {
            val line = row().apply { setPadding(0, dp(10), 0, dp(10)) }
            val names = column()
            names.addView(label(operationName(operation), 18f, bold = true))
            val state = label("", 14f, MUTED).also { bindingLabels[operation] = it }
            names.addView(state, params(4))
            line.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            val identify = button(getString(R.string.steering_identify)) { identify(operation) }
            identifyButtons.add(identify)
            line.addView(identify, LinearLayout.LayoutParams(-2, -2))
            content.addView(line, params(4))
        }

        cancelButton = button(getString(R.string.cancel)) { cancelIdentification(); instruction.setText(R.string.steering_choose_button) }
            .apply { visibility = View.GONE }
        content.addView(cancelButton, params(8))

        cloudState = label("", 16f, MUTED)
        content.addView(cloudState, params(16))
        saveButton = button(getString(R.string.steering_save_and_upload), primary = true) { saveAndUpload() }
        content.addView(saveButton, params(12))
        content.addView(button(getString(R.string.steering_export)) {
            val profile = SteeringProfiles.load(this)
            if (profile == null) toast(R.string.steering_save_first)
            else { exportProfile = profile; export.launch(profile.fileName) }
        }, params(10))
        restoreButton = button(getString(R.string.steering_restore_defaults)) {
            runCatching { SteeringProfiles.disable(this); CarPlayMediaKeys.reloadSteeringProfile(this) }
                .onSuccess { cancelIdentification(); instruction.setText(R.string.steering_default_restored); updateRestoreButton() }
                .onFailure { instruction.setText(R.string.steering_save_failed) }
        }
        content.addView(restoreButton, params(10))

        if (developer) {
            hotspotJoinControls = HotspotJoinControls(this, { CarPlayBackgroundSession.hasSession() },
                labelFactory = { label(it, 14f, MUTED) },
                buttonFactory = { title, click -> button(title) { click() } })
                .also { content.addView(it.build(), params(24)) }
        }
        // carlito | Expose wheel test status without exposing unrelated repair controls.
        if (developer || intent.getBooleanExtra("wheelTest", false)) {
            content.addView(label(getString(R.string.steering_diagnostics), 20f, bold = true), params(24))
            diagnosticText = label("", 14f, MUTED).apply { setTextIsSelectable(true) }
            content.addView(diagnosticText, params(12))
        }
        if (developer) {
            content.addView(label(getString(R.string.steering_log_permission, packageName), 14f, MUTED), params(8))
            content.addView(button(getString(R.string.steering_log_match)) {
                AlertDialog.Builder(this).setTitle(R.string.steering_log_match)
                    .setItems(SteeringBinding.operations.map(::operationName).toTypedArray()) { _, index ->
                        editLogRule(SteeringBinding.operations[index])
                    }.setNegativeButton(R.string.cancel, null).show()
            }, params(12))
        }
        setContentView(scroll)
        updateBindings()
        updateCloudState()
        updateAccess()
        updateRestoreButton()
    }

    private fun identify(operation: String) {
        learningOperation = operation
        identifyButtons.forEach { it.enable(false) }
        saveButton.enable(false)
        restoreButton.enable(false)
        cancelButton.visibility = View.VISIBLE
        instruction.text = getString(R.string.steering_press_button, operationName(operation))
        CarPlayMediaKeys.startSteeringLearning(this, this) { observed ->
            if (observed == null) {
                cancelIdentification()
                instruction.setText(R.string.steering_no_key)
            } else onIdentifiedKey(operation, observed)
        }
    }

    private fun onIdentifiedKey(operation: String, key: SteeringObservedKey) {
        if (learningOperation != operation) return
        if (key.keyCode == 0) {
            if (key.broadcastAction.isNotBlank()) instruction.text = getString(R.string.steering_press_again, operationName(operation))
            return
        }
        if (key.event !in 0..4) { instruction.setText(R.string.steering_event_incomplete); return }
        val captured = SteeringBinding(operation, key.keyCode, key.event, key.source, key.logTag,
            key.broadcastAction, key.keyExtra, key.eventExtra, key.logContains)
        val duplicate = draft.bindings.firstOrNull { it.operation != operation && it.inputId == captured.inputId }
        draft = draft.copy(bindings = draft.bindings.filterNot { it.operation == operation || it.inputId == captured.inputId } + captured)
        cancelIdentification()
        updateBindings()
        instruction.text = if (duplicate == null) getString(R.string.steering_filled, operationName(operation))
            else getString(R.string.steering_reassigned, operationName(duplicate.operation), operationName(operation))
    }

    private fun cancelIdentification() {
        val wasLearning = learningOperation != null
        CarPlayMediaKeys.stopSteeringLearning(this)
        learningOperation = null
        if (wasLearning && ::instruction.isInitialized) instruction.setText(R.string.steering_choose_button)
        identifyButtons.forEach { it.enable(true) }
        if (::saveButton.isInitialized) saveButton.enable(true)
        if (::restoreButton.isInitialized) restoreButton.enable(true)
        if (::cancelButton.isInitialized) cancelButton.visibility = View.GONE
    }

    private fun currentDraft() = draft.copy(carModel = carModel.text.toString().trim(), headUnitModel = headUnit.text.toString().trim())

    private fun saveAndUpload() {
        val profile = currentDraft().copy(savedAt = System.currentTimeMillis())
        if (profile.carModel.isBlank()) { carModel.error = getString(R.string.steering_enter_car_model); carModel.requestFocus(); return }
        if (profile.headUnitModel.isBlank()) { headUnit.error = getString(R.string.steering_enter_head_unit); headUnit.requestFocus(); return }
        if (profile.bindings.isEmpty()) { instruction.setText(R.string.steering_identify_first); return }
        runCatching {
            SteeringProfiles.save(this, profile)
            CarPlayMediaKeys.reloadSteeringProfile(this)
        }.onSuccess {
            draft = profile
            instruction.setText(R.string.steering_applied)
            updateCloudState()
            updateRestoreButton()
        }.onFailure {
            android.util.Log.w("DiPlay-SteeringProfiles", "Could not save steering profile", it)
            instruction.setText(R.string.steering_save_failed)
        }
    }

    private fun updateBindings() {
        bindingLabels.forEach { (operation, view) ->
            val binding = draft.bindings.firstOrNull { it.operation == operation }
            view.text = if (binding == null) getString(R.string.steering_unassigned)
            else if (developer) "${binding.keyCode} · ${binding.source} · ${binding.event}"
            else getString(R.string.steering_assigned)
        }
    }

    private fun updateCloudState() {
        if (!::cloudState.isInitialized) return
        cloudState.setText(when (SteeringProfiles.uploadState(this)) {
            "uploaded" -> R.string.steering_cloud_received
            "pending" -> R.string.steering_cloud_pending
            "failed" -> R.string.steering_cloud_failed
            else -> R.string.steering_cloud_after_save
        })
    }

    private fun updateAccess() {
        if (::accessButton.isInitialized) {
            accessButton.visibility = if (SteeringLogAccess.granted(this) ||
                CarPlayMediaKeys.steeringDirectReady()
            ) View.GONE else View.VISIBLE
            accessButton.enable(!requestingAccess)
        }
    }

    private fun updateRestoreButton() {
        if (::restoreButton.isInitialized) restoreButton.visibility =
            if (SteeringProfiles.enabled(this) && SteeringProfiles.load(this) != null) View.VISIBLE else View.GONE
    }

    private fun requestAccess() {
        if (requestingAccess) return
        requestingAccess = true
        updateAccess()
        instruction.setText(R.string.steering_access_connecting)
        val app = applicationContext
        Thread({
            val result = runCatching { SteeringLogAccess.request(app) }.getOrDefault(SteeringLogAccess.Result.DENIED)
            handler.post {
                requestingAccess = false
                if (isDestroyed || isFinishing) return@post
                updateAccess()
                if (result == SteeringLogAccess.Result.READY && learningOperation != null) {
                    CarPlayMediaKeys.reloadSteeringProfile(this)
                }
                if (learningOperation == null) instruction.setText(when (result) {
                    SteeringLogAccess.Result.READY -> R.string.steering_choose_button
                    SteeringLogAccess.Result.APPROVAL_REQUIRED -> R.string.steering_access_approval
                    SteeringLogAccess.Result.UNAVAILABLE -> R.string.steering_access_unavailable
                    SteeringLogAccess.Result.DENIED -> R.string.steering_access_denied
                })
            }
        }, "diplay-key-log-access").start()
    }

    private fun editLogRule(operation: String) {
        cancelIdentification()
        val existing = draft.bindings.firstOrNull { it.operation == operation && it.source == "logcat" }
        val form = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
        form.addView(label(getString(R.string.steering_log_tag), 16f))
        val tag = input(existing?.logTag.orEmpty(), getString(R.string.steering_log_tag)).apply {
            filters = arrayOf(InputFilter.LengthFilter(100))
        }
        form.addView(tag, params(6))
        form.addView(label(getString(R.string.steering_log_parts), 14f, MUTED), params(12))
        val fragments = input(existing?.logContains?.joinToString("&&").orEmpty(), "").apply {
            isSingleLine = false; minLines = 3; filters = arrayOf(InputFilter.LengthFilter(646))
        }
        form.addView(fragments, params(6))
        val dialog = AlertDialog.Builder(this).setTitle(operationName(operation)).setView(form)
            .setPositiveButton(R.string.steering_identify, null).setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pieces = fragments.text.toString().split("&&").map(String::trim)
                val binding = SteeringBinding(operation, 0, 2, "logcat", tag.text.toString().trim(), logContains = pieces)
                if (runCatching { SteeringBinding.fromJson(binding.json()) }.isFailure) {
                    fragments.error = getString(R.string.steering_log_match_invalid); return@setOnClickListener
                }
                val duplicate = draft.bindings.firstOrNull { it.operation != operation && it.inputId == binding.inputId }
                draft = draft.copy(bindings = draft.bindings.filterNot { it.operation == operation || it.inputId == binding.inputId } + binding)
                updateBindings()
                instruction.text = if (duplicate == null) getString(R.string.steering_filled, operationName(operation))
                    else getString(R.string.steering_reassigned, operationName(duplicate.operation), operationName(operation))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun operationName(operation: String) = getString(when (operation) {
        "next" -> R.string.steering_next
        "previous" -> R.string.steering_previous
        "siri" -> R.string.steering_voice
        else -> R.string.steering_play_pause
    })

    private fun input(value: String, hintText: String) = EditText(this).apply {
        setText(value); hint = hintText; textSize = 17f; setTextColor(TEXT); setHintTextColor(MUTED)
        isSingleLine = true; minHeight = dp(56); filters = arrayOf(InputFilter.LengthFilter(120))
        setPadding(dp(14), dp(8), dp(14), dp(8)); background = rounded(SURFACE)
    }
    private fun label(value: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun button(value: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 17f; minHeight = dp(56)
        setTextColor(if (primary) BG else TEXT); setPadding(dp(14), dp(8), dp(14), dp(8))
        background = RippleDrawable(ColorStateList.valueOf(0x336F9FD9), rounded(if (primary) ACCENT else SURFACE), null)
        setOnClickListener { click() }
    }
    private fun Button.enable(value: Boolean) { isEnabled = value; alpha = if (value) 1f else 0.45f }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat(); setStroke(dp(1), BORDER) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun params(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(resource: Int) { Toast.makeText(this, resource, Toast.LENGTH_LONG).show() }

    companion object {
        private val BG = Color.rgb(12, 17, 27)
        private val SURFACE = Color.rgb(21, 30, 44)
        private val BORDER = Color.rgb(42, 56, 75)
        private val ACCENT = Color.rgb(166, 200, 255)
        private val TEXT = Color.rgb(241, 245, 252)
        private val MUTED = Color.rgb(168, 182, 202)
    }
}
