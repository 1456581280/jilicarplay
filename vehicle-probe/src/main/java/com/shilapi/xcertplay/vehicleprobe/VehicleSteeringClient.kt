// carlito | Steering client for the independent vehicle bridge APK; no OEM implementation here.
package com.shilapi.xcertplay.vehicleprobe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import com.geely.desktop.vehicle.properties.IVehicleProperties
import com.geely.desktop.vehicle.properties.IVehicleSteeringCallback
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Binder work and reconnection run on one worker; callback consumers choose their own UI thread. */
class VehicleSteeringClient(context: Context, private val onEvent: (Bundle) -> Unit) : Closeable {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diplay-bridge-steering").apply { isDaemon = true }
    }
    private data class Request(val keys: List<Int>, val intercept: Boolean)
    @Volatile private var request = Request(emptyList(), false)
    @Volatile private var closed = false
    @Volatile private var configured = false
    @Volatile private var state = Bundle().apply { putString("stage", "IDLE") }
    private var remote: IVehicleProperties? = null
    private var bound = false
    private var boundAt = 0L
    private val callback = object : IVehicleSteeringCallback.Stub() {
        override fun onKeyEvent(event: Bundle?) {
            if (closed || event == null) return
            val snapshot = Bundle(event)
            val expected = request
            // carlito | Serialize callbacks after the registration reply, including the first XUI press.
            enqueue { if (expected == request) acceptEvent(snapshot) }
        }
    }

    private fun acceptEvent(event: Bundle) {
        val current = request
        val key = event.getInt("keyCode")
        val raw = event.getInt("rawKeyCode")
        if (current.keys.isNotEmpty() && key !in current.keys) return
        val status = state
        if (status.getString("stage") in listOf("CONFIGURING", "CLOSED") ||
            (event.containsKey("leaseId") && event.getLong("leaseId") != status.getLong("leaseId"))) return
        if (current.intercept) {
            val held = status.getIntArray("interceptedRawKeys") ?: intArrayOf()
            // The authenticated bridge can confirm consumption in the callback before the next poll.
            if (raw !in held && !event.getBoolean("intercepted")) return
            if (event.getBoolean("intercepted")) state = Bundle(status).apply {
                putIntArray("interceptedRawKeys", (held + raw).distinct().toIntArray())
                val confirmed = ((getIntArray("interceptedKeys") ?: intArrayOf()) + key).distinct().toIntArray()
                putIntArray("interceptedKeys", confirmed)
                val pending = current.keys.filter { it !in confirmed }.toIntArray()
                putIntArray("unconfirmedKeys", pending)
                if (status.getString("stage") != "RELEASE_REJECTED") {
                    putString("stage", if (pending.isEmpty()) "ACTIVE" else "PARTIAL_INTERCEPTION")
                    putBoolean("ready", true)
                }
            }
        }
        onEvent(Bundle(event))
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = enqueue {
            remote = IVehicleProperties.Stub.asInterface(binder)
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) = enqueue { disconnect(); publishFailure("BRIDGE_DISCONNECTED") }
        override fun onBindingDied(name: ComponentName?) = enqueue { disconnect(); publishFailure("BRIDGE_DISCONNECTED") }
        override fun onNullBinding(name: ComponentName?) = enqueue { disconnect(); publishFailure("BRIDGE_UNAVAILABLE") }
    }
    private val poll = worker.scheduleWithFixedDelay(::refresh, 0, 2, TimeUnit.SECONDS)

    fun update(keyCodes: IntArray, intercept: Boolean) {
        require(keyCodes.size <= 64 && (!intercept || keyCodes.isNotEmpty()) && keyCodes.all { it in 1..1_000_000 })
        check(!closed)
        val next = Request(keyCodes.distinct().sorted(), intercept)
        if (configured && next == request) return
        configured = true
        request = next
        // Drop previous ownership immediately; the next worker response establishes the new lease.
        state = Bundle().apply { putString("stage", "CONFIGURING") }
        enqueue(::refresh)
    }

    fun status(): Bundle = Bundle(state)
    fun ready(): Boolean = !closed && state.getBoolean("ready")
    fun ownsRawKey(keyCode: Int): Boolean = !closed && request.intercept &&
        keyCode in (state.getIntArray("interceptedRawKeys") ?: intArrayOf())
    fun ownsCanonicalKey(keyCode: Int): Boolean = !closed && request.intercept &&
        keyCode in (state.getIntArray("interceptedKeys") ?: intArrayOf())
    fun diagnostics(): String = state.let {
        "vehicleBridge stage=${it.getString("stage")} platform=${it.getString("platform")} " +
            "intercept=${it.getBoolean("interceptRequested")} held=${(it.getIntArray("interceptedRawKeys") ?: intArrayOf()).joinToString()} " +
            "rejected=${(it.getIntArray("rejectedKeys") ?: intArrayOf()).joinToString()} " +
            "unconfirmed=${(it.getIntArray("unconfirmedKeys") ?: intArrayOf()).joinToString()} " +
            "vendorResult=${it.getString("requestResult")} " +
            "observed=${it.getLong("observedEvents")} forwarded=${it.getLong("forwardedEvents")} " +
            "last=${it.getString("lastCallback")} " +
            "service=${it.getString("service")} failure=${it.getString("failure")}"
    }

    private fun refresh() {
        if (closed || !configured) return
        try {
            if (remote == null) {
                if (bound && SystemClock.elapsedRealtime() - boundAt > 10_000) disconnect()
                if (!bound) {
                    app.packageManager.getPackageInfo(VehicleBridgeClient.BRIDGE_PACKAGE, 0)
                    bound = app.bindService(Intent().setComponent(ComponentName(VehicleBridgeClient.BRIDGE_PACKAGE,
                        "${VehicleBridgeClient.BRIDGE_PACKAGE}.VehiclePropertiesService")), connection, Context.BIND_AUTO_CREATE)
                    boundAt = SystemClock.elapsedRealtime()
                }
                publishFailure(if (bound) "BRIDGE_CONNECTING" else "BRIDGE_UNAVAILABLE")
                return
            }
            val api = remote ?: return
            check(api.status.getInt("steeringProtocol") == 1) { "BRIDGE_UPDATE_REQUIRED" }
            val current = request
            val response = api.registerSteeringListener(callback, current.keys.toIntArray(), current.intercept)
            check(response.getInt("schema") == 1) { "BRIDGE_UPDATE_REQUIRED" }
            if (!closed && current == request) state = Bundle(response)
        } catch (error: Exception) {
            runCatching { remote?.unregisterSteeringListener(callback) }
            if (error is android.os.RemoteException) disconnect()
            publishFailure(when (error) {
                is android.content.pm.PackageManager.NameNotFoundException -> "BRIDGE_NOT_INSTALLED"
                is SecurityException -> "CLIENT_NOT_AUTHORIZED"
                else -> "BRIDGE_ERROR"
            }, error)
        }
    }

    private fun publishFailure(stage: String, error: Exception? = null) {
        state = Bundle().apply {
            putString("stage", stage); putBoolean("ready", false)
            putString("failure", error?.let { "${it.javaClass.simpleName}: ${it.message?.take(160)}" }.orEmpty())
        }
    }
    private fun enqueue(action: () -> Unit) {
        if (closed) return
        try { worker.execute { if (!closed) action() } } catch (_: RejectedExecutionException) { }
    }
    private fun disconnect() {
        remote = null
        if (bound) runCatching { app.unbindService(connection) }
        bound = false
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        state = Bundle().apply { putString("stage", "CLOSED") }
        poll.cancel(false)
        worker.execute {
            try { remote?.unregisterSteeringListener(callback) }
            catch (_: Exception) { /* Binder death also releases the bridge lease. */ }
            finally { disconnect() }
        }
        worker.shutdown()
    }
    companion object {
        fun installed(context: Context): Boolean = runCatching {
            context.packageManager.getPackageInfo(VehicleBridgeClient.BRIDGE_PACKAGE, 0)
        }.isSuccess
    }
}
