package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w960dp-h540dp-mdpi")
class ReleaseUpdatesTest {
    private lateinit var activity: Activity
    private lateinit var cm: ConnectivityManager
    private lateinit var source: FakeSource
    private lateinit var updates: ReleaseUpdates
    private class FakeSource : UpdateRepository {
        var checks = 0; var noteChecks = 0
        var release: AppRelease? = null
        var fail = false; var failNotes = false
        override fun latest(installed: String): AppRelease? { checks++; check(!fail); return release }
        override fun notes(): String { noteChecks++; check(!failNotes); return "修复连接问题。\n\n新增应用图标。" }
    }
    @Before fun setup() {
        activity = spy(Robolectric.buildActivity(Activity::class.java).setup().get())
        doReturn(true).`when`(activity).hasWindowFocus()
        cm = mock(ConnectivityManager::class.java)
        val network = mock(Network::class.java)
        `when`(cm.activeNetwork).thenReturn(network)
        val capabilities = mock(NetworkCapabilities::class.java)
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        `when`(cm.getNetworkCapabilities(network)).thenReturn(capabilities)
        doReturn(cm).`when`(activity).getSystemService(ConnectivityManager::class.java)
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        info.versionName = "0.2.16"; info.longVersionCode = 42
        shadowOf(activity.packageManager).installPackage(info)
        source = FakeSource()
        updates = ReleaseUpdates(activity, source, true) { it() }
    }
    private fun markNotesSeen() = activity.getSharedPreferences("release_updates", Context.MODE_PRIVATE)
        .edit().putLong("notes_seen_version_code", 42).apply()

    @Test fun installedVersionShowsNativeNotesOnceAndEachOpenStillChecks() {
        updates.onResume()
        val log = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(log.isShowing)
        assertTrue(descendants(log.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("修复连接问题。") })
        assertNull(shadowOf(activity).nextStartedActivity)
        log.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        updates.onPause(); updates.onResume()
        assertEquals(2, source.checks); assertEquals(1, source.noteChecks)
        assertFalse(log.isShowing)
        updates.close()
    }
    @Test fun skippingUpdateLeavesAppUsableAndNextOpeningChecksAgain() {
        markNotesSeen()
        var clicked = false
        val connect = Button(activity).apply { setOnClickListener { clicked = true } }
        activity.setContentView(connect)
        source.release = AppRelease("0.2.17", "https://1280ds.lanzoue.com/ifuture123")
        updates.onResume()
        val prompt = ShadowAlertDialog.getLatestAlertDialog()
        prompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        connect.performClick()
        assertTrue(clicked); assertNull(shadowOf(activity).nextStartedActivity)
        updates.onPause(); updates.onResume()
        assertEquals(2, source.checks)
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        updates.close()
    }
    @Test fun automaticFailureDoesNotOpenDialogOrDisableControls() {
        markNotesSeen(); source.fail = true
        val before = ShadowAlertDialog.getLatestAlertDialog()
        updates.onResume()
        assertSame(before, ShadowAlertDialog.getLatestAlertDialog())
        assertNull(shadowOf(activity).nextStartedActivity)
        updates.onPause(); updates.onResume()
        assertEquals(2, source.checks)
        updates.close()
    }
    @Test fun resumingWithAnOpenPromptDoesNotAskAgainAfterUserDeclines() {
        markNotesSeen()
        source.release = AppRelease("0.2.17", "https://1280ds.lanzoue.com/ifuture123")
        updates.onResume()
        val prompt = ShadowAlertDialog.getLatestAlertDialog()
        updates.onPause(); updates.onResume()
        assertEquals(2, source.checks)
        prompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        updates.close()
    }
    @Test fun resultBeforeWindowFocusIsKeptUntilAppCanShowIt() {
        markNotesSeen()
        source.release = AppRelease("0.2.17", "https://1280ds.lanzoue.com/ifuture123")
        doReturn(false).`when`(activity).hasWindowFocus()
        val before = ShadowAlertDialog.getLatestAlertDialog()
        updates.onResume()
        assertSame(before, ShadowAlertDialog.getLatestAlertDialog())
        doReturn(true).`when`(activity).hasWindowFocus()
        updates.onWindowFocusChanged()
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        updates.close()
    }
    @Test fun failedLogRemainsUnseenAndRetriesOnNextOpening() {
        source.failNotes = true
        updates.onResume()
        assertEquals(-1, activity.getSharedPreferences("release_updates", Context.MODE_PRIVATE).getLong("notes_seen_version_code", -1))
        source.failNotes = false
        updates.onPause(); updates.onResume()
        assertEquals(2, source.noteChecks)
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        updates.close()
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
