// carlito | Exclusive vehicle audio source; never changes Bluetooth discovery or authentication.
package com.shilapi.xcertplay.vehicle

import android.content.Context

object VehicleAudioOutputMode {
    private fun prefs(context: Context) = context.getSharedPreferences("vehicle_audio_routes", Context.MODE_PRIVATE)
    fun bluetooth(context: Context): Boolean = prefs(context).getBoolean("bluetooth_output", false)
    fun setBluetooth(context: Context, value: Boolean) { prefs(context).edit().putBoolean("bluetooth_output", value).apply() }
}
