// carlito | Public key/event mappings only; OEM input implementation is in the vehicle bridge.
package com.shilapi.xcertplay

import android.os.Build

internal data class GeelySteeringKeyEvent(
    val keyCode: Int,
    val rawKeyCode: Int,
    val action: Int,
    val eventTimeMs: Long,
) {
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_SINGLE = 2
        const val ACTION_LONG = 3
        const val ACTION_DOUBLE = 4
    }
}

internal object GeelySteeringKeyCodes {
    const val MEDIA_PLAY_PAUSE = 200_085
    const val MEDIA_NEXT = 200_087
    const val MEDIA_PREVIOUS = 200_088
    const val VOICE_ASSIST = 200_231
    const val SEEK_NEXT = 210_005
    const val SEEK_PREVIOUS = 210_006
    // carlito | Generic volume buttons/rotary aliases can be learned for temporary map zoom.
    const val VOLUME_UP = 200_024
    const val VOLUME_DOWN = 200_025

    fun canonicalize(keyCode: Int): Int? = when (keyCode) {
        MEDIA_PLAY_PAUSE, 85 -> MEDIA_PLAY_PAUSE
        MEDIA_NEXT, 87, 110_005 -> if (keyCode == 87) MEDIA_NEXT else if (keyCode == 110_005) SEEK_NEXT else MEDIA_NEXT
        MEDIA_PREVIOUS, 88, 110_006 -> if (keyCode == 88) MEDIA_PREVIOUS else if (keyCode == 110_006) SEEK_PREVIOUS else MEDIA_PREVIOUS
        VOICE_ASSIST, 231 -> VOICE_ASSIST
        SEEK_NEXT -> SEEK_NEXT
        SEEK_PREVIOUS -> SEEK_PREVIOUS
        VOLUME_UP, 24 -> VOLUME_UP
        VOLUME_DOWN, 25 -> VOLUME_DOWN
        200_400 -> 200_400
        210_007, 110_007 -> 210_007
        210_008, 110_008 -> 210_008
        210_009, 110_009 -> 210_009
        210_010, 110_010 -> 210_010
        else -> null
    }

    fun enabledByDefault(): Boolean = Build.MODEL.orEmpty().uppercase().let { model ->
        listOf("G636", "FX11", "KX11", "G733", "L6").any(model::contains)
    }
}
