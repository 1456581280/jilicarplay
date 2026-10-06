// carlito | Read calibrated category values through the independent bridge only.
package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.vehicleprobe.VehicleBridgeClient
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ProjectionVehicleReader(context: Context, private val onFrame: (ProjectionVehicleFrame) -> Unit) : Closeable {
    @Volatile private var closed = false
    private val poller = synchronized(lock) {
        (current ?: Poller(context.applicationContext).also { current = it }).also { it.listeners += this }
    }
    val stage: String get() = if (closed) "STOPPED" else poller.stage

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        poller.listeners -= this
        if (poller.listeners.isEmpty()) {
            if (current === poller) current = null
            poller.close()
        }
    }

    companion object {
        private val lock = Any()
        private var current: Poller? = null
    }

    // carlito | Editor, projection and iPhone motion use one read loop in each app process.
    private class Poller(private val app: Context) : Closeable {
        val listeners = mutableSetOf<ProjectionVehicleReader>()
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadScheduledExecutor()
        @Volatile private var closed = false
        @Volatile var stage = "CONNECTING"
            private set
        private var bridge: VehicleBridgeClient? = null

        init {
            worker.scheduleWithFixedDelay(::read, 0, 1, TimeUnit.SECONDS)
        }
        private fun read() {
            if (closed) return
            var frame = ProjectionVehicleFrame()
            try {
                val started = SystemClock.elapsedRealtime()
                val names = ProjectionField.entries.mapNotNull { it.property } + "GEAR"
                val response = (bridge ?: VehicleBridgeClient(app).also { bridge = it }).readProperties(names.toSet())
                check(response.getInt("schema") == 1) { "BRIDGE_VERSION" }
                val values = response.getBundle("values")
                val units = response.getBundle("units")
                val states = response.getBundle("states")
                val parsed = names.mapNotNull { name ->
                    if (values?.containsKey(name) != true || states?.getString(name) != "READ_OK") return@mapNotNull null
                    val number = (values.get(name) as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: return@mapNotNull null
                    name to ProjectionVehicleValue(number, units?.getString(name).orEmpty().take(16))
                }.toMap()
                // Age starts before the call: a slow response cannot revive stale readings.
                frame = ProjectionVehicleFrame(started, parsed)
                stage = if (parsed.isEmpty()) "NO_VALUES" else "READ_OK"
            } catch (error: Exception) {
                stage = if (error is SecurityException) "CLIENT_NOT_AUTHORIZED" else "BRIDGE_UNAVAILABLE"
                runCatching { bridge?.close() }; bridge = null
            }
            val delivered = frame
            main.post {
                val subscribers = synchronized(lock) { if (closed) emptyList() else listeners.toList() }
                subscribers.forEach { if (!it.closed) it.onFrame(delivered) }
            }
        }
        override fun close() {
            if (closed) return
            closed = true
            worker.execute { runCatching { bridge?.close() }; bridge = null }
            worker.shutdown()
        }
    }
}
