package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.AtomicFile
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import java.io.ByteArrayInputStream
import java.io.File
import org.json.JSONObject

/** Local HTML has no JavaScript-to-Java bridge and cannot fetch network or private files. */
internal object HtmlHome {
    const val LIMIT = 262144
    val actions = setOf("connect", "usb", "choose-device", "disconnect", "settings", "check-update", "source", "edit-home", "default-home")
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "custom-home.html"))
    fun read(context: Context): String? = runCatching { file(context).readFully().takeIf { it.size <= LIMIT }?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } }.getOrNull()
    fun save(context: Context, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        require(html.isNotBlank() && bytes.size <= LIMIT) { "HTML 必须非空且不超过 256 KB" }
        val target = file(context)
        val stream = target.startWrite()
        try { stream.write(bytes); target.finishWrite(stream) } catch (error: Exception) { target.failWrite(stream); throw error }
    }
    fun reset(context: Context) = file(context).delete()
    fun action(url: Uri, mainFrame: Boolean, gesture: Boolean): String? =
        if (mainFrame && gesture && url.scheme == "diplay" && url.host == "action" && url.query == null && url.fragment == null && url.port == -1 && url.userInfo == null)
            url.path?.removePrefix("/")?.takeIf { it in actions } else null
    fun sample(context: Context) = context.assets.open("custom-home-example.html").bufferedReader().use { it.readText() }
    val guide = """
        请生成适配横屏、竖屏和大字体的车机首页，输出完整 HTML。CSS、JavaScript、SVG 必须内嵌；图片仅使用 data URI。禁止外部链接、CDN、iframe、网络请求。不使用 Java 接口。
        使用用户点击的原生入口：
        <a href="diplay://action/connect">无线连接 / 打开 CarPlay</a>
        <a href="diplay://action/usb">USB 连接</a>
        <a href="diplay://action/choose-device">选择设备</a>
        <a href="diplay://action/disconnect">断开连接</a>
        <a href="diplay://action/settings">设置</a>
        <a href="diplay://action/check-update">检查更新</a>
        <a href="diplay://action/source">项目源码</a>
        <a href="diplay://action/edit-home">编辑首页</a>
        <a href="diplay://action/default-home">恢复默认首页</a>
        监听 window 的 diplay-state 事件，event.detail 含 connected、connecting、status、version；也可读取 window.DiPlayState。使用 textContent 显示状态。不含车辆数据和设备标识。
        按钮触控高度至少 48px；窄屏堆叠、允许纵向滚动；HTML UTF-8 总大小不超过 256 KB。
    """.trimIndent()

    fun edit(activity: Activity, applied: () -> Unit) {
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 12, 20, 12) }
        val editor = EditText(activity).apply {
            hint = "在这里粘贴完整 HTML / Paste HTML here"
            typeface = android.graphics.Typeface.MONOSPACE; textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 10; maxLines = 16; gravity = android.view.Gravity.TOP
            setText(read(activity) ?: sample(activity))
            filters = arrayOf(android.text.InputFilter.LengthFilter(LIMIT))
        }
        layout.addView(TextView(activity).apply { text = "自定义首页仅保存在本机。可将示例和接口说明交给 AI 生成页面。外部图片、网络和文件访问不可用。" })
        fun button(title: String, click: () -> Unit) { layout.addView(Button(activity).apply { text = title; setOnClickListener { click() } }) }
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        button("复制 AI 提示词和功能入口") { clipboard.setPrimaryClip(ClipData.newPlainText("HTML home guide", guide)) }
        button("复制完整示例 HTML") { clipboard.setPrimaryClip(ClipData.newPlainText("HTML home example", sample(activity))) }
        button("粘贴 HTML") { clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.let { editor.setText(it.take(LIMIT)) } }
        layout.addView(editor)
        button("预览（功能入口仅提示，不执行）") {
            val preview = HtmlHomeView(activity, editor.text.toString()) { Toast.makeText(activity, "功能入口：$it", Toast.LENGTH_SHORT).show() }
            val dialog = AlertDialog.Builder(activity).setTitle("首页预览").setView(preview).setPositiveButton("关闭", null).create()
            dialog.setOnDismissListener { preview.dispose() }; dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("自定义 HTML 首页").setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("保存并应用", null).setNegativeButton("取消", null).setNeutralButton("恢复默认", null).create()
        dialog.show(); dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        run {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { save(activity, editor.text.toString()) }.onSuccess { dialog.dismiss(); applied() }
                    .onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(activity).setMessage("删除保存的 HTML，恢复默认首页？").setNegativeButton("取消", null)
                    .setPositiveButton("恢复默认") { _, _ -> reset(activity); dialog.dismiss(); applied() }.show()
            }
        }
    }
}

internal class HtmlHomeView(context: Context, html: String, private val onAction: (String) -> Unit) : LinearLayout(context) {
    internal val web = WebView(context)
    private var lastState = ""
    init {
        orientation = VERTICAL; setBackgroundColor(Color.rgb(15, 24, 40))
        val toolbar = LinearLayout(context)
        listOf("编辑首页" to "edit-home", "默认首页" to "default-home", "设置" to "settings").forEach { (label, action) ->
            toolbar.addView(Button(context).apply { text = label; minHeight = (48 * resources.displayMetrics.density).toInt(); setOnClickListener { onAction(action) } }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(toolbar); addView(web, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        web.setBackgroundColor(Color.rgb(15, 24, 40))
        web.settings.apply {
            javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true; domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: JsPromptResult): Boolean { result.cancel(); return true }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                HtmlHome.action(request.url, request.isForMainFrame, request.hasGesture())?.let { action -> view.post { onAction(action) } }
                return true
            }
            @Deprecated("Legacy navigations do not supply a trustworthy gesture")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            override fun onPageFinished(view: WebView, url: String) { publish() }
        }
        val policy = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'none'"
        web.loadDataWithBaseURL("https://diplay-home.invalid/", "<!doctype html><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"$policy\">$html", "text/html", "UTF-8", null)
    }
    fun state(connected: Boolean, connecting: Boolean, status: String, version: String) {
        val value = JSONObject().put("connected", connected).put("connecting", connecting).put("status", status).put("version", version).toString()
        if (value != lastState) { lastState = value; publish() }
    }
    private fun publish() { if (lastState.isNotEmpty()) web.evaluateJavascript("window.DiPlayState=$lastState;window.dispatchEvent(new CustomEvent('diplay-state',{detail:window.DiPlayState}));", null) }
    fun dispose() { web.stopLoading(); removeView(web); web.destroy() }
}
