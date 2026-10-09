package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers
import java.io.File

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w960dp-h540dp-mdpi")
class HtmlHomeTest {
    @Test fun storageRoundTripsAndRejectsOversizeWithoutReplacingSavedHome() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        HtmlHome.reset(activity)
        assertNull(HtmlHome.read(activity))
        HtmlHome.save(activity, "<h1>你好</h1>")
        assertEquals("<h1>你好</h1>", HtmlHome.read(activity))
        assertTrue(runCatching { HtmlHome.save(activity, "<p>" + "中".repeat(HtmlHome.LIMIT / 3) + "</p>") }.isFailure)
        assertEquals("<h1>你好</h1>", HtmlHome.read(activity))
        val unrelated = File(activity.filesDir, "keep.txt").apply { writeText("keep") }
        HtmlHome.reset(activity)
        assertNull(HtmlHome.read(activity)); assertEquals("keep", unrelated.readText())
    }
    @Test fun onlyAllowlistedMainFrameUserClicksAreAccepted() {
        HtmlHome.actions.forEach { assertEquals(it, HtmlHome.action(Uri.parse("diplay://action/$it"), true, true)) }
        listOf("https://example.com", "diplay://action/connect?url=https://example.com", "diplay://action/shell", "diplay://action:80/connect", "diplay://user@action/connect").forEach {
            assertNull(HtmlHome.action(Uri.parse(it), true, true))
        }
        assertNull(HtmlHome.action(Uri.parse("diplay://action/connect"), false, true))
        assertNull(HtmlHome.action(Uri.parse("diplay://action/connect"), true, false))
    }
    @Test fun webViewBlocksNetworkFilesAndUsesTheWholeHome() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val calls = mutableListOf<String>()
        val view = HtmlHomeView(activity, HtmlHome.sample(activity)) { calls += it }
        assertTrue(view.web.settings.blockNetworkLoads)
        assertFalse(view.web.settings.allowFileAccess)
        assertFalse(view.web.settings.allowContentAccess)
        assertFalse(view.web.settings.domStorageEnabled)
        assertTrue(descendants(view).filterIsInstance<Button>().none())
        // Robolectric does not measure WebView content; the device smoke test checks actual bounds.
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, view.web.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, view.web.layoutParams.height)
        assertTrue(calls.isEmpty())
        view.dispose()
    }
    @Test fun incompletePastesDoNotReplaceSavedHome() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        HtmlHome.save(activity, "<h1>原首页</h1>")
        HtmlHome.edit(activity) {}
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val source = "<html><head><style>" + "    /* 格式化代码 */\n".repeat(20000)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("html", source))
        descendants(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text == "粘贴 HTML" }.performClick()
        val editor = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        assertEquals(source, editor.text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertEquals("<h1>原首页</h1>", HtmlHome.read(activity))
        editor.setText("<!doctype html><html><head><style>body { color: white;")
        descendants(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text.startsWith("预览") }.performClick()
        assertSame(dialog, ShadowAlertDialog.getLatestAlertDialog())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertEquals("<h1>原首页</h1>", HtmlHome.read(activity))
        dialog.dismiss()
    }
    @Test fun fencedHtmlIsSavedWithoutMarkdownAndUtf8LimitIsChecked() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val html = HtmlHome.sample(activity)
        HtmlHome.save(activity, "```html\n$html\n```")
        assertEquals(html, HtmlHome.read(activity))
        listOf("只有 CSS", "<style>body { color: white;", "<html><head></head></html>").forEach {
            assertTrue(runCatching { HtmlHome.prepare(it) }.isFailure)
        }
        val exactLimit = "<p>" + "a".repeat(HtmlHome.LIMIT - 7) + "</p>"
        assertEquals(exactLimit, HtmlHome.prepare(exactLimit))
        assertTrue(runCatching { HtmlHome.prepare(exactLimit + "中") }.isFailure)
    }
    @Test fun largeFormattedPastePreviewSaveAndReopenPreserveAllWhitespace() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val source = "\n  <html>\n    <head><style>\n" +
            "        /* 中文注释：保留换行和缩进 */\r\n".repeat(12000) +
            "    </style></head>\n    <body><pre>  第一行\n    第二行  </pre>" +
            "<script>const text = `  第一行\n    第二行  `;</script></body>\n  </html>\n\n"
        assertTrue(source.toByteArray(Charsets.UTF_8).size > 262144)
        assertEquals(source, HtmlHome.prepare("```html\n$source\n```"))
        HtmlHome.edit(activity) {}
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("html", source))
        descendants(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text == "粘贴 HTML" }.performClick()
        val editor = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        assertEquals(source, editor.text.toString())
        descendants(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text.startsWith("预览") }.performClick()
        val preview = ShadowAlertDialog.getLatestAlertDialog()
        assertNotSame(dialog, preview)
        preview.dismiss()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(source, HtmlHome.read(activity))
        HtmlHome.edit(activity) {}
        val reopened = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(source, descendants(reopened.window!!.decorView).filterIsInstance<EditText>().single().text.toString())
        reopened.dismiss()
        HtmlHome.reset(activity)
    }
    @Test fun backFromCustomHomeOpensSettingsForRecovery() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        HtmlHome.save(controller.get(), "<h1>自定义</h1>")
        val activity = controller.setup().get()
        assertNotNull(ReflectionHelpers.getField<HtmlHomeView?>(activity, "htmlHome"))
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
        assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>().any { it.text == "编辑首页 / HTML" })
        HtmlHome.reset(activity)
        controller.pause().stop().destroy()
    }
    @Test @Config(qualifiers = "zh-rCN-w800dp-h360dp-mdpi")
    fun shortEditorKeepsPreviewAndSaveVisible() { verifyEditorLayout("html-editor-short") }

    @Test @Config(qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
    fun portraitEditorKeepsPreviewAndSaveVisible() { verifyEditorLayout("html-editor-portrait") }

    private fun verifyEditorLayout(name: String) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        HtmlHome.edit(activity) {}
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val decor = dialog.window!!.decorView
        screenshot(decor, name)
        val editor = descendants(decor).filterIsInstance<EditText>().single()
        assertTrue("Editor must have room to scroll", editor.height >= 48)
        val preview = descendants(decor).filterIsInstance<Button>().single { it.text.startsWith("预览") }
        for (control in listOf(preview, dialog.getButton(AlertDialog.BUTTON_POSITIVE))) {
            val rect = android.graphics.Rect(); control.getDrawingRect(rect)
            (decor as ViewGroup).offsetDescendantRectToMyCoords(control, rect)
            assertTrue("Control clipped: $rect in ${decor.width}x${decor.height}", rect.top >= 0 && rect.bottom <= decor.height)
        }
        assertTrue(editor.parent is android.widget.LinearLayout)
        dialog.dismiss()
    }
    @Test fun legacyIncompleteHomeOpensSettingsAndKeepsSourceForRepair() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val source = "<html><head><style>body { color: white;"
        File(controller.get().filesDir, "custom-home.html").writeText(source)
        val activity = controller.setup().get()
        assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
        assertNull(ReflectionHelpers.getField<HtmlHomeView?>(activity, "htmlHome"))
        assertEquals(source, HtmlHome.read(activity))
        HtmlHome.reset(activity)
        controller.pause().stop().destroy()
    }
    @Test fun editorSavesCodeAndResetRestoresDefault() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var applied = 0
        HtmlHome.edit(activity) { applied++ }
        val editorDialog = ShadowAlertDialog.getLatestAlertDialog()
        screenshot(editorDialog.window!!.decorView, "html-editor")
        descendants(editorDialog.window!!.decorView).filterIsInstance<EditText>().single().setText("<h1>自定义</h1>")
        editorDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, applied); assertEquals("<h1>自定义</h1>", HtmlHome.read(activity))
        HtmlHome.edit(activity) { applied++ }
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(2, applied); assertNull(HtmlHome.read(activity))
    }
    @Test fun aboutHasAllThreeCorrectSourceLinks() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "page", "about")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        screenshot(activity.window.decorView, "about-source")
        val links = listOf("开源项目：" to "https://github.com/shihabal3amri/DiPlay", "上游二次开发地址：" to "https://github.com/carlito12345/DiPlay", "本项目二次开发地址：" to "https://github.com/1456581280/jilicarplay")
        links.forEach { (prefix, url) ->
            descendants(activity.window.decorView).filterIsInstance<Button>().single { it.text.startsWith(prefix) }.performClick()
            assertEquals(url, shadowOf(activity).nextStartedActivity.data.toString())
        }
        controller.pause().stop().destroy()
    }
    private fun screenshot(view: View, name: String) {
        val width = view.resources.configuration.screenWidthDp
        val height = view.resources.configuration.screenHeightDp
        descendants(view).forEach { it.scrollIndicators = 0 }
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        if (name == "about-source") descendants(view).filterIsInstance<android.widget.ScrollView>().first().fullScroll(View.FOCUS_DOWN)
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/outputs/home-preview/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
