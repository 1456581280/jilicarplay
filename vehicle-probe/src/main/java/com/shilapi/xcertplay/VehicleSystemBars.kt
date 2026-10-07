// carlito | Preserve the v0.2.11 OEM immersive flags alongside the modern insets API.
package com.shilapi.xcertplay

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

const val EXTRA_HIDE_TOP_BAR = "diplay.hideTopBar"
const val EXTRA_HIDE_BOTTOM_BAR = "diplay.hideBottomBar"

@Suppress("DEPRECATION")
fun applyVehicleSystemBars(activity: Activity, hideTopBar: Boolean, hideBottomBar: Boolean) {
    val multiWindow = Build.VERSION.SDK_INT >= 24 && activity.isInMultiWindowMode
    val hideTop = hideTopBar && !multiWindow
    val hideBottom = hideBottomBar && !multiWindow
    val window = activity.window
    var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    if (hideTop) flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_FULLSCREEN
    if (hideBottom) flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    if (hideTop || hideBottom) flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    window.decorView.systemUiVisibility = flags
    WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hideTop) hide(WindowInsetsCompat.Type.statusBars()) else show(WindowInsetsCompat.Type.statusBars())
        if (hideBottom) hide(WindowInsetsCompat.Type.navigationBars()) else show(WindowInsetsCompat.Type.navigationBars())
    }
}
