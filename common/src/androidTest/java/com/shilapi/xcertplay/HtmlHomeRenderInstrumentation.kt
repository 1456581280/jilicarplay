package com.shilapi.xcertplay

import android.app.Activity
import android.app.Instrumentation
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HtmlHomeRenderActivity : Activity()

/** Real WebView smoke test, without relying on Robolectric's WebView stub. */
class HtmlHomeRenderInstrumentation : Instrumentation() {
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private var onlineUpdates = false
    override fun onCreate(arguments: Bundle?) {
        onlineUpdates = arguments?.getString("updates") == "true"
        super.onCreate(arguments); start()
    }

    override fun onStart() {
        var activity: Activity? = null
        try {
            val live = if (onlineUpdates) {
                val source = UpdateSource()
                val release = checkNotNull(source.latest("0.0.0")) { "No versioned APK found in configured folder" }
                release to source.notes().also { check(it.isNotBlank()) }
            } else null
            val host = startActivitySync(Intent(context, HtmlHomeRenderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = host
            lateinit var home: HtmlHomeView
            onMain {
                // A complete document, inline style and script, SVG and multibyte source.
                val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                    <style>body{background:#123456;color:white}h1{font-size:32px}</style></head>
                    <body><h1 id="heading">自定义首页</h1><svg width="30" height="30"><circle cx="15" cy="15" r="12" fill="orange"/></svg>
                    <a href="diplay://action/settings">设置</a><script>document.title='inline-script-ok';</script></body></html>""".trimIndent()
                HtmlHome.save(host, html)
                home = HtmlHomeView(host, HtmlHome.read(host)!!) {}
                host.setContentView(home)
            }
            var result: JSONObject? = null
            repeat(40) {
                if (result == null) {
                    val done = CountDownLatch(1)
                    onMain {
                        home.web.evaluateJavascript("JSON.stringify({title:document.title,text:document.body?document.body.innerText:'',height:document.body?document.body.offsetHeight:0,color:document.body?getComputedStyle(document.body).backgroundColor:''})") { value ->
                            runCatching { JSONObject(org.json.JSONTokener(value).nextValue() as String) }.getOrNull()?.let {
                                if (it.optString("title") == "inline-script-ok") result = it
                            }
                            done.countDown()
                        }
                    }
                    check(done.await(3, TimeUnit.SECONDS)) { "WebView JavaScript callback timed out" }
                    if (result == null) Thread.sleep(250)
                }
            }
            val rendered = checkNotNull(result) { "Local HTML or inline JavaScript did not load" }
            check(rendered.getString("text").contains("自定义首页")) { rendered.toString() }
            check(rendered.getInt("height") > 0) { rendered.toString() }
            check(rendered.getString("color") == "rgb(18, 52, 86)") { rendered.toString() }
            onMain {
                check(home.childCount == 1 && home.web.width == home.width && home.web.height == home.height)
                home.dispose()
                HtmlHome.reset(host)
            }
            if (live != null) {
                lateinit var updates: ReleaseUpdates
                var checks = 0
                var forcedRelease: AppRelease? = null
                onMain {
                    host.getSharedPreferences("release_updates", android.content.Context.MODE_PRIVATE).edit().clear().commit()
                    host.setContentView(TextView(host).apply { text = "连接功能可继续使用" })
                    val repository = object : UpdateRepository {
                        override fun latest(installed: String): AppRelease? { checks++; return forcedRelease }
                        override fun notes() = live.second
                    }
                    updates = ReleaseUpdates(host, repository, true) { it() }
                    updates.onResume()
                }
                waitForIdleSync()
                onMain { updates.onWindowFocusChanged() }
                waitForIdleSync()
                val field = ReleaseUpdates::class.java.getDeclaredField("dialog").apply { isAccessible = true }
                onMain {
                    val log = checkNotNull(field.get(updates) as? AlertDialog) { "No native log dialog" }
                    check(log.isShowing)
                    fun hasLog(view: android.view.View): Boolean =
                        if (view is TextView && view.text.toString() == live.second) true
                        else if (view is android.view.ViewGroup) (0 until view.childCount).any { hasLog(view.getChildAt(it)) } else false
                    check(hasLog(log.window!!.decorView)) { "Native log did not contain fetched Feishu content" }
                }
                uiAutomation.takeScreenshot()?.let { bitmap ->
                    val file = java.io.File(host.getExternalFilesDir(null), "native-update-notes.png")
                    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                onMain {
                    (field.get(updates) as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                waitForIdleSync()
                onMain {
                    updates.onPause(); updates.onResume()
                    check(checks == 2) { "Each opening must check again" }
                    check(field.get(updates) == null) { "Installed-version log should only show once" }
                    forcedRelease = live.first
                    updates.onPause(); updates.onResume()
                }
                repeat(40) {
                    if (field.get(updates) == null) { onMain { updates.onWindowFocusChanged() }; Thread.sleep(100) }
                }
                onMain { (field.get(updates) as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
                waitForIdleSync()
                lateinit var downloadWeb: android.webkit.WebView
                onMain {
                    val browser = checkNotNull(field.get(updates) as? AlertDialog)
                    fun findWeb(view: android.view.View): android.webkit.WebView? {
                        if (view is android.webkit.WebView) return view
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findWeb(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    downloadWeb = checkNotNull(findWeb(browser.window!!.decorView)) { "Download did not stay inside the app" }
                }
                var loaded = false
                repeat(60) {
                    if (!loaded) {
                        val done = CountDownLatch(1)
                        onMain {
                            downloadWeb.evaluateJavascript("document.title") { title ->
                                loaded = title.contains("JiliCarPlay-${live.first.version}.apk")
                                done.countDown()
                            }
                        }
                        check(done.await(3, TimeUnit.SECONDS))
                        if (!loaded) Thread.sleep(250)
                    }
                }
                check(loaded) { "Lanzou download page did not load inside the app" }
                onMain { updates.close() }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: saved HTML renders with inline CSS and JavaScript and no top toolbar.\n$rendered\n" +
                (live?.let { "PASS: live Lanzou folder found ${it.first.version}; Feishu log rendered natively; each reopening checks again; download page loaded inside the app.\n" } ?: "")) })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${error.stackTraceToString()}\n") })
        } finally {
            activity?.let { onMain { it.finish() } }
        }
    }
}
