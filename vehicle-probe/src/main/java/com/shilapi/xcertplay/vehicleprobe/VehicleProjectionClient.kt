// carlito | Public instrument capability/lease client; all OEM behavior remains in the GD APK.
package com.shilapi.xcertplay.vehicleprobe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import com.geely.desktop.vehicle.properties.IVehicleProperties
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VehicleProjectionClient(context: Context) : Closeable {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-instrument-lease").apply { isDaemon = true } }
    private val owner = Binder()
    @Volatile private var closed = false
    @Volatile private var desired = false to false
    @Volatile private var state = Bundle()
    private var remote: IVehicleProperties? = null
    private var held = false
    private var bound = false
    private var boundAt = 0L
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = enqueue {
            remote = IVehicleProperties.Stub.asInterface(binder); refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) = enqueue { disconnect() }
        override fun onBindingDied(name: ComponentName?) = enqueue { disconnect() }
        override fun onNullBinding(name: ComponentName?) = enqueue { disconnect() }
    }
    private val poll = worker.scheduleWithFixedDelay(::refresh, 0, 500, TimeUnit.MILLISECONDS)

    fun update(acquire: Boolean, frameReady: Boolean) {
        check(!closed)
        val next = acquire to (acquire && frameReady)
        if (desired == next) return
        desired = next
        // Immediately invalidate a previous ready reply while the new ownership request is queued.
        state = Bundle()
        enqueue(::refresh)
    }

    fun status(): Bundle = Bundle(state).takeIf {
        SystemClock.elapsedRealtime() - it.getLong("receivedAtMs") in 0..1_500L
    } ?: Bundle()

    /** carlito | An explicit reopen starts a new lease after an original-instrument exit. */
    fun resetLease() {
        state = Bundle()
        enqueue {
            runCatching { remote?.releaseProjection(owner) }.onFailure { disconnect() }
            held = false; state = Bundle()
        }
    }

    private fun refresh() {
        if (closed) return
        try {
            if (remote == null) {
                if (bound && SystemClock.elapsedRealtime() - boundAt > 10_000) disconnect()
                if (!bound) {
                    bound = app.bindService(Intent().setComponent(ComponentName(VehicleBridgeClient.BRIDGE_PACKAGE,
                        "${VehicleBridgeClient.BRIDGE_PACKAGE}.VehiclePropertiesService")), connection, Context.BIND_AUTO_CREATE)
                    boundAt = SystemClock.elapsedRealtime()
                }
                return
            }
            val api = remote ?: return
            check(api.status.getInt("projectionProtocol") == 1) { "BRIDGE_UPDATE_REQUIRED" }
            val request = desired
            val reply = if (request.first) {
                held = true
                api.updateProjection(owner, request.second)
            } else {
                if (held) { api.releaseProjection(owner); held = false }
                api.projectionStatus
            }
            if (!closed && request == desired) state = Bundle(reply).apply {
                putLong("receivedAtMs", SystemClock.elapsedRealtime())
            }
        } catch (error: Exception) {
            state = Bundle().apply { putString("stage", "BRIDGE_UNAVAILABLE"); putString("failure", error.javaClass.simpleName) }
            // Do not silently retain an OEM mode after losing its status/heartbeat contract.
            if (held) runCatching { remote?.releaseProjection(owner) }.onSuccess { held = false }
            if (error is android.os.RemoteException) disconnect()
        }
    }

    private fun enqueue(action: () -> Unit) {
        if (!closed) runCatching { worker.execute { if (!closed) action() } }
    }
    private fun disconnect() {
        remote = null; state = Bundle()
        if (bound) runCatching { app.unbindService(connection) }
        bound = false
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; state = Bundle(); poll.cancel(false)
        worker.execute { try { if (held) remote?.releaseProjection(owner) } finally { disconnect() } }
        worker.shutdown()
    }
}
