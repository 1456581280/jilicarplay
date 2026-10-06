// carlito | Independent optional output preferences; null retains each role's existing vehicle policy.
package com.shilapi.xcertplay.media

data class AudioOutputRoutes(
    val media: AudioOutputDevice? = null,
    val navigation: AudioOutputDevice? = null,
    val phone: AudioOutputDevice? = null,
    val assistant: AudioOutputDevice? = null,
    val ringtone: AudioOutputDevice? = null,
    val phoneMicrophone: AudioOutputDevice? = null,
    val assistantMicrophone: AudioOutputDevice? = null,
) {
    internal fun device(channel: AudioChannel): AudioOutputDevice? = when (channel) {
        AudioChannel.MEDIA -> media
        AudioChannel.NAVIGATION -> navigation
        AudioChannel.PHONE -> phone
        AudioChannel.ASSISTANT -> assistant
        AudioChannel.RINGTONE -> ringtone
    }
    internal fun microphone(audioType: String): AudioOutputDevice? = if (isPhoneAudio(audioType)) phoneMicrophone
        else assistantMicrophone.takeIf { audioType.equals("speechrecognition", true) }
}
