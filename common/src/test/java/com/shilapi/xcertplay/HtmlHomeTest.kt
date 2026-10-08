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
        assertTrue(runCatching { HtmlHome.save(activity, "中".repeat(HtmlHome.LIMIT / 2)) }.isFailure)
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
    @Test fun webViewBlocksNetworkFilesAndKeepsNativeRecoveryButtons() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val calls = mutableListOf<String>()
        val view = HtmlHomeView(activity, HtmlHome.sample(activity)) { calls += it }
        assertTrue(view.web.settings.blockNetworkLoads)
        assertFalse(view.web.settings.allowFileAccess)
        assertFalse(view.web.settings.allowContentAccess)
        assertFalse(view.web.settings.domStorageEnabled)
        descendants(view).filterIsInstance<Button>().forEach { it.performClick() }
        assertEquals(listOf("edit-home", "default-home", "settings"), calls)
        view.dispose()
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
        descendants(view).forEach { it.scrollIndicators = 0 }
        view.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 960, 540)
        if (name == "about-source") descendants(view).filterIsInstance<android.widget.ScrollView>().first().fullScroll(View.FOCUS_DOWN)
        val bitmap = android.graphics.Bitmap.createBitmap(960, 540, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/outputs/home-preview/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
