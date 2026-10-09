package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.host.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class UpdatePackageProvider : FileProvider()

/** Optional updates. Neither an unavailable server nor declining an update gates app functions. */
internal class ReleaseUpdates(
    private val activity: Activity,
    private val source: UpdateRepository = UpdateSource(),
    private val enabled: Boolean = activity.packageName == "com.shihab.diplay",
    private val work: (() -> Unit) -> Unit = { Thread(it, "app-update-check").start() },
) {
    companion object { const val REPOSITORY_URL = "https://github.com/1456581280/jilicarplay" }
    private val checking = AtomicBoolean(false)
    private val downloading = AtomicBoolean(false)
    private var cancelled = AtomicBoolean(false)
    @Volatile private var activeDownload: HttpURLConnection? = null
    private var dialog: AlertDialog? = null
    private var foreground = false
    private var closed = false
    private var pendingRelease: AppRelease? = null
    private var pendingNotes: String? = null
    private var promptedVersion: String? = null
    private val prefs get() = activity.getSharedPreferences("release_updates", Context.MODE_PRIVATE)
    private val apk get() = File(activity.cacheDir, "updates/release.apk")
    private val alive get() = !closed && !activity.isFinishing && !activity.isDestroyed
    private val installed get() = activity.packageManager.getPackageInfo(activity.packageName, 0)
    private val needsNotes get() = prefs.getLong("notes_seen_version_code", -1) != installed.longVersionCode

    fun close() {
        closed = true; cancelled.set(true); activeDownload?.disconnect()
        dialog?.dismiss(); dialog = null
    }
    fun onPause() { foreground = false }
    fun onResume() {
        if (!enabled) return
        foreground = true; pendingRelease = null
        if (dialog?.isShowing != true) promptedVersion = null
        if (prefs.getBoolean("install_requested", false)) {
            prefs.edit().remove("install_requested").apply()
            if (activity.packageManager.canRequestPackageInstalls() && apk.isFile) install()
        }
        checkForUpdate()
    }
    fun onWindowFocusChanged() { presentPending() }

    fun checkForUpdate(manual: Boolean = false) {
        if (!enabled || !alive) return
        val cm = activity.getSystemService(ConnectivityManager::class.java)
        if (cm?.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } != true) {
            if (manual) toast(R.string.update_unavailable)
            return
        }
        if (!checking.compareAndSet(false, true)) return
        if (manual) toast(R.string.update_checking)
        val version = installed.versionName.orEmpty()
        val showInstalledNotes = needsNotes
        work {
            val release = runCatching { source.latest(version) }
            // Folder and log availability are independent; either request can fail without blocking use.
            val notes = if (showInstalledNotes || release.getOrNull() != null) runCatching { source.notes() }.getOrNull() else null
            activity.runOnUiThread {
                checking.set(false)
                if (!alive) return@runOnUiThread
                if (notes != null) {
                    if (showInstalledNotes) pendingNotes = notes
                }
                if (release.isSuccess) pendingRelease = release.getOrNull()?.copy(notes = notes)
                if (manual) {
                    promptedVersion = null
                    when {
                        release.isFailure -> toast(R.string.update_unavailable)
                        release.getOrNull() == null -> toast(R.string.update_current)
                    }
                }
                presentPending()
            }
        }
    }

    private fun presentPending() {
        if (!alive || !foreground || !activity.hasWindowFocus() || dialog?.isShowing == true || CarPlayBackgroundSession.hasSession()) return
        pendingNotes?.let { notes ->
            pendingNotes = null
            val text = TextView(activity).apply {
                this.text = notes; textSize = 17f; setTextIsSelectable(true)
                val pad = (24 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
            }
            val log = AlertDialog.Builder(activity).setTitle(activity.getString(R.string.update_notes_title, installed.versionName.orEmpty()))
                .setView(ScrollView(activity).apply { addView(text) }).setPositiveButton(R.string.update_notes_close, null).create()
            show(log)
            prefs.edit().putLong("notes_seen_version_code", installed.longVersionCode).apply()
            return
        }
        val release = pendingRelease ?: return
        if (promptedVersion == release.version) return
        promptedVersion = release.version
        val notes = release.notes
        show(AlertDialog.Builder(activity).setTitle(activity.getString(R.string.update_available, release.version))
            .setMessage(activity.getString(R.string.update_prompt) + (notes?.let { "\n\n$it" } ?: ""))
            .setNegativeButton(R.string.update_later, null)
            .setPositiveButton(R.string.update_download) { _, _ -> openDownload(release) }.create())
    }

    private fun show(next: AlertDialog) {
        dialog = next
        next.setOnDismissListener {
            if (dialog === next) { dialog = null; presentPending() }
        }
        next.show()
    }

    /** If Lanzou asks for download verification, the user completes it inside this app. */
    private fun openDownload(release: AppRelease) {
        if (!alive) return
        val web = WebView(activity)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = false
            allowFileAccess = false; allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = ReleasePolicy.USER_AGENT
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !ReleasePolicy.downloadPageAllowed(request.url.toString())
            @Deprecated("Legacy navigation")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = !ReleasePolicy.downloadPageAllowed(url)
        }
        val browser = AlertDialog.Builder(activity).setTitle(activity.getString(R.string.update_available, release.version))
            .setView(web).setNegativeButton(android.R.string.cancel, null).create()
        dialog = browser
        browser.setOnDismissListener {
            web.stopLoading(); web.destroy()
            if (dialog === browser) { dialog = null; presentPending() }
        }
        web.setDownloadListener { url, agent, _, _, _ ->
            if (!ReleasePolicy.downloadPageAllowed(url)) { toast(R.string.update_failed); return@setDownloadListener }
            browser.dismiss()
            download(release, url, agent)
        }
        browser.show()
        browser.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        web.loadUrl(release.url)
    }

    private fun download(release: AppRelease, initialUrl: String, userAgent: String) {
        if (!downloading.compareAndSet(false, true)) return
        cancelled = AtomicBoolean(false)
        val cancellation = cancelled
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        val progressDialog = AlertDialog.Builder(activity).setTitle(R.string.update_downloading).setView(progress).setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancellation.set(true); activeDownload?.disconnect() }.create()
        show(progressDialog)
        Thread({
            val result = runCatching {
                val folder = apk.parentFile!!; check(folder.isDirectory || folder.mkdirs())
                val partial = File(folder, "release.part.apk")
                try {
                    var next = initialUrl
                    var complete = false
                    for (redirect in 0..5) {
                        check(!cancellation.get() && ReleasePolicy.downloadPageAllowed(next))
                        val parsed = URL(next)
                        val conn = parsed.openConnection() as HttpURLConnection
                        activeDownload = conn
                        try {
                            conn.connectTimeout = 15_000; conn.readTimeout = 30_000; conn.instanceFollowRedirects = false
                            conn.setRequestProperty("User-Agent", userAgent)
                            CookieManager.getInstance().getCookie(next)?.let { conn.setRequestProperty("Cookie", it) }
                            val code = conn.responseCode
                            if (code in listOf(301, 302, 303, 307, 308)) {
                                next = URL(parsed, conn.getHeaderField("Location") ?: error("Missing redirect")).toString(); continue
                            }
                            check(code == 200 && !conn.contentType.orEmpty().contains("text/html", true))
                            val expected = conn.contentLengthLong
                            check(expected <= ReleasePolicy.MAX_APK_BYTES)
                            var total = 0L; var lastPercent = -1
                            conn.inputStream.use { input -> partial.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    check(!cancellation.get())
                                    val n = input.read(buffer); if (n < 0) break
                                    total += n; check(total <= ReleasePolicy.MAX_APK_BYTES && (expected < 0 || total <= expected))
                                    output.write(buffer, 0, n)
                                    if (expected > 0) {
                                        val percent = (total * 100 / expected).toInt()
                                        if (percent != lastPercent) {
                                            lastPercent = percent
                                            activity.runOnUiThread { if (alive) { progress.isIndeterminate = false; progress.progress = percent } }
                                        }
                                    }
                                }
                            } }
                            check(total > 0 && (expected < 0 || total == expected))
                            verifyPackage(partial, release.version)
                            check(!cancellation.get())
                            if (apk.exists()) check(apk.delete())
                            check(partial.renameTo(apk)); complete = true; break
                        } finally { conn.disconnect(); activeDownload = null }
                    }
                    check(complete)
                } finally { partial.delete() }
            }
            downloading.set(false)
            activity.runOnUiThread {
                if (!alive) return@runOnUiThread
                progressDialog.dismiss()
                if (cancellation.get()) return@runOnUiThread
                if (result.isFailure) toast(R.string.update_failed)
                else show(AlertDialog.Builder(activity).setTitle(R.string.update_ready).setMessage(R.string.update_install_message)
                    .setNegativeButton(R.string.update_later, null).setPositiveButton(R.string.update_install) { _, _ -> install() }.create())
            }
        }, "app-update-download").start()
    }

    private fun verifyPackage(file: File, expectedVersion: String? = null) {
        val pm = activity.packageManager
        val current = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val candidate = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Invalid APK")
        check(candidate.packageName == activity.packageName && candidate.longVersionCode > current.longVersionCode)
        if (expectedVersion != null) check(candidate.versionName == expectedVersion)
        val currentSigners = current.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val newSigners = candidate.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        check(currentSigners.isNotEmpty() && currentSigners == newSigners)
    }
    private fun install() {
        runCatching {
            verifyPackage(apk)
            if (!activity.packageManager.canRequestPackageInstalls()) {
                prefs.edit().putBoolean("install_requested", true).apply()
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            } else {
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
                activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }.onFailure { toast(R.string.update_failed) }
    }
    private fun toast(message: Int) { if (alive) Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
}
