// carlito | Peer-scoped A2DP sink handoff; restores only an app-disconnected, still-bonded peer.
package com.shilapi.xcertplay.vehicle

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable
import java.util.Locale

@SuppressLint("MissingPermission")
internal class BluetoothAudioHandoff(
    context: Context, address: String, private val report: (String) -> Unit,
    private val onPlaying: ((Boolean) -> Unit)? = null,
) : Closeable {
    private val app = context.applicationContext
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val peer = address.uppercase(Locale.US)
    private val main = Handler(Looper.getMainLooper())
    private var proxy: BluetoothProfile? = null
    private var registered = false
    private var held = false
    @Volatile private var closed = false
    @Volatile private var suppressed = true
    private var lastDisconnectAt = 0L
    private var unavailable = false
    private var playing = false
    private var playbackQueryUnavailable = false
    private val playbackPoll = object : Runnable {
        override fun run() = synchronized(this@BluetoothAudioHandoff) {
            if (closed || onPlaying == null) return@synchronized
            refreshPlaying()
            main.postDelayed(this, 1_000L)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action !in setOf(ACTION_CONNECTION, ACTION_PLAYING) || closed) return
            @Suppress("DEPRECATION") val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (!device.address.equals(peer, true)) return
            if (intent.action == ACTION_PLAYING) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                // Accept only this session's connected phone and a known platform playback state.
                val connected = runCatching { proxy?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)
                if (connected && state in setOf(STATE_PLAYING, STATE_NOT_PLAYING)) publishPlaying(state == STATE_PLAYING)
            } else when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                BluetoothProfile.STATE_CONNECTED -> { disconnectPeer(); refreshPlaying() }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    publishPlaying(false)
                    if (!suppressed) proxy?.let { restoreActive(it) }
                }
            }
        }
    }

    @Synchronized fun setSuppressed(value: Boolean) {
        suppressed = value
        val connected = proxy ?: return
        if (value) disconnectPeer() else restoreActive(connected)
    }

    @Synchronized fun start() {
        if (closed || held) return
        if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            report("Audio: Bluetooth music handoff unavailable: connection permission"); return
        }
        try {
            synchronized(leases) { leases.getOrPut(peer) { Lease() }.owners.add(this) }
            held = true
            val filter = IntentFilter(ACTION_CONNECTION).apply { if (onPlaying != null) addAction(ACTION_PLAYING) }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(receiver, filter)
            registered = true
            val requested = adapter?.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, connected: BluetoothProfile) = synchronized(this@BluetoothAudioHandoff) {
                    if (closed) { restoreThenClose(connected); return@synchronized }
                    proxy = connected
                    playbackQueryUnavailable = false
                    refreshPlaying()
                    if (suppressed) disconnectPeer() else restoreActive(connected)
                }
                override fun onServiceDisconnected(profile: Int) = synchronized(this@BluetoothAudioHandoff) { proxy = null; publishPlaying(false) }
            }, A2DP_SINK) == true
            if (requested && onPlaying != null) main.post(playbackPoll)
            if (!requested) { report("Audio: Bluetooth music handoff unavailable: sink profile; retaining focus routing"); close() }
        } catch (error: Exception) {
            report("Audio: Bluetooth music handoff unavailable: ${error.javaClass.simpleName}"); close()
        }
    }

    // carlito | AOSP A2DP_SINK exposes isA2dpPlaying; hidden/unsupported queries keep CarPlay fallback.
    @Synchronized private fun refreshPlaying() {
        if (closed || onPlaying == null) return
        val profile = proxy ?: run { publishPlaying(false); return }
        try {
            val device = profile.connectedDevices.firstOrNull { it.address.equals(peer, true) }
                ?: run { publishPlaying(false); return }
            if (!playbackQueryUnavailable) {
                val value = profile.javaClass.getMethod("isA2dpPlaying", BluetoothDevice::class.java).invoke(profile, device) as? Boolean
                if (value != null) publishPlaying(value) else publishPlaying(false)
            }
        } catch (error: Exception) {
            // Future authenticated platform playback broadcasts may still establish playback.
            val firstFailure = !playbackQueryUnavailable
            playbackQueryUnavailable = true
            publishPlaying(false)
            if (firstFailure) report("Audio: Bluetooth playback state unavailable: ${error.javaClass.simpleName}; retaining CarPlay fallback")
        }
    }

    @Synchronized private fun publishPlaying(value: Boolean) {
        if (onPlaying == null || playing == value) return
        playing = value
        runCatching { onPlaying?.invoke(value) }
    }

    @Synchronized private fun disconnectPeer() {
        if (closed || !suppressed || unavailable) return
        val profile = proxy ?: return
        try {
            val device = profile.connectedDevices.firstOrNull { it.address.equals(peer, true) && it.bondState == BluetoothDevice.BOND_BONDED } ?: return
            val now = SystemClock.elapsedRealtime()
            if (lastDisconnectAt != 0L && now - lastDisconnectAt < 500) return
            lastDisconnectAt = now
            synchronized(leases) {
                if (profile.javaClass.getMethod("disconnect", BluetoothDevice::class.java).invoke(profile, device) == true) {
                    leases[peer]?.disconnected = true
                    report("Audio: Bluetooth music handed to CarPlay")
                }
            }
        } catch (error: Exception) {
            unavailable = true
            report("Audio: Bluetooth music handoff fallback: ${error.javaClass.simpleName}")
        }
    }

    private fun restoreIfOwned(profile: BluetoothProfile): Boolean = synchronized(leases) {
        val lease = leases[peer] ?: return true
        if (!lease.disconnected || lease.owners.any { it.suppressed && !it.closed }) return true
        try {
            val device = adapter?.bondedDevices?.firstOrNull { it.address.equals(peer, true) }
            if (adapter?.isEnabled != true || device == null) {
                lease.disconnected = false
            } else when (profile.getConnectionState(device)) {
                BluetoothProfile.STATE_DISCONNECTING -> return false
                BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING -> lease.disconnected = false
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val accepted = profile.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(profile, device) == true
                    if (accepted) lease.disconnected = false
                    else return false
                    report("Audio: original Bluetooth music reconnect accepted=$accepted")
                }
            }
        } catch (error: Exception) { report("Audio: original Bluetooth music reconnect unavailable: ${error.javaClass.simpleName}") }
        if (lease.owners.isEmpty() && !lease.disconnected) leases.remove(peer)
        true
    }

    // carlito | A disconnect may still be completing when focus is yielded.
    private fun restoreActive(profile: BluetoothProfile, deadline: Long = SystemClock.elapsedRealtime() + 5_000L) {
        if (closed || suppressed || proxy !== profile) return
        if (!restoreIfOwned(profile) && SystemClock.elapsedRealtime() < deadline)
            main.postDelayed({ restoreActive(profile, deadline) }, 250L)
    }

    private fun restoreThenClose(profile: BluetoothProfile, deadline: Long = SystemClock.elapsedRealtime() + 5_000L) {
        if (!restoreIfOwned(profile) && SystemClock.elapsedRealtime() < deadline) {
            main.postDelayed({ restoreThenClose(profile, deadline) }, 250L)
        } else runCatching { adapter?.closeProfileProxy(A2DP_SINK, profile) }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(playbackPoll)
        publishPlaying(false)
        if (registered) runCatching { app.unregisterReceiver(receiver) }
        registered = false
        if (held) synchronized(leases) {
            leases[peer]?.let { lease ->
                lease.owners.remove(this)
                if (lease.owners.isEmpty() && !lease.disconnected) leases.remove(peer)
            }
        }
        held = false
        proxy?.let(::restoreThenClose); proxy = null
    }

    private class Lease { val owners = mutableSetOf<BluetoothAudioHandoff>(); var disconnected = false }
    private companion object {
        val leases = HashMap<String, Lease>()
        const val A2DP_SINK = 11
        const val STATE_PLAYING = 10
        const val STATE_NOT_PLAYING = 11
        const val ACTION_PLAYING = "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED"
        const val ACTION_CONNECTION = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
    }
}
