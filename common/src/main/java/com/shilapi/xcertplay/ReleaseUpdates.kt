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
import android.widget.Toast
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.host.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class UpdatePackageProvider : FileProvider()

internal data class AppRelease(val version: String, val url: String, val size: Long, val digest: String, val notes: String)

/** Only fetches public release metadata. No vehicle, phone or diagnostic data is sent. */
internal object ReleasePolicy {
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    private fun numbers(version: String): List<Long>? {
        if (!version.matches(Regex("v?\\d+\\.\\d+\\.\\d+(?:-[A-Za-z0-9.]+)?"))) return null
        return version.removePrefix("v").substringBefore('-').split('.').map { it.toLongOrNull() ?: return null }
    }
    fun newer(candidate: String, installed: String): Boolean {
        val next = numbers(candidate) ?: return false
        val current = numbers(installed) ?: return false
        for (i in next.indices) if (next[i] != current[i]) return next[i] > current[i]
        return '-' in installed && '-' !in candidate
    }
    fun parse(json: JSONObject, installed: String): AppRelease? {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val version = json.optString("tag_name").removePrefix("v")
        if (!version.matches(Regex("\\d+\\.\\d+\\.\\d+")) || !newer(version, installed)) return null
        val assets = json.optJSONArray("assets") ?: return null
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name") == "JiliCarPlay-$version.apk" && it.optString("state") == "uploaded" }
        if (matches.size != 1) return null
        val asset = matches.single()
        val url = asset.optString("browser_download_url")
        val parsed = runCatching { URL(url) }.getOrNull() ?: return null
        if (parsed.protocol != "https" || parsed.host != "github.com" || parsed.userInfo != null ||
            parsed.port !in listOf(-1, 443) || !parsed.path.startsWith("/1456581280/jilicarplay/releases/download/")) return null
        val size = asset.optLong("size")
        val digest = asset.optString("digest").lowercase()
        if (size !in 1..MAX_APK_BYTES || !digest.matches(Regex("sha256:[a-f0-9]{64}"))) return null
        return AppRelease(version, url, size, digest.removePrefix("sha256:"), json.optString("body").take(2000))
    }
    fun verifyBytes(file: File, release: AppRelease) {
        check(file.length() == release.size)
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        check(hash.digest().joinToString("") { "%02x".format(it) } == release.digest)
    }
}

internal class ReleaseUpdates(private val activity: Activity) {
    companion object {
        const val REPOSITORY_URL = "https://github.com/1456581280/jilicarplay"
        const val API_URL = "https://api.github.com/repos/1456581280/jilicarplay/releases/latest"
        private val checking = AtomicBoolean(false)
        private val downloading = AtomicBoolean(false)
        private const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L
        private const val RETRY_INTERVAL = 15 * 60 * 1000L
    }
    private var downloadDialog: AlertDialog? = null
    private var cancelled = AtomicBoolean(false)
    @Volatile private var activeDownload: HttpURLConnection? = null
    fun close() { cancelled.set(true); activeDownload?.disconnect(); downloadDialog?.dismiss(); downloadDialog = null }
    private val prefs get() = activity.getSharedPreferences("release_updates", Context.MODE_PRIVATE)
    private val apk get() = File(activity.cacheDir, "updates/release.apk")
    private val alive get() = !activity.isFinishing && !activity.isDestroyed
    fun onResume(allowPrompt: Boolean) {
        if (prefs.getBoolean("install_requested", false) && activity.packageManager.canRequestPackageInstalls()) {
            prefs.edit().remove("install_requested").apply()
            if (apk.isFile) install()
        } else if (allowPrompt) checkForUpdate()
    }
    fun checkForUpdate(manual: Boolean = false) {
        if (activity.packageName != "com.shihab.diplay") return
        val cm = activity.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        if (network == null || cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            if (manual) toast(R.string.update_unavailable)
            return
        }
        val now = System.currentTimeMillis()
        if (!manual && now < prefs.getLong("next_check", 0)) return
        if (!checking.compareAndSet(false, true)) return
        prefs.edit().putLong("next_check", now + RETRY_INTERVAL).apply()
        if (manual) toast(R.string.update_checking)
        Thread({
            val result = runCatching {
                val connection = connection(API_URL)
                try {
                    check(connection.responseCode == 200)
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break
                            check(output.size() + n <= 256 * 1024); output.write(buffer, 0, n) }
                        output.toByteArray()
                    }
                    check(bytes.size <= 256 * 1024)
                    val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
                    ReleasePolicy.parse(JSONObject(bytes.toString(Charsets.UTF_8)), installed)
                } finally { connection.disconnect() }
            }
            checking.set(false)
            if (result.isSuccess) prefs.edit().putLong("next_check", now + CHECK_INTERVAL).apply()
            activity.runOnUiThread {
                if (!alive || (!manual && !activity.hasWindowFocus()) || (!manual && CarPlayBackgroundSession.hasSession())) return@runOnUiThread
                val release = result.getOrNull()
                when {
                    result.isFailure -> if (manual) toast(R.string.update_unavailable)
                    release == null -> if (manual) toast(R.string.update_current)
                    manual || prefs.getString("dismissed", null) != release.version ||
                        now - prefs.getLong("dismissed_at", 0) >= 24 * 60 * 60 * 1000L -> prompt(release)
                }
            }
        }, "github-release-check").start()
    }
    private fun prompt(release: AppRelease) {
        AlertDialog.Builder(activity).setTitle(activity.getString(R.string.update_available, release.version))
            .setMessage(activity.getString(R.string.update_prompt) + "\n\n" + release.notes)
            .setNegativeButton(R.string.update_later) { _, _ -> prefs.edit().putString("dismissed", release.version).putLong("dismissed_at", System.currentTimeMillis()).apply() }
            .setPositiveButton(R.string.update_download) { _, _ -> download(release) }.show()
    }
    private fun connection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "JiliCarPlay-UpdateChecker")
    }
    private fun download(release: AppRelease) {
        if (!downloading.compareAndSet(false, true)) { toast(R.string.update_downloading); return }
        cancelled = AtomicBoolean(false)
        val cancellation = cancelled
        val progress = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        downloadDialog = AlertDialog.Builder(activity).setTitle(R.string.update_downloading)
            .setView(progress).setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancellation.set(true); activeDownload?.disconnect() }.show()
        Thread({
            val result = runCatching {
                val folder = apk.parentFile!!; check(folder.isDirectory || folder.mkdirs())
                val partial = File(folder, "release.part.apk")
                var next = release.url
                try {
                    var complete = false
                    for (redirect in 0..5) {
                        val parsed = URL(next)
                        check(parsed.protocol == "https" && parsed.userInfo == null && parsed.port in listOf(-1,443) &&
                            parsed.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
                        check(!cancellation.get())
                        val conn = connection(next)
                        activeDownload = conn
                        try {
                            val code = conn.responseCode
                            if (code in listOf(301,302,303,307,308)) {
                                next = URL(parsed, conn.getHeaderField("Location") ?: error("Missing redirect")).toString()
                                continue
                            }
                            check(code == 200)
                            var total = 0L
                            var lastPercent = -1
                            conn.inputStream.use { input -> partial.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    check(!cancellation.get())
                                    val n = input.read(buffer); if (n < 0) break
                                    total += n; check(total <= release.size && total <= ReleasePolicy.MAX_APK_BYTES)
                                    output.write(buffer, 0, n)
                                    val percent = (total * 100 / release.size).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        activity.runOnUiThread { if (alive) progress.progress = percent }
                                    }
                                }
                            } }
                            ReleasePolicy.verifyBytes(partial, release)
                            verifyPackage(partial, release.version)
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
                downloadDialog?.dismiss(); downloadDialog = null
                if (cancellation.get()) return@runOnUiThread
                if (result.isFailure) toast(R.string.update_failed)
                else AlertDialog.Builder(activity).setTitle(R.string.update_ready)
                    .setMessage(R.string.update_install_message)
                    .setNegativeButton(R.string.update_later, null)
                    .setPositiveButton(R.string.update_install) { _, _ -> install() }.show()
            }
        }, "github-release-download").start()
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
