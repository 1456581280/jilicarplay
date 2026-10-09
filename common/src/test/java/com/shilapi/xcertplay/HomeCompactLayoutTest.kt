package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeCompactLayoutTest {
    @Test @Config(qualifiers = "zh-rCN-w960dp-h540dp-mdpi")
    fun landscapeKeepsTwoColumnsAndAllActions() { verify("landscape", true) }

    @Test @Config(qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
    fun portraitStacksWithoutClippingControls() { verify("portrait", false) }

    @Test @Config(qualifiers = "zh-rCN-w800dp-h360dp-mdpi")
    fun shortWindowScrollsInsteadOfHidingActions() { verify("short", true) }

    @Test @Config(qualifiers = "zh-rCN-w960dp-h540dp-mdpi")
    fun largeFontReflowsToOneColumn() { verify("large-font", false, 1.6f) }

    private fun verify(name: String, expectedColumns: Boolean, fontScale: Float = 1f) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        if (fontScale != 1f) {
            val config = android.content.res.Configuration(activity.resources.configuration).apply { this.fontScale = fontScale }
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { background = JourneyBackdrop(activity); isFillViewport = true; addView(content) }
        val calls = mutableListOf<String>()
        val home = WarmHomeScreen(activity)
        home.populate(content, "保持车机 Wi-Fi、蓝牙和手机 Wi-Fi 开启。", "0.2.15",
            { calls += "connect" }, { calls += "choose" }, { calls += "disconnect" },
            { calls += "usb" }, { calls += "settings" }, { calls += "update" }, { calls += "github" })
        assertEquals(expectedColumns, home.twoColumns)
        activity.setContentView(scroll)
        val width = activity.resources.configuration.screenWidthDp
        val height = activity.resources.configuration.screenHeightDp
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        scroll.layout(0, 0, width, height)
        val buttons = descendants(scroll).filterIsInstance<Button>().toList()
        assertEquals(5, buttons.size)
        buttons.forEach { button ->
            assertTrue("Minimum touch height", button.height >= 48)
            val rect = android.graphics.Rect(); button.getDrawingRect(rect); scroll.offsetDescendantRectToMyCoords(button, rect)
            assertTrue("Horizontal clipping: $name $rect", rect.left >= 0 && rect.right <= width)
            button.performClick()
        }
        assertEquals(setOf("connect", "choose", "disconnect", "usb", "settings"), calls.toSet())
        val labels = descendants(scroll).filterIsInstance<android.widget.TextView>().map { it.text.toString() }.toList()
        assertTrue(labels.contains("检查更新"))
        assertTrue(labels.none { it.contains("GitHub") || it.contains("0.2.15") })
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        scroll.draw(Canvas(bitmap))
        val output = File("build/outputs/home-preview/$name.png").apply { parentFile!!.mkdirs() }
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
