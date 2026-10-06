package com.shilapi.xcertplay.network

import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Selects and remembers the first wireless link that the current head unit can provide. */
class AutomaticHotspotManager(
    context: Context,
    private val manualSsid: String?,
    private val manualPassphrase: String?,
    private val manualBand: ManualHotspotBand,
    private val manualChannel: Int,
    private val manualSecurity: ManualHotspotSecurity,
    private val wifiP2pPreferredChannel: Int,
    private val onDiagnostic: (String) -> Unit = {},
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile
    private var activeManager: WirelessHotspotManager? = null

    @Volatile
    private var activeBackend: WirelessHotspotBackend? = null

    @Volatile
    private var closed = false

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "AutomaticHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(lock) {
            check(!closed) { "AutomaticHotspotManager is closed" }
            check(activeManager == null) { "An automatic hotspot is already starting or active" }
        }

        val attempts = buildAttempts()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        attempts.forEachIndexed { index, attempt ->
            val remainingMillis = remainingMillis(deadline)
            if (remainingMillis <= 0) return@forEachIndexed
            val attemptMillis = if (index == attempts.lastIndex) {
                remainingMillis
            } else {
                minOf(remainingMillis, attempt.maximumMillis)
            }
            val manager = try {
                attempt.create()
            } catch (error: Exception) {
                val reason = error.message ?: error.javaClass.simpleName
                onDiagnostic("Automatic hotspot backend=${attempt.backend.label} unavailable reason=$reason")
                return@forEachIndexed
            }
            synchronized(lock) {
                if (closed) {
                    runCatching { manager.close() }
                    throw IOException("Automatic hotspot selection was cancelled")
                }
                activeManager = manager
                activeBackend = attempt.backend
            }
            onDiagnostic("Automatic hotspot trying backend=${attempt.backend.label}")
            try {
                return manager.start(attemptMillis).also {
                    onDiagnostic("Automatic hotspot selected backend=${it.backend.label}")
                }
            } catch (error: Exception) {
                val reason = error.message ?: error.javaClass.simpleName
                onDiagnostic("Automatic hotspot backend=${attempt.backend.label} unavailable reason=$reason")
                synchronized(lock) {
                    if (activeManager === manager) {
                        activeManager = null
                        activeBackend = null
                    }
                }
                runCatching { manager.close() }
                if (closed) throw IOException("Automatic hotspot selection was cancelled", error)
            }
        }
        throw IOException("Could not prepare a wireless connection. Check that Wi-Fi is available and try again.")
    }

    override fun onCarPlayConfirmed() {
        val manager = activeManager ?: return
        manager.onCarPlayConfirmed()
        activeBackend?.let { backend ->
            preferences.edit().putString(KEY_LAST_BACKEND, backend.name).apply()
        }
    }

    override fun validateReady() {
        activeManager?.validateReady()
    }

    override fun onStartupFailed() { activeManager?.onStartupFailed() }

    override fun connectionDiagnosticSnapshot(): String =
        activeManager?.connectionDiagnosticSnapshot() ?: "automaticBackend=unavailable association=unknown"

    override fun close() {
        val manager = synchronized(lock) {
            if (closed) return
            closed = true
            activeManager.also {
                activeManager = null
                activeBackend = null
            }
        }
        manager?.close()
    }

    private fun buildAttempts(): List<Attempt> {
        val manualProbeMillis = if (EcarxHotspotReader.available(appContext)) 10_000L else MANUAL_PROBE_MILLIS
        val manual = Attempt(WirelessHotspotBackend.MANUAL_HOTSPOT, manualProbeMillis) {
            ManualHotspotManager(
                context = appContext,
                ssid = manualSsid,
                passphrase = manualPassphrase,
                band = manualBand,
                channel = manualChannel,
                security = manualSecurity,
                preferSystemConfiguration = true,
                onDiagnostic = onDiagnostic,
                isCancelled = { closed },
            )
        }
        val local = Attempt(WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT, LOCAL_HOTSPOT_MILLIS) {
            LocalOnlyHotspotManager(appContext, onDiagnostic)
        }
        val generated = mutableListOf(local)
        // carlito: Upstream provides a public Android 9 group creation path as well.
        generated += Attempt(WirelessHotspotBackend.WIFI_P2P, WIFI_P2P_MILLIS) {
            WifiP2pGroupManager(appContext, onDiagnostic, wifiP2pPreferredChannel)
        }
        val remembered = preferences.getString(KEY_LAST_BACKEND, null)
            ?.let { name -> WirelessHotspotBackend.entries.firstOrNull { it.name == name } }
        if (remembered != null && remembered != WirelessHotspotBackend.MANUAL_HOTSPOT) {
            generated.sortByDescending { it.backend == remembered }
        }
        return listOf(manual) + generated
    }

    private fun remainingMillis(deadlineNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((deadlineNanos - System.nanoTime()).coerceAtLeast(0L))

    private data class Attempt(
        val backend: WirelessHotspotBackend,
        val maximumMillis: Long,
        val create: () -> WirelessHotspotManager,
    )

    private companion object {
        const val PREFERENCES = "carplay_automatic_hotspot"
        const val KEY_LAST_BACKEND = "last_confirmed_backend"
        const val MANUAL_PROBE_MILLIS = 4_000L
        const val LOCAL_HOTSPOT_MILLIS = 35_000L
        const val WIFI_P2P_MILLIS = 20_000L
    }
}
