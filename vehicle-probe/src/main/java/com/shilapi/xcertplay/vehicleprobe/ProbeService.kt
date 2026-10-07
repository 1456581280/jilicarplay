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

internal data class ProbeState(
    val loading: Boolean = true,
    val running: Boolean = false,
    val summary: ProbeSummary = ProbeSummary(),
    val lastScan: Long = 0,
    val error: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val errorMessage: String? = null,
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
                    lastScan = ProbeReports.file(applicationContext).lastModified())
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
            Intent(this, VehicleProbeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "vehicle_scan")
            else Notification.Builder(this)
        startForeground(7401, notification.setSmallIcon(R.drawable.ic_probe)
            .setContentTitle("正在扫描车辆").setContentText("完成后可导出报告")
            .setContentIntent(open).setOngoing(true).build())
        state = state.copy(loading = false, running = true, error = false, errorMessage = null, completed = 0, total = 0)
        worker.execute {
            var result: Pair<ProbeSummary, Long>? = null
            var failure: String? = null
            try {
                VehicleBridgeClient(applicationContext).use { client ->
                    val text = client.scanReport()
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Vehicle scan cancelled")
                    val summary = ProbeSummary.parse(text)
                    ProbeReports.save(applicationContext, text)
                    result = summary to ProbeReports.file(applicationContext).lastModified()
                }
            } catch (error: Exception) {
                Log.e("DiPlayVehicleProbe", "Scan failed", error)
                failure = if (error is SecurityException) "车辆数据桥未授权当前 DiPlay 签名，请安装正式签名版本"
                    else error.message?.take(180) ?: "车辆数据桥扫描失败，请重试"
            } finally {
                val completed = result
                main.post {
                    if (!destroyed) {
                        state = if (completed == null) state.copy(loading = false, running = false, error = true, errorMessage = failure)
                            else ProbeState(loading = false, summary = completed.first, lastScan = completed.second)
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
