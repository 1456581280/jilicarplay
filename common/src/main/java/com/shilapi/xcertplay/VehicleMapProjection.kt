// carlito | Model-independent full-map output; closed vehicle mode control is an optional bridge lease.
package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.widget.FrameLayout
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.vehicleprobe.VehicleProjectionClient
import kotlin.math.min
import kotlin.math.roundToInt

internal data class VehicleMapPlan(val screen: GeelyHudDisplay, val width: Int, val height: Int,
    val visibleHeight: Int, val vehicleMode: Boolean, val automaticCanvas: Boolean = false) {
    fun config() = AirPlayDisplayConfig(width, height, fps = 30, primaryInputDevice = 0,
        viewArea = AirPlayInsets(bottom = height - visibleHeight),
        safeArea = AirPlayInsets(top = visibleHeight * 8 / 100, bottom = height - visibleHeight + visibleHeight * 8 / 100,
            left = width * 8 / 100, right = width * 8 / 100),
        safeAreaDrawOutside = false, initialUrl = CarPlayClusterDisplay.MAP_URL, features = 0)
}

internal object VehicleMapSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("vehicle_map_projection", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun setEnabled(context: Context, value: Boolean) { prefs(context).edit().putBoolean("enabled", value).apply() }
    fun vehicleMode(context: Context) = prefs(context).getBoolean("vehicle_mode", false)
    fun setVehicleMode(context: Context, value: Boolean) { prefs(context).edit().putBoolean("vehicle_mode", value).apply() }
    fun screen(context: Context): GeelyHudDisplay? {
        val stored = prefs(context)
        val id = stored.getInt("display_id", Display.INVALID_DISPLAY)
        val name = stored.getString("display_name", null) ?: return null
        val displays = GeelyHudProjection.availableDisplays(context)
        return displays.firstOrNull { it.id == id && it.name == name }
            ?: displays.filter { it.name == name }.singleOrNull()
    }
    fun select(context: Context, screen: GeelyHudDisplay) {
        prefs(context).edit().putInt("display_id", screen.id).putString("display_name", screen.name)
            .putInt("width", 0).putInt("height", 0).putInt("visible_height", 0).apply()
    }
    fun geometry(context: Context) = prefs(context).let { listOf(it.getInt("width", 0), it.getInt("height", 0), it.getInt("visible_height", 0)) }
    fun saveGeometry(context: Context, values: List<Int>) {
        require(values.size == 3 && values.all { it == 0 || it in 64..4096 })
        val selected = screen(context) ?: error("Select a display")
        val h = values[1].takeIf { it > 0 } ?: selected.height
        require(values[2] == 0 || values[2] <= h)
        prefs(context).edit().putInt("width", values[0]).putInt("height", values[1]).putInt("visible_height", values[2]).apply()
    }
    fun plan(context: Context): VehicleMapPlan? {
        if (!enabled(context)) return null
        val screen = screen(context) ?: return null
        val values = geometry(context)
        val automatic = values[0] == 0 && values[1] == 0
        val scale = if (automatic) min(1.0, 4096.0 / maxOf(screen.width, screen.height).coerceAtLeast(1)) else 1.0
        val w = values[0].takeIf { it > 0 } ?: ((screen.width * scale).toInt() and -2)
        val h = values[1].takeIf { it > 0 } ?: ((screen.height * scale).toInt() and -2)
        if (automatic && values[2] > screen.height) return null
        val visible = if (values[2] == 0) h else if (automatic) (values[2] * scale).toInt().coerceAtMost(h) else values[2]
        if (w !in 64..4096 || h !in 64..4096 || visible !in 64..h) return null
        return VehicleMapPlan(screen, w, h, visible, vehicleMode(context), automatic)
    }
}

/** Survives Activity replacement with the same controller/sink; all view mutations run on main. */
internal object VehicleMapProjection {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var plan: VehicleMapPlan? = null
    private var bridge: VehicleProjectionClient? = null
    private var manager: WindowManager? = null
    private var root: FrameLayout? = null
    private var texture: ClusterVideoTexture? = null
    private var surface: Surface? = null
    private var phone: Any? = null
    private var command = 0L
    private var requested = false
    private var delivered = false
    private var firstFrame = false
    private var manual = false
    private var hidden = false
    private var suppressed = false
    private var routeSeen = false
    private var stage = "IDLE"
    @Volatile private var outputVisible = false
    @Volatile private var zoomAllowed = false
    private var commandAt = 0L
    private var attachedScreen: GeelyHudDisplay? = null
    private val poll = object : Runnable {
        override fun run() { refresh(); if (controller != null) main.postDelayed(this, 250L) }
    }

    fun attach(context: Context, next: CarPlayController, renderer: AndroidMediaSink, selection: VehicleMapPlan?) {
        if (selection == null) return
        main.post {
            if (controller === next) return@post
            closeNow()
            app = context.applicationContext; controller = next; sink = renderer; plan = selection
            if (selection.vehicleMode) bridge = VehicleProjectionClient(context)
            main.post(poll)
        }
    }
    fun owns(expected: CarPlayController?) = expected != null && controller === expected
    fun mapVisible() = outputVisible
    fun menuAllowsZoom(): Boolean = zoomAllowed
    fun close(expected: CarPlayController?) = main.post { if (expected == null || controller === expected) closeNow() }

    fun flyNavigation(): Int {
        if (controller?.clusterProjectionSessionToken() == null) return R.string.vehicle_map_reconnect
        if (outputVisible || requested && !suppressed) { manual = false; hidden = true; hide() }
        else { hidden = false; suppressed = false; manual = true; bridge?.resetLease(); refresh() }
        return if (hidden) R.string.projection_navigation_hidden else R.string.vehicle_map_waiting
    }

    private fun refresh() {
        val context = app ?: return
        val current = controller ?: return
        val selection = plan ?: return
        val route = CarPlayGlance.snapshot().maneuverType != null
        if (routeSeen && !route) { suppressed = false; hidden = false; manual = false }
        routeSeen = route
        val nextPhone = current.clusterProjectionSessionToken()
        if (phone != nextPhone) {
            hide(); phone = nextPhone; manual = false; hidden = false; suppressed = false
        }
        // carlito | Re-read the logical target size; an OEM can resize a display without replacing its ID.
        val screen = GeelyHudProjection.availableDisplays(context).firstOrNull {
            it.id == selection.screen.id && it.name == selection.screen.name
        }
        val display = screen?.let { context.getSystemService(DisplayManager::class.java)?.getDisplay(it.id) }
        val available = screen != null && screen.width > 0 && screen.height > 0 && display != null
        val wanted = VehicleMapSettings.enabled(context) && !hidden && !suppressed && available &&
            Settings.canDrawOverlays(context) && nextPhone != null && (route || manual)
        if (!wanted) { hide(); if (!available) stage = "DISPLAY_UNAVAILABLE"; return }
        if (root != null && attachedScreen != screen) {
            // Keep the negotiated phone canvas but refit its visible area to the resized screen.
            // A replacement Surface must deliver a new frame before native mode/zoom can resume.
            hide()
        }
        if (root == null && !createWindow(context, display!!, screen!!, selection)) { stage = "WINDOW_REJECTED"; return }
        if (!requested && surface?.isValid == true) {
            requested = true; delivered = false; firstFrame = false
            val generation = ++command
            commandAt = SystemClock.elapsedRealtime()
            stage = "WAITING_COMMAND"
            current.setProjectionUiShown(this, true) { accepted -> main.post {
                if (generation != command || controller !== current || !requested) return@post
                if (accepted) { delivered = true; stage = "WAITING_FRAME" }
                else { hide(); suppressed = true; stage = "COMMAND_REJECTED" }
            } }
        }
        val status = bridge?.status()
        bridge?.update(requested, delivered && firstFrame)
        if (status?.getBoolean("suppressed") == true) { hide(); suppressed = true; stage = "EXTERNAL_EXIT"; return }
        val ready = delivered && firstFrame && (!selection.vehicleMode || status?.getBoolean("ready") == true)
        outputVisible = ready
        texture?.alpha = if (ready) 1f else 0f
        current.setDashboardMapOutputVisible(ready)
        zoomAllowed = ready && (!selection.vehicleMode || status?.getBoolean("menuAllowsZoom") == true)
        if (ready) stage = "ACTIVE"
        else if (selection.vehicleMode && status?.getBoolean("ready") != true) stage = "WAITING_VEHICLE_MODE"
        // Static maps may legitimately stop updating. Only the initial frame has a deadline.
        if (requested && !firstFrame && SystemClock.elapsedRealtime() - commandAt > 15_000L) {
            hide(); suppressed = true; stage = "FRAME_TIMEOUT"
        }
    }

    private fun createWindow(context: Context, display: Display, screen: GeelyHudDisplay, selection: VehicleMapPlan): Boolean {
        val displayContext = context.createDisplayContext(display)
        val wm = displayContext.getSystemService(WindowManager::class.java) ?: return false
        val container = FrameLayout(displayContext).apply { setBackgroundColor(Color.TRANSPARENT) }
        val viewport = FrameLayout(displayContext).apply { clipChildren = true; clipToPadding = true }
        // carlito | Fit both axes with one scale: full-height automatic output fills a matching
        // screen, while a manual crop never stretches or spills beyond a narrower display.
        val scale = min(screen.width.toDouble() / selection.width, screen.height.toDouble() / selection.visibleHeight)
        val viewportWidth = (selection.width * scale).roundToInt().coerceIn(1, screen.width)
        val scaledHeight = (selection.height * scale).roundToInt().coerceAtLeast(1)
        val visibleHeight = (selection.visibleHeight * scale).roundToInt().coerceIn(1, screen.height)
        val video = ClusterVideoTexture(displayContext, selection.width, selection.height, onFrame = {
            if (delivered && requested) firstFrame = true
        }) { next ->
            surface?.let { old -> sink?.clearSurface(111, old) }
            surface = next
            if (next != null) sink?.setSurface(111, next)
            else { firstFrame = false; outputVisible = false; controller?.setDashboardMapOutputVisible(false) }
        }.apply { alpha = 0f }
        viewport.addView(video, FrameLayout.LayoutParams(viewportWidth, scaledHeight))
        container.addView(viewport, FrameLayout.LayoutParams(viewportWidth, visibleHeight, Gravity.CENTER))
        val params = WindowManager.LayoutParams(screen.width, screen.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; title = "DiPlay Map"
        }
        return try {
            manager = wm; root = container; texture = video; attachedScreen = screen
            wm.addView(container, params); true
        } catch (_: RuntimeException) { destroyWindow(); false }
    }

    private fun hide() {
        outputVisible = false; zoomAllowed = false; texture?.alpha = 0f
        controller?.setDashboardMapOutputVisible(false)
        val wasRequested = requested
        requested = false; delivered = false; firstFrame = false; command++
        bridge?.update(false, false)
        if (wasRequested) controller?.setProjectionUiShown(this, false) {}
        destroyWindow()
    }
    private fun destroyWindow() {
        texture?.close(); texture = null
        root?.let { runCatching { manager?.removeViewImmediate(it) } }; root = null; manager = null; attachedScreen = null
    }
    private fun closeNow() {
        main.removeCallbacks(poll)
        hide(); controller?.releaseProjectionUi(this)
        bridge?.close(); bridge = null
        controller = null; sink = null; app = null; plan = null; phone = null
        manual = false; hidden = false; suppressed = false; routeSeen = false; stage = "IDLE"
    }
    fun diagnostics() = "vehicleMap stage=$stage requested=$requested delivered=$delivered firstFrame=$firstFrame " +
        "visible=$outputVisible menuAllowsZoom=$zoomAllowed target=${plan?.screen?.id} bridge=${bridge?.status()?.getString("stage")}"
}
