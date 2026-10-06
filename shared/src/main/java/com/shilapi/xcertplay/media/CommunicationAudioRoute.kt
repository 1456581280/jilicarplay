// carlito | Owns only DiPlay's communication mode/device/SCO requests, with one process-wide lease.
package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import java.io.Closeable

internal class CommunicationAudioRoute(context: Context?, private val report: (String) -> Unit) : Closeable {
    private val manager = context?.getSystemService(AudioManager::class.java)
    private var output: AudioOutputDevice? = null
    private var input: AudioOutputDevice? = null
    private var selectedDevice: Int? = null
    private var speakerBefore = false
    private var modeBefore = AudioManager.MODE_NORMAL
    private var speakerApplied: Boolean? = null
    private var scoStarted = false
    private var scoConnected = false
    private var scoAt = 0L
    private var scoFailed = false
    private var closed = false
    fun held(): Boolean = synchronized(ownershipLock) { owner === this }

    fun externalCall(): Boolean {
        val mode = runCatching { manager?.mode }.getOrNull() ?: return false
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION && !held()
    }

    fun acquire(nextOutput: AudioOutputDevice?, nextInput: AudioOutputDevice?): Boolean = synchronized(ownershipLock) {
        val audio = manager ?: return false
        if (closed || owner != null && owner !== this || externalCall()) return false
        if (owner == null) {
            try {
                // The same phone may already be ringing over HFP; active calls remain protected.
                val mode = audio.mode
                if (mode != AudioManager.MODE_NORMAL && mode != AudioManager.MODE_RINGTONE) return false
                modeBefore = mode
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                owner = this
                speakerBefore = audio.isSpeakerphoneOn
                runCatching { report("Audio: communication mode acquired") }
            } catch (error: RuntimeException) {
                android.util.Log.w("xcertplay-usb", "microphone start failed: communication mode unavailable", error)
                runCatching { report("Audio: communication mode unavailable ${error.javaClass.simpleName}") }
                return false
            }
        }
        if (output != nextOutput || input != nextInput) {
            output = nextOutput; input = nextInput
            scoFailed = false
            refreshDevices()
        }
        audio.mode == AudioManager.MODE_IN_COMMUNICATION
    }

    fun refreshDevices(retry: Boolean = false) {
        val audio = manager ?: return
        if (!held() || closed || externalCall()) return
        if (retry) scoFailed = false
        val preferredOutput = output?.resolve(audio)
        val preferredInput = input?.resolveInput(audio)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val choices = audio.availableCommunicationDevices
                val desired = choices.firstOrNull { it.id == preferredOutput?.id }
                    ?: preferredInput?.takeIf { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                        ?.let { source -> choices.filter { it.type == source.type && it.address == source.address }.singleOrNull() }
                if (desired?.id != selectedDevice) {
                    if (desired == null) { if (selectedDevice != null) audio.clearCommunicationDevice(); selectedDevice = null }
                    else if (audio.setCommunicationDevice(desired)) selectedDevice = desired.id
                    runCatching { report("Audio: communication device requested=${desired?.id} accepted=${desired == null || selectedDevice == desired.id}") }
                }
            } else {
                val wantsSco = preferredOutput?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || preferredInput?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                if (wantsSco && !scoStarted && !scoFailed) {
                    if (audio.isBluetoothScoOn) scoConnected = true
                    else {
                        scoAt = SystemClock.elapsedRealtime()
                        audio.startBluetoothSco(); scoStarted = true
                        audio.isBluetoothScoOn = true
                    }
                } else if (!wantsSco && scoStarted) stopOwnedSco()
                val speaker = when (preferredOutput?.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> true
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> false
                    else -> null
                }
                if (speaker != speakerApplied) {
                    if (speaker != null) { audio.isSpeakerphoneOn = speaker; speakerApplied = speaker }
                    else restoreSpeaker()
                }
            }
        } catch (error: RuntimeException) {
            // Restricted ROMs keep their standard route. Never disconnect the phone's HFP profile.
            scoFailed = true; stopOwnedSco()
            runCatching { report("Audio: communication route fallback ${error.javaClass.simpleName}") }
        }
    }

    fun onScoState(connected: Boolean) { scoConnected = connected }
    fun captureReady(): Boolean {
        if (!held() || externalCall() || manager?.mode != AudioManager.MODE_IN_COMMUNICATION) return false
        if (scoStarted && !scoConnected) {
            if (SystemClock.elapsedRealtime() - scoAt < 5_000L) return false
            scoFailed = true; stopOwnedSco()
            runCatching { report("Audio: Bluetooth voice route timed out; using system fallback") }
        }
        return true
    }

    private fun stopOwnedSco() {
        if (!scoStarted) return
        scoStarted = false; scoConnected = false
        runCatching { manager?.stopBluetoothSco() }
        runCatching { manager?.isBluetoothScoOn = false }
    }
    private fun restoreSpeaker() {
        val applied = speakerApplied ?: return
        runCatching { if (manager?.isSpeakerphoneOn == applied) manager.isSpeakerphoneOn = speakerBefore }
        speakerApplied = null
    }

    fun release() = synchronized(ownershipLock) {
        if (owner !== this) return@synchronized
        try {
            if (Build.VERSION.SDK_INT >= 31 && selectedDevice != null) runCatching { manager?.clearCommunicationDevice() }
            stopOwnedSco(); restoreSpeaker()
            // Clearing this process's mode request lets an existing native call keep its priority.
            runCatching { manager?.mode = if (manager?.mode == AudioManager.MODE_IN_COMMUNICATION) modeBefore else AudioManager.MODE_NORMAL }
            runCatching { report("Audio: communication route released") }
        } finally {
            owner = null; output = null; input = null; selectedDevice = null; scoFailed = false
        }
    }
    override fun close() { release(); closed = true }
    private companion object { val ownershipLock = Any(); var owner: CommunicationAudioRoute? = null }
}
