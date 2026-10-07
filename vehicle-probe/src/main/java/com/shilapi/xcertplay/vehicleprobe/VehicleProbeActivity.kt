// carlito | DiPlay vehicle probe integration. GD source, GPL-3.0.
package com.shilapi.xcertplay.vehicleprobe

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class VehicleProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var service: ProbeService? = null
    private var bound = false
    private var lastState: ProbeState? = null
    private var developerUnlocked = false
    private var diagnosticOpen = false
    private var exporting = false
    private var pickingExport = false
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var counts: TextView
    private lateinit var scan: Button
    private lateinit var export: Button
    private lateinit var progress: ProgressBar
    private val green = Color.rgb(21, 106, 81)
    private val ink = Color.rgb(27, 38, 33)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as ProbeService.LocalBinder).service
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            lastState = null
            refresh()
        }
    }
    private val poll = object : Runnable {
        override fun run() { refresh(); main.postDelayed(this, 500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Entry is provided only by DiPlay's version-unlocked developer page; activity is private.
        if (!intent.getBooleanExtra("developer", false)) { finish(); return }
        developerUnlocked = true
        pickingExport = savedInstanceState?.getBoolean("exporting") ?: false
        exporting = pickingExport
        showHome()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("developer", developerUnlocked)
        outState.putBoolean("exporting", pickingExport)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        bound = bindService(Intent(this, ProbeService::class.java), connection, Context.BIND_AUTO_CREATE)
        main.post(poll)
    }

    override fun onStop() {
        main.removeCallbacks(poll)
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }

    private fun showHome() {
        diagnosticOpen = false
        val column = page()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("车辆扫描", 28, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("设置") { showSettings() })
        column.addView(top)
        column.addView(label("读取车辆支持的属性与当前状态", 16).apply { setPadding(0, dp(8), 0, dp(28)) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(223, 229, 224))
            }
        }
        status = label("正在准备", 24, true)
        detail = label("", 15).apply { setPadding(0, dp(10), 0, dp(18)) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        counts = label("", 19).apply { setPadding(0, dp(20), 0, dp(8)); setLineSpacing(dp(8).toFloat(), 1f) }
        card.addView(status); card.addView(detail); card.addView(progress); card.addView(counts)
        column.addView(card)
        scan = button("开始全车扫描") {
            if (service?.state?.let { !it.loading && !it.running } == true) {
                runCatching {
                    val intent = Intent(this, ProbeService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                }.onSuccess { scan.isEnabled = false }.onFailure {
                    Toast.makeText(this, "暂时无法开始扫描，请重试", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(green) }
        export = button("导出扫描报告") { exportReport() }
        column.addView(scan, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(24) })
        column.addView(export, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(8) })
        // carlito | Reports can fill read-only property addresses without scanning the vehicle again.
        column.addView(button("车型属性配置") { showProfileMenu() }, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(8) })
        column.addView(button("查看车辆数据") { showVehicleValues() }, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(8) })
        column.addView(label("扫描仅读取车辆状态，不改变车辆设置。", 14).apply { setPadding(0, dp(16), 0, 0) })
        lastState = null
        refresh()
    }

    private fun refresh() {
        if (diagnosticOpen || !::status.isInitialized) return
        val state = service?.state ?: ProbeState()
        export.isEnabled = state.lastScan > 0 && !exporting
        if (state == lastState) return
        lastState = state
        scan.isEnabled = !state.loading && !state.running
        scan.text = if (state.running) "正在扫描…" else if (state.lastScan > 0) "重新扫描" else "开始全车扫描"
        progress.visibility = if (state.running || state.loading) View.VISIBLE else View.GONE
        // Enumeration completes before VHAL scanning; keep a visible busy indicator until both finish.
        progress.isIndeterminate = state.total == 0 || state.completed >= state.total
        if (!progress.isIndeterminate) { progress.max = state.total; progress.progress = state.completed }
        status.text = when {
            state.running -> "正在扫描车辆"
            state.loading -> "正在准备"
            state.error -> "本次扫描未完成"
            state.lastScan == 0L -> "准备就绪"
            state.summary.readable == 0 -> "未读取到车辆数据"
            else -> "扫描完成"
        }
        detail.text = when {
            state.running -> "正在读取车辆状态，完成后可导出报告。"
            state.loading -> "正在连接扫描服务。"
            state.error -> state.errorMessage ?: "请重试。已有报告仍可导出。"
            state.lastScan == 0L -> "点击下方按钮开始扫描。"
            state.summary.readable == 0 -> "请在设置中检查车辆访问授权后重试。"
            else -> "上次扫描：${SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(state.lastScan))}"
        }
        counts.visibility = if (state.lastScan > 0 && !state.running) View.VISIBLE else View.GONE
        counts.text = "属性项目  ${state.summary.total}\n有效读数  ${state.summary.readable}    无效读数  ${state.summary.invalid}\n暂不可用  ${state.summary.unavailable}"
    }

    private fun showSettings() {
        val options = if (developerUnlocked) arrayOf("车辆访问授权", "关于", "开发者诊断", "车桥连接状态") else arrayOf("车辆访问授权", "关于")
        AlertDialog.Builder(this).setTitle("设置").setItems(options) { _, which ->
            when (which) { 0 -> authorize(); 1 -> showAbout(); 2 -> showDiagnostics(); 3 -> showBridgeStatus() }
        }.setNegativeButton("关闭", null).show()
    }

    private fun showAbout() {
        val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        val version = label("版本 $versionName", 18).apply {
            setPadding(dp(24), dp(22), dp(24), dp(22))
            minHeight = dp(56)
        }
        AlertDialog.Builder(this).setTitle("关于车辆扫描").setView(version).setPositiveButton("关闭", null).show()
    }

    private fun authorize() {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${VehicleBridgeClient.BRIDGE_PACKAGE}"))) }.onFailure {
            Toast.makeText(this, "请先安装车辆数据桥", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDiagnostics() {
        if (!developerUnlocked) return
        diagnosticOpen = true
        val column = page()
        column.addView(button("返回") { showHome() })
        column.addView(label("开发者诊断", 24, true))
        val reportView = label("正在读取报告…", 13).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
            setPadding(0, dp(16), 0, 0)
        }
        column.addView(reportView)
        val scanner = service
        io.execute {
            val text = runCatching {
                scanner?.openReport()?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val chars = CharArray(64 * 1024)
                    var size = 0
                    while (size < chars.size) {
                        val read = reader.read(chars, size, chars.size - size)
                        if (read < 0) break
                        size += read
                    }
                    String(chars, 0, size) + if (reader.read() >= 0) "\n\n预览已截取，完整内容请导出报告。" else ""
                } ?: "暂无扫描报告。"
            }.getOrDefault("暂无扫描报告。")
            main.post { if (diagnosticOpen && !isDestroyed) reportView.text = text }
        }
    }

    private fun exportReport() {
        if (exporting) return
        exporting = true
        pickingExport = true
        export.isEnabled = false
        val filename = "车辆扫描-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.txt"
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, filename)
            }, 41)
        } catch (_: android.content.ActivityNotFoundException) {
            exporting = false
            pickingExport = false
            refresh()
            Toast.makeText(this, "未找到文件保存工具", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42) {
            if (resultCode == RESULT_OK) data?.data?.let { uri ->
                extractReport { checkNotNull(contentResolver.openInputStream(uri)) }
            }
            return
        }
        if (requestCode != 41) return
        pickingExport = false
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { exporting = false; refresh(); return }
        saveReport(uri)
    }

    private fun importReport() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, 42)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "未找到文件选择工具", Toast.LENGTH_LONG).show()
        }
    }

    private fun extractCurrentReport() = extractReport { ProbeReports.open(applicationContext) }

    private fun extractReport(open: () -> java.io.InputStream) {
        io.execute {
            val result = runCatching {
                val text = open().use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(bytes.size() + count <= ReportPropertyImporter.MAX_BYTES)
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toString("UTF-8")
                }
                ReportPropertyImporter.extract(text).also { require(it.isNotEmpty()) }
            }
            main.post {
                if (!isDestroyed && !isFinishing) result.onSuccess { bindings ->
                    showProfileEditor(bindings, VehiclePropertyProfiles.load(applicationContext)?.model.orEmpty())
                }.onFailure {
                    Toast.makeText(this, "未提取到可用属性，请选择完整的车辆扫描报告", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun editSavedProfile() {
        io.execute {
            val result = runCatching { VehicleBridgeClient(applicationContext).use { it.activeProfile() } }
            main.post { if (!isDestroyed) {
                val profile = result.getOrNull()
                if (result.isFailure) showBridgeFailure(result.exceptionOrNull())
                else if (profile == null) Toast.makeText(this, "请先选择车型或导入属性配置", Toast.LENGTH_LONG).show()
                else showProfileEditor(profile.bindings, profile.model)
            } }
        }
    }

    private fun showProfileMenu() {
        io.execute {
            val result = runCatching { VehicleBridgeClient(applicationContext).use { it.presets() } }
            main.post { if (!isDestroyed) {
                val presets = result.getOrDefault(emptyList())
                if (result.isFailure) showBridgeFailure(result.exceptionOrNull())
                val items = listOf("已保存配置", "从本次报告提取属性", "导入扫描报告") + presets.map { it.model }
                AlertDialog.Builder(this).setTitle("车型属性配置").setItems(items.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> editSavedProfile(); 1 -> extractCurrentReport(); 2 -> importReport()
                        else -> presets[which - 3].let { showProfileEditor(it.bindings, it.model) }
                    }
                }.setNegativeButton("关闭", null).show()
            } }
        }
    }

    private fun showBridgeFailure(error: Throwable?) {
        val text = if (error is SecurityException) "车辆数据桥未授权当前 DiPlay 签名，请安装正式签名版本"
            else error?.message?.take(180) ?: "车辆数据桥暂不可用，请重试"
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun showBridgeStatus() {
        io.execute {
            val result = runCatching { VehicleBridgeClient(applicationContext).use { it.status() } }
            main.post { if (!isDestroyed) {
                val data = result.getOrNull()
                if (data == null) { showBridgeFailure(result.exceptionOrNull()); return@post }
                val stage = when (data.getString("stage")) {
                    "CONNECTED" -> "车桥已连接"
                    "PROFILE_REQUIRED" -> "车桥已连接，请保存车型配置"
                    else -> "车桥正在启动，请稍后重试"
                }
                val channels = data.getStringArrayList("channels").orEmpty().joinToString("\n")
                AlertDialog.Builder(this).setTitle("车桥连接状态")
                    .setMessage("$stage\n车型：${data.getString("model").orEmpty().ifBlank { "未选择" }}\n$channels")
                    .setPositiveButton("关闭", null).show()
            } }
        }
    }

    // carlito | Attribute categories consume normalized values from the separate APK.
    private fun showVehicleValues() {
        io.execute {
            val result = runCatching { VehicleBridgeClient(applicationContext).use { client ->
                val profile = client.activeProfile() ?: error("请先保存车型属性配置")
                profile to client.readProfile(profile)
            } }
            main.post { if (!isDestroyed) {
                val data = result.getOrNull()
                if (data == null) { showBridgeFailure(result.exceptionOrNull()); return@post }
                val groups = data.first.bindings.groupBy { it.field.category }.entries.toList()
                AlertDialog.Builder(this).setTitle(data.first.model).setItems(groups.map { it.key }.toTypedArray()) { _, index ->
                    val group = groups[index]
                    val text = group.value.joinToString("\n") { binding ->
                        "${binding.field.title}：${data.second[binding.field]?.let { "$it ${binding.unit}" } ?: "暂不可用"}"
                    }
                    AlertDialog.Builder(this).setTitle(group.key).setMessage(text).setPositiveButton("关闭", null).show()
                }.setNegativeButton("关闭", null).show()
            } }
        }
    }

    private fun showProfileEditor(bindings: List<VehiclePropertyBinding>, model: String) {
        if (!developerUnlocked) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        content.addView(label("车型名称", 16, true))
        val modelInput = EditText(this).apply { setText(model); hint = "例如：银河 L6"; setSingleLine(true) }
        content.addView(modelInput)
        content.addView(label("属性地址已填入。倍率和偏移用于校准换算后的数值；单位未确认时保留原始值。", 14))
        data class Inputs(val binding: VehiclePropertyBinding, val scale: EditText, val offset: EditText, val unit: EditText)
        val inputs = bindings.map { binding ->
            content.addView(label(binding.field.title, 18, true).apply { setPadding(0, dp(20), 0, dp(6)) })
            if (binding.field == VehicleField.GEAR) content.addView(label(
                "挡位编码确认是 0=P、1=R、2=N、3=D 时，单位可填 PRND 以辅助导航；未确认时保留原始值。", 14))
            content.addView(label("${binding.hexId} · ${binding.chain}/${binding.kind} · ${binding.area}", 13))
            fun input(title: String, value: String, numeric: Boolean): EditText {
                content.addView(label(title, 14))
                return EditText(this).apply {
                    setText(value); setSingleLine(true)
                    inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT
                    content.addView(this)
                }
            }
            Inputs(binding, input("倍率", binding.scale.toString(), true),
                input("偏移", binding.offset.toString(), true), input("单位", binding.unit, false))
        }
        val readings = label("", 15).apply { setPadding(0, dp(16), 0, 0) }
        val read = button("读取当前数值") { }
        fun profile(): VehiclePropertyProfile = VehiclePropertyProfile(modelInput.text.toString().trim(),
            inputs.map { item -> item.binding.copy(scale = item.scale.text.toString().toDouble(),
                offset = item.offset.text.toString().toDouble(), unit = item.unit.text.toString().trim()) })
        read.setOnClickListener {
            if (service?.state?.running == true) {
                Toast.makeText(this, "请等待当前扫描完成", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selected = runCatching { profile() }.getOrNull()
            if (selected == null || selected.bindings.any { !it.scale.isFinite() || it.scale <= 0 || !it.offset.isFinite() }) {
                Toast.makeText(this, "请填写有效的倍率和偏移", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            read.isEnabled = false
            readings.text = "正在读取…"
            io.execute {
                val result = runCatching { VehicleBridgeClient(applicationContext).use { it.readProfile(selected) } }
                main.post { if (!isDestroyed) {
                    read.isEnabled = true
                    readings.text = result.getOrNull()?.let { values -> selected.bindings.joinToString("\n") { binding ->
                        "${binding.field.title}：${values[binding.field]?.let { "$it ${binding.unit}" } ?: "暂不可用"}"
                    } } ?: "暂时无法读取车辆数据，请检查车辆访问授权。"
                } }
            }
        }
        content.addView(read); content.addView(readings)
        if (bindings.any { it.field == VehicleField.STEERING_BUTTON }) content.addView(label(
            "按键属性用于观察数值变化。按键动作与系统拦截需在方控诊断中设置。", 14))
        val dialog = AlertDialog.Builder(this).setTitle("车型属性配置")
            .setView(ScrollView(this).apply { addView(content) }).setPositiveButton("保存配置", null)
            .setNegativeButton("关闭", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = runCatching { profile().also { VehiclePropertyProfiles.encode(it) } }.getOrNull()
                if (selected == null) {
                    Toast.makeText(this, "请填写车型名称和有效的倍率、偏移及单位", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                io.execute {
                    val result = runCatching {
                        VehicleBridgeClient(applicationContext).use { it.saveProfile(selected) }
                        VehiclePropertyProfiles.save(applicationContext, selected)
                    }
                    main.post { if (!isDestroyed) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        if (result.isSuccess) { Toast.makeText(this, "车型属性配置已保存", Toast.LENGTH_SHORT).show(); dialog.dismiss() }
                        else showBridgeFailure(result.exceptionOrNull())
                    } }
                }
            }
        }
        dialog.show()
    }

    private fun saveReport(uri: android.net.Uri) {
        val app = applicationContext
        io.execute {
            val result = runCatching {
                // A file descriptor remains a snapshot even if another scan replaces the report.
                ProbeReports.open(app).use { input ->
                    checkNotNull(app.contentResolver.openOutputStream(uri, "w")).use { output -> input.copyTo(output) }
                }
            }
            // The picker created this document for us; discard partial output on failure.
            if (result.isFailure) runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) }
            main.post {
                exporting = false
                if (!isDestroyed) {
                    refresh()
                    Toast.makeText(this, if (result.isSuccess) "报告已保存" else "保存失败，请重试", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Deprecated("Uses platform back for Android 11")
    override fun onBackPressed() { if (diagnosticOpen) showHome() else super.onBackPressed() }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun page(): LinearLayout {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(32))
        }
        val frame = android.widget.FrameLayout(this).apply {
            addView(column, android.widget.FrameLayout.LayoutParams(
                minOf(resources.displayMetrics.widthPixels, dp(880)), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(frame) })
        return column
    }

    private fun label(text: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(ink)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 16f; minHeight = dp(52)
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
