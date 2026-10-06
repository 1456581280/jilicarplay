// carlito | Navigation and calibrated vehicle cards share one secondary-display window.
package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.shilapi.xcertplay.hud.CarPlayHudGuidance
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.min

internal data class GeelyHudDisplay(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
)

/** CarPlay maneuver projection for a Geely head unit's secondary HUD display. */
internal object GeelyHudProjection : DisplayManager.DisplayListener {
    private const val TAG = "DiPlay-GeelyHud"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activityRef: WeakReference<Activity>? = null
    private var displayManager: DisplayManager? = null
    private var windowManager: WindowManager? = null
    private var hudView: GeelyProjectionView? = null
    private var vehicleReader: ProjectionVehicleReader? = null
    private var vehicleFrame = ProjectionVehicleFrame()
    private var windowStage = "IDLE"
    private val refreshValues = object : Runnable {
        override fun run() {
            hudView?.invalidate()
            if (hudView != null) mainHandler.postDelayed(this, 1_000L)
        }
    }
    private var attachedDisplayId = Display.INVALID_DISPLAY
    private var attachedWidth = 0
    private var attachedHeight = 0
    private var guidance: CarPlayHudGuidance? = null
    private var navigationHidden = false
    private val expireGuidance = Runnable {
        guidance = null
        refresh()
    }

    fun attach(activity: Activity) {
        mainHandler.post {
            if (activityRef?.get() !== activity) {
                detachWindow()
                stopVehicleReader()
                displayManager?.unregisterDisplayListener(this)
                activityRef = WeakReference(activity)
                displayManager = activity.getSystemService(DisplayManager::class.java)
                displayManager?.registerDisplayListener(this, mainHandler)
            }
            refresh()
        }
    }

    fun detach(activity: Activity) {
        mainHandler.post {
            if (activityRef?.get() !== activity) return@post
            detachWindow()
            stopVehicleReader()
            mainHandler.removeCallbacks(expireGuidance)
            guidance = null
            navigationHidden = false
            displayManager?.unregisterDisplayListener(this)
            displayManager = null
            activityRef = null
        }
    }

    fun update(value: CarPlayHudGuidance?) {
        mainHandler.post {
            mainHandler.removeCallbacks(expireGuidance)
            guidance = value
            if (value != null) mainHandler.postDelayed(expireGuidance, 30_000L)
            refresh()
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        AirPlayPersistence.saveGeelyHudEnabled(context, enabled)
        if (enabled && context is Activity && !Settings.canDrawOverlays(context)) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }.onFailure { Log.w(TAG, "Could not open HUD display permission settings", it) }
        }
        mainHandler.post { if (enabled) navigationHidden = false; refresh() }
    }

    fun availableDisplays(context: Context): List<GeelyHudDisplay> =
        context.getSystemService(DisplayManager::class.java)?.displays.orEmpty()
            .asSequence()
            .filter { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }
            .map {
                val (width, height) = displaySize(context, it)
                GeelyHudDisplay(
                    id = it.displayId,
                    name = it.name,
                    width = width,
                    height = height,
                )
            }
            .sortedBy(GeelyHudDisplay::id)
            .toList()

    fun selectDisplay(context: Context, display: GeelyHudDisplay?) {
        AirPlayPersistence.saveGeelyHudDisplay(
            context,
            display?.id ?: Display.INVALID_DISPLAY,
            display?.name,
        )
        mainHandler.post(::refresh)
    }

    fun setScale(context: Context, percent: Int) {
        AirPlayPersistence.saveGeelyHudScalePercent(context, percent)
        mainHandler.post {
            hudView?.scalePercent = percent
            refresh()
        }
    }

    fun applyLayout() = mainHandler.post(::refresh)
    fun currentGuidance(): CarPlayHudGuidance? = guidance
    fun currentVehicleFrame(): ProjectionVehicleFrame = vehicleFrame

    fun threeFingerEnabled(context: Context) = context.getSharedPreferences("geely_projection_layout", Context.MODE_PRIVATE)
        .getBoolean("three_finger_navigation", false)
    fun setThreeFingerEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("geely_projection_layout", Context.MODE_PRIVATE).edit()
            .putBoolean("three_finger_navigation", enabled).apply()
    }

    /** carlito | Switch only the guidance element; vehicle cards keep their own visibility. */
    fun flyNavigation(activity: Activity): Int {
        if (guidance == null) return com.shilapi.xcertplay.host.R.string.projection_no_guidance
        if (!Settings.canDrawOverlays(activity)) return com.shilapi.xcertplay.host.R.string.projection_permission_required
        val displays = availableDisplays(activity)
        val id = AirPlayPersistence.loadGeelyHudDisplayId(activity)
        val name = AirPlayPersistence.loadGeelyHudDisplayName(activity)
        val selected = if (id != Display.INVALID_DISPLAY || name != null) {
            displays.firstOrNull { it.id == id && (name == null || it.name == name) }
                ?: name?.let { value -> displays.filter { it.name == value }.singleOrNull() }
        } else displays.filter { it.name.contains("hud", true) }.singleOrNull()
        if (selected == null) return com.shilapi.xcertplay.host.R.string.projection_select_display
        if (GeelyProjectionLayout.load(activity).none { it.field == ProjectionField.NAVIGATION && it.visible })
            return com.shilapi.xcertplay.host.R.string.projection_add_navigation
        navigationHidden = AirPlayPersistence.loadGeelyHudEnabled(activity) && !navigationHidden
        if (!navigationHidden) AirPlayPersistence.saveGeelyHudEnabled(activity, true)
        refresh()
        return if (windowStage.startsWith("WINDOW_REJECTED")) com.shilapi.xcertplay.host.R.string.projection_open_failed
            else if (navigationHidden) com.shilapi.xcertplay.host.R.string.projection_navigation_hidden
            else com.shilapi.xcertplay.host.R.string.projection_navigation_shown
    }

    fun diagnosticReport(context: Context): String {
        val selectedId = AirPlayPersistence.loadGeelyHudDisplayId(context)
        val selectedName = AirPlayPersistence.loadGeelyHudDisplayName(context).orEmpty()
        val displays = availableDisplays(context)
        return buildString {
            append("enabled=${AirPlayPersistence.loadGeelyHudEnabled(context)} ")
            append("overlayPermission=${Settings.canDrawOverlays(context)} ")
            append("selectedId=$selectedId selectedName=${selectedName.ifBlank { "automatic" }} ")
            append("scale=${AirPlayPersistence.loadGeelyHudScalePercent(context)}% ")
            append("attachedId=$attachedDisplayId attachedSize=${attachedWidth}x$attachedHeight")
            append(" stage=$windowStage vehicle=${vehicleReader?.stage ?: "IDLE"}")
            appendLine()
            append("availableDisplays=")
            if (displays.isEmpty()) append("none") else append(
                displays.joinToString { "${it.id}:${it.name}:${it.width}x${it.height}" },
            )
        }
    }

    override fun onDisplayAdded(displayId: Int) = refresh()
    override fun onDisplayRemoved(displayId: Int) = refresh()
    override fun onDisplayChanged(displayId: Int) = refresh()

    private fun refresh() {
        val activity = activityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            detachWindow()
            stopVehicleReader()
            return
        }
        if (!AirPlayPersistence.loadGeelyHudEnabled(activity)) {
            windowStage = "DISABLED"
            detachWindow()
            stopVehicleReader()
            return
        }
        val elements = GeelyProjectionLayout.load(activity)
        val visible = elements.filter { it.visible && !(navigationHidden && it.field == ProjectionField.NAVIGATION) }
        if (visible.any { it.field.property != null }) {
            if (vehicleReader == null) vehicleReader = ProjectionVehicleReader(activity) { value ->
                vehicleFrame = value; hudView?.vehicle = value
            }
        } else stopVehicleReader()
        if (!Settings.canDrawOverlays(activity) || visible.isEmpty() ||
            guidance == null && visible.all { it.field == ProjectionField.NAVIGATION }) {
            windowStage = if (!Settings.canDrawOverlays(activity)) "OVERLAY_PERMISSION_REQUIRED" else "WAITING_FOR_CONTENT"
            detachWindow()
            return
        }
        val displays = displayManager?.displays
            ?.filter { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }
            .orEmpty()
        val savedId = AirPlayPersistence.loadGeelyHudDisplayId(activity)
        val savedName = AirPlayPersistence.loadGeelyHudDisplayName(activity)
        val display = if (savedId != Display.INVALID_DISPLAY || savedName != null) {
            displays.firstOrNull { it.displayId == savedId && (savedName == null || it.name == savedName) }
                ?: savedName?.let { name -> displays.filter { it.name == name }.singleOrNull() }
        } else {
            // carlito | Ambiguous/rear/cluster screens require an explicit user selection.
            displays.filter { it.name.contains("hud", ignoreCase = true) }.singleOrNull()
        }
        if (display == null) {
            windowStage = "DISPLAY_SELECTION_REQUIRED"
            detachWindow()
            return
        }
        val displayContext = activity.createDisplayContext(display)
        val (displayWidth, displayHeight) = displaySize(activity, display)
        if (attachedDisplayId != display.displayId ||
            attachedWidth != displayWidth || attachedHeight != displayHeight
        ) {
            detachWindow()
            val manager = displayContext.getSystemService(WindowManager::class.java) ?: return
            val view = GeelyProjectionView(displayContext)
            val params = WindowManager.LayoutParams(
                displayWidth,
                displayHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "DiPlay HUD Navigation"
            }
            try {
                manager.addView(view, params)
                windowManager = manager
                hudView = view
                attachedDisplayId = display.displayId
                attachedWidth = displayWidth
                attachedHeight = displayHeight
            } catch (error: RuntimeException) {
                windowStage = "WINDOW_REJECTED:${error.javaClass.simpleName}"
                Log.w(TAG, "Could not open the HUD display", error)
                runCatching { manager.removeViewImmediate(view) }
                return
            }
        }
        hudView?.apply {
            this.elements = visible
            this.scalePercent = AirPlayPersistence.loadGeelyHudScalePercent(activity)
            this.guidance = GeelyHudProjection.guidance
            this.vehicle = vehicleFrame
        }
        windowStage = "ACTIVE"
        mainHandler.removeCallbacks(refreshValues)
        mainHandler.postDelayed(refreshValues, 1_000L)
    }

    private fun displaySize(context: Context, display: Display): Pair<Int, Int> {
        val metrics = context.createDisplayContext(display).resources.displayMetrics
        return (metrics.widthPixels.takeIf { it > 0 } ?: display.mode.physicalWidth) to
            (metrics.heightPixels.takeIf { it > 0 } ?: display.mode.physicalHeight)
    }

    private fun detachWindow() {
        mainHandler.removeCallbacks(refreshValues)
        val current = hudView
        hudView = null
        runCatching { current?.let { windowManager?.removeViewImmediate(it) } }
        windowManager = null
        attachedDisplayId = Display.INVALID_DISPLAY
        attachedWidth = 0
        attachedHeight = 0
    }

    private fun stopVehicleReader() {
        vehicleReader?.close(); vehicleReader = null
        vehicleFrame = ProjectionVehicleFrame()
        hudView?.vehicle = vehicleFrame
    }
}

internal class GeelyHudGuidanceView(context: Context, initialScalePercent: Int) : View(context) {
    var scalePercent = initialScalePercent
        set(value) {
            field = value
            invalidate()
        }

    var guidance: CarPlayHudGuidance? = null
        set(value) {
            field = value
            invalidate()
        }

    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(115, 255, 198)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val arrow = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val route = guidance ?: return
        val checkpoint = canvas.save()
        val contentScale = scalePercent / 100f
        canvas.scale(contentScale, contentScale, width * 0.5f, height * 0.5f)
        val heightScale = min(height.toFloat(), width * 0.45f)
        val centerY = height * 0.5f
        val iconSize = heightScale * 0.47f
        val left = width * 0.22f
        drawArrow(canvas, route.maneuverCode, left, centerY, iconSize)

        val distance = if (route.distanceMeters >= 1_000) {
            String.format(Locale.getDefault(), "%.1f km", route.distanceMeters / 1_000f)
        } else {
            String.format(Locale.getDefault(), "%d m", route.distanceMeters.coerceAtLeast(0))
        }
        val textLeft = width * 0.43f
        accent.textSize = heightScale * 0.19f
        canvas.drawText(distance, textLeft, centerY + accent.textSize * 0.18f, accent)
        if (route.road.isNotBlank()) {
            roadPaint.textSize = heightScale * 0.095f
            val maxWidth = (width - textLeft - width * 0.07f).coerceAtLeast(0f)
            val road = TextUtils.ellipsize(route.road, android.text.TextPaint(roadPaint), maxWidth, TextUtils.TruncateAt.END)
            canvas.drawText(road.toString(), textLeft, centerY + accent.textSize * 0.75f, roadPaint)
        }
        canvas.restoreToCount(checkpoint)
    }

    private fun drawArrow(canvas: Canvas, maneuver: Int, x: Float, y: Float, size: Float) {
        arrow.reset()
        when (maneuver) {
            1, 2 -> {
                arrow.moveTo(18f, 36f); arrow.lineTo(18f, 0f)
                arrow.quadTo(18f, -20f, -2f, -20f); arrow.lineTo(-38f, -20f)
                arrow.moveTo(-22f, -34f); arrow.lineTo(-38f, -20f); arrow.lineTo(-22f, -6f)
            }
            3, 5 -> {
                arrow.moveTo(18f, 36f); arrow.lineTo(18f, 0f); arrow.lineTo(-28f, -36f)
                arrow.moveTo(-30f, -16f); arrow.lineTo(-28f, -36f); arrow.lineTo(-8f, -36f)
            }
            7, 8 -> {
                arrow.moveTo(22f, 36f); arrow.lineTo(22f, -8f)
                arrow.cubicTo(22f, -48f, -22f, -48f, -22f, -8f); arrow.lineTo(-22f, 16f)
                arrow.moveTo(-36f, 0f); arrow.lineTo(-22f, 16f); arrow.lineTo(-8f, 0f)
            }
            11 -> {
                arrow.moveTo(0f, 36f); arrow.lineTo(0f, -38f)
                arrow.moveTo(-14f, -22f); arrow.lineTo(0f, -38f); arrow.lineTo(14f, -22f)
            }
            else -> return
        }
        val checkpoint = canvas.save()
        canvas.translate(x, y)
        val scale = size / 88f
        canvas.scale(if (maneuver in listOf(2, 5, 8)) -scale else scale, scale)
        accent.style = Paint.Style.STROKE
        accent.strokeWidth = 9f
        accent.strokeCap = Paint.Cap.ROUND
        accent.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(arrow, accent)
        accent.style = Paint.Style.FILL
        canvas.restoreToCount(checkpoint)
    }
}
