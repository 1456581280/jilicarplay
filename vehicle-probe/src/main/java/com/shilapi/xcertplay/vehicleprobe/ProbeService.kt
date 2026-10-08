// carlito | DiPlay vehicle probe integration. Adapted from GD, GPL-3.0.
package com.shilapi.xcertplay.vehicleprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import com.shilapi.xcertplay.DiagnosticExportStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.shilapi.xcertplay.EXTRA_HIDE_TOP_BAR
import com.shilapi.xcertplay.EXTRA_HIDE_BOTTOM_BAR

internal data class ProbeState(
    val loading: Boolean = true,
    val running: Boolean = false,
    val summary: ProbeSummary = ProbeSummary(),
    val lastScan: Long = 0,
    val error: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val errorMessage: String? = null,
    val stage: String = "正在扫描车辆",
    val application: ProfileApplication? = null,
)

/** The separate GD APK scans hardware; this service stores and exports the returned report. */
class ProbeService : Service() {
    inner class LocalBinder : Binder() { val service: ProbeService get() = this@ProbeService }
    private val binder = LocalBinder()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    @Volatile internal var state = ProbeState()
        private set

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("vehicle_scan", "车辆扫描", NotificationManager.IMPORTANCE_LOW))
        }
        worker.execute {
            val previous = runCatching {
                val text = openReport().bufferedReader(Charsets.UTF_8).use { it.readText() }
                ProbeState(loading = false, summary = ProbeSummary.parse(text),
                    lastScan = ProbeReports.file(applicationContext).lastModified(),
                    application = AutomaticVehicleProfile.load(applicationContext, ProbeReports.file(applicationContext).lastModified()))
            }.getOrDefault(ProbeState(loading = false))
            main.post {
                if (!destroyed) state = if (state.running) state.copy(
                    summary = previous.summary, lastScan = previous.lastScan) else previous
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (state.running) return START_NOT_STICKY
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, VehicleProbeActivity::class.java)
                .putExtra(EXTRA_HIDE_TOP_BAR, intent?.getBooleanExtra(EXTRA_HIDE_TOP_BAR, true) ?: true)
                .putExtra(EXTRA_HIDE_BOTTOM_BAR, intent?.getBooleanExtra(EXTRA_HIDE_BOTTOM_BAR, true) ?: true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "vehicle_scan")
            else Notification.Builder(this)
        startForeground(7401, notification.setSmallIcon(R.drawable.ic_probe)
            .setContentTitle("正在扫描车辆").setContentText("正在扫描并应用可用属性")
            .setContentIntent(open).setOngoing(true).build())
        state = state.copy(loading = false, running = true, error = false, errorMessage = null, completed = 0, total = 0, stage = "正在扫描车辆", application = null)
        worker.execute {
            var result: Pair<ProbeSummary, Long>? = null
            var failure: String? = null
            var application: ProfileApplication? = null
            fun progress(stage: String) { main.post { if (!destroyed) state = state.copy(stage = stage) } }
            try {
                VehicleBridgeClient(applicationContext).use { client ->
                    val rawReport = client.scanReport()
                    // carlito | Scan metadata is captured on this head unit, separate from runtime diagnostics.
                    val configuredModel = runCatching { client.activeProfile()?.model }.getOrNull()
                    val detectedModel = runCatching { AutomaticVehicleProfile.detectedModel(client.presets())?.model }.getOrNull()
                    val text = VehicleScanReport.format(applicationContext, rawReport,
                        configuredModel ?: detectedModel,
                        if (configuredModel != null) "saved vehicle profile" else "head-unit model matched preset")
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Vehicle scan cancelled")
                    val summary = ProbeSummary.parse(text)
                    ProbeReports.save(applicationContext, text)
                    val time = ProbeReports.file(applicationContext).lastModified()
                    result = summary to time
                    // carlito | Profile application errors never discard a successful scan report.
                    application = try { AutomaticVehicleProfile.apply(applicationContext, client, text, time, ::progress) }
                        catch (error: Exception) {
                            Log.e("DiPlayVehicleProbe", "Profile application failed", error)
                            val active = runCatching { client.activeProfile() }.getOrNull()
                            ProfileApplication(time, configured = active?.bindings?.size ?: 0,
                                message = "扫描已完成，属性自动应用未完成。已有配置保留，请检查车辆数据桥后重试。")
                        }
                    progress("正在保存报告")
                    val filename = VehicleScanReport.fileName(time)
                    val exported = runCatching { DiagnosticExportStore.saveWithoutPicker(applicationContext, filename, text, shareable = false) }
                        .onFailure { Log.w("DiPlayVehicleProbe", "Public vehicle report export failed", it) }.getOrNull()
                    Log.i("DiPlayVehicleProbe", "Vehicle report export downloads=${exported?.savedToDownloads == true} " +
                        "file=${exported?.savedPath ?: "app_storage"}")
                    val location = when {
                        exported?.savedToDownloads == true -> "报告已保存到“下载/DiPlay”。\n文件：${exported.savedPath?.let { File(it).name } ?: filename}"
                        exported != null -> "报告已保存在 DiPlay，允许存储权限后可再次导出到下载目录。"
                        else -> "报告已保存在应用内，导出未完成，可点击“导出扫描报告”重试。"
                    }
                    application = application?.let { it.copy(message = it.message + "\n" + location) }
                    application?.let { outcome ->
                        runCatching { AutomaticVehicleProfile.save(applicationContext, outcome) }.onFailure {
                            Log.e("DiPlayVehicleProbe", "Application result save failed", it)
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e("DiPlayVehicleProbe", "Scan failed", error)
                failure = if (error is SecurityException) "车辆数据桥未授权当前 DiPlay 签名，请安装正式签名版本"
                    else "车辆数据桥扫描失败，请检查安装与车辆访问授权后重试"
            } finally {
                val completed = result
                main.post {
                    if (!destroyed) {
                        state = if (completed == null) state.copy(loading = false, running = false, error = true, errorMessage = failure)
                            else ProbeState(loading = false, summary = completed.first, lastScan = completed.second, application = application)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    internal fun openReport(): InputStream = ProbeReports.open(applicationContext)

    override fun onDestroy() {
        destroyed = true
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

internal object ProbeReports {
    fun file(context: Context): File = File(context.filesDir, "diplay-vehicle-report.txt")
    @Synchronized fun open(context: Context): InputStream = AtomicFile(file(context)).openRead()

    @Synchronized fun save(context: Context, text: String) {
        val report = AtomicFile(file(context))
        val stream = report.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            report.finishWrite(stream)
        } catch (error: Throwable) {
            report.failWrite(stream)
            throw error
        }
    }
}
