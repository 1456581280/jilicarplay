// carlito | Durable cloud delivery of vehicle scan reports, with bounded storage and retries.
package com.shilapi.xcertplay.vehicleprobe

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.shilapi.xcertplay.DiagnosticReportUpload
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object VehicleReportDelivery {
    private fun directory(context: Context) = File(context.filesDir, "vehicle-report-outbox").apply {
        check(isDirectory || mkdirs()) { "Report queue unavailable" }
    }
    @Synchronized fun enqueue(context: Context, time: Long, report: String): File {
        require(report.toByteArray(Charsets.UTF_8).size <= ReportPropertyImporter.MAX_BYTES)
        val folder = directory(context)
        check(folder.listFiles().orEmpty().filter { it.name.endsWith(".json") }.sumOf { it.length() }
            < 50L * 1024 * 1024) { "Report queue full" }
        val target = File(folder, "$time.json")
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            val name = VehicleScanReport.fileName(time)
            val json = JSONObject().put("fileName", name).put("report", report)
            output.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output)
        } catch (error: Throwable) { atomic.failWrite(output); throw error }
        return target
    }

    @Synchronized fun deliver(context: Context, file: File, connectionReady: (HttpURLConnection) -> Unit = {}) {
        val marker = File(file.parentFile, file.nameWithoutExtension + ".uploaded")
        if (marker.isFile) { if (file.isFile) check(file.delete()); return }
        val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        val receipt = DiagnosticReportUpload.upload(json.getString("fileName"), "车辆属性扫描报告",
            json.getString("report"), ReportPropertyImporter.MAX_BYTES, connectionReady,
            submittedAt = file.nameWithoutExtension.toLong())
        val atomic = AtomicFile(marker)
        val output = atomic.startWrite()
        try { output.write(receipt.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
        check(file.delete()) { "Uploaded queue cleanup failed" }
        directory(context).listFiles().orEmpty().filter { it.extension == "uploaded" }
            .sortedByDescending { it.lastModified() }.drop(100).forEach { it.delete() }
    }

    fun status(context: Context, time: Long): String = runCatching {
        val folder = directory(context)
        when {
            File(folder, "$time.uploaded").isFile -> "报告已上传云端"
            File(folder, "$time.json").isFile -> "报告等待上传，联网后自动重试"
            else -> "报告尚未上传云端"
        }
    }.getOrDefault("报告尚未上传云端")

    fun retry(context: Context) {
        check(context.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(7402, ComponentName(context, VehicleReportUploadService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()) == JobScheduler.RESULT_SUCCESS)
    }
    fun pending(context: Context) = directory(context).listFiles().orEmpty()
        .filter { it.extension == "json" }.sortedBy { it.lastModified() }
}

class VehicleReportUploadService : JobService() {
    private class Run {
        @Volatile var cancelled = false
        @Volatile var connection: HttpURLConnection? = null
    }
    private var active: Run? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val run = Run().also { active = it }
        Thread({
            var retry = false
            try {
                for (file in VehicleReportDelivery.pending(this)) {
                    if (run.cancelled) break
                    VehicleReportDelivery.deliver(this, file) {
                        run.connection = it
                        if (run.cancelled) { it.disconnect(); throw InterruptedException("Upload cancelled") }
                    }
                }
            } catch (error: Exception) {
                retry = true
                Log.w("DiPlayVehicleProbe", "Vehicle report upload will retry", error)
            } finally {
                run.connection?.disconnect()
                if (!run.cancelled) jobFinished(params, retry)
            }
        }, "vehicle-report-upload").start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        active?.let { it.cancelled = true; it.connection?.disconnect() }; active = null
        return true
    }
}
