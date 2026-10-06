// carlito | Per-role device preferences reuse the existing device enumerator and navigation preference.
package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioAttributes
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AudioOutputDevice
import com.shilapi.xcertplay.media.AudioOutputRoutes

internal enum class VehicleAudioRole(val label: Int, val usage: Int, val factoryKind: String, val input: Boolean = false) {
    MEDIA(R.string.vehicle_audio_media, AudioAttributes.USAGE_MEDIA, "MEDIA"),
    NAVIGATION(R.string.vehicle_audio_navigation, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, "GUIDANCE"),
    PHONE(R.string.vehicle_audio_phone, AudioAttributes.USAGE_VOICE_COMMUNICATION, "PHONE"),
    ASSISTANT(R.string.vehicle_audio_assistant, AudioAttributes.USAGE_ASSISTANT, "SIRI"),
    RINGTONE(R.string.vehicle_audio_ringtone, AudioAttributes.USAGE_NOTIFICATION_RINGTONE, "RING"),
    PHONE_MICROPHONE(R.string.vehicle_audio_phone_microphone, AudioAttributes.USAGE_VOICE_COMMUNICATION, "PHONE", true),
    ASSISTANT_MICROPHONE(R.string.vehicle_audio_assistant_microphone, AudioAttributes.USAGE_ASSISTANT, "SIRI", true),
}

internal object VehicleAudioRoutes {
    private fun prefs(context: Context) = context.getSharedPreferences("vehicle_audio_routes", Context.MODE_PRIVATE)
    fun get(context: Context, role: VehicleAudioRole): AudioOutputDevice? = if (role == VehicleAudioRole.NAVIGATION)
        AirPlayPersistence.loadNavigationOutputDevice(context) else AudioOutputDevice.decode(prefs(context).getString(role.name, null))
    fun set(context: Context, role: VehicleAudioRole, device: AudioOutputDevice?) {
        if (role == VehicleAudioRole.NAVIGATION) {
            AirPlayPersistence.saveNavigationOutputDevice(context, device)
            if (device != null) AirPlayPersistence.saveNavigationAudioChannel(context, 0)
        } else {
            prefs(context).edit().putString(role.name, device?.encode()).apply()
            if (role == VehicleAudioRole.MEDIA && device != null) AirPlayPersistence.saveMediaAudioChannel(context, 0)
        }
    }
    fun load(context: Context) = AudioOutputRoutes(get(context, VehicleAudioRole.MEDIA), get(context, VehicleAudioRole.NAVIGATION),
        get(context, VehicleAudioRole.PHONE), get(context, VehicleAudioRole.ASSISTANT), get(context, VehicleAudioRole.RINGTONE),
        get(context, VehicleAudioRole.PHONE_MICROPHONE), get(context, VehicleAudioRole.ASSISTANT_MICROPHONE))
}
