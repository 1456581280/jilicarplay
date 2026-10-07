package com.shilapi.xcertplay

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaScannerConnection
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Saves an app-owned report without depending on an OEM's document-picker activity. */
// carlito | Share the existing provider-free exporter with the vehicle scan process.
object DiagnosticExportStore {
    data class SavedReport(
        val uri: Uri,
        val savedToDownloads: Boolean = false,
        val savedInApp: Boolean = false,
        val savedPath: String? = null,
    )

    /** Android 9 and OEMs without working Downloads storage can still export privately. */
    fun saveWithoutPicker(context: Context, fileName: String, report: String, shareable: Boolean = true): SavedReport {
        // carlito | Separate fallback folders prevent scan retention from pruning connection logs.
        val reportDirectory = if (fileName.startsWith("DiPlay-Vehicle-") || fileName.startsWith("车辆扫描-"))
            "vehicle-reports" else "diagnostic-reports"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return SavedReport(saveToDownloads(context.contentResolver, fileName, report), savedToDownloads = true)
            } catch (error: Exception) {
                Log.w("DiPlayVehicleProbe", "Downloads provider export failed; retaining report locally", error)
                // Preserve the report even when the OEM's public storage provider is absent.
            }
        }
        // carlito | Android 9 writes public Downloads after the user's storage grant.
        if (Build.VERSION.SDK_INT <= 28 && context.checkSelfPermission(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                @Suppress("DEPRECATION")
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DiPlay")
                return saveInDirectory(context, directory, fileName, report, publicDownload = true, shareable = shareable)
            } catch (error: Exception) {
                Log.w("DiPlayVehicleProbe", "Public Downloads file export failed; retaining report locally", error)
            }
        }
        if (Build.VERSION.SDK_INT <= 28) Log.w("DiPlayVehicleProbe", "Public Downloads unavailable; storage permission or volume must be checked")
        try {
            // Use Android's package-specific directory, including debug application IDs.
            // No storage permission or document-picker activity is needed.
            val externalFiles = context.getExternalFilesDir(null)
            if (externalFiles != null) {
                return saveInDirectory(context, File(externalFiles, reportDirectory), fileName, report, shareable = shareable)
            }
        } catch (_: Exception) {
            // A missing, read-only or full external volume must not prevent export.
        }
        return saveInDirectory(context, File(context.filesDir, reportDirectory), fileName, report, savedInApp = true, shareable = shareable)
    }

    private fun saveInDirectory(
        context: Context,
        directory: File,
        fileName: String,
        report: String,
        savedInApp: Boolean = false,
        publicDownload: Boolean = false,
        shareable: Boolean = true,
    ): SavedReport {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Report storage is unavailable")
        // Each export has a new URI: an earlier share grant cannot read a later report.
        val file = File.createTempFile(fileName.removeSuffix(".txt") + "-", ".txt", directory)
        try {
            // carlito | Saving a vehicle report must not require an OEM sharing provider.
            val bytes = report.toByteArray(Charsets.UTF_8)
            file.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            if (file.length() != bytes.size.toLong()) throw IOException("Report write incomplete")
            val uri = if (shareable) FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
                else Uri.fromFile(file) // Local location only; never pass this URI to another application.
            if (publicDownload) runCatching {
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("text/plain"), null)
            }
            // Retain only the newest eight reports; never prune the export being returned.
            if (!publicDownload) directory.listFiles()?.filter { it != file && it.isFile }
                ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
            return SavedReport(uri, savedToDownloads = publicDownload, savedInApp = savedInApp, savedPath = if (savedInApp) null else file.absolutePath)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(resolver: ContentResolver, fileName: String, report: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DiPlay")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads could not create the report")
        try {
            write(resolver, uri, report)
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Downloads could not publish the report")
            return uri
        } catch (error: Exception) {
            // Only remove the entry created by this call; never leave a partial report behind.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    fun write(resolver: ContentResolver, uri: Uri, report: String) {
        val stream = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Report destination is unavailable")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
    }
}
