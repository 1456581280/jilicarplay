package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import com.shilapi.xcertplay.host.R
import kotlin.math.min
import kotlin.math.roundToInt

/** Native, reflowing home UI adapted from the owner's supplied SVG assets. */
internal class WarmHomeScreen(private val context: Context) {
    private val config = context.resources.configuration
    private val scale = min(config.screenWidthDp / 1672f, config.screenHeightDp / 941f).coerceIn(.46f, 1.15f)
    internal val twoColumns = config.screenWidthDp / config.fontScale >= 700
    private val textColor = Color.rgb(250, 245, 239)
    private val muted = Color.rgb(183, 197, 222)
    private val peach = Color.rgb(255, 208, 167)
    lateinit var status: TextView; private set
    lateinit var connect: Button; private set
    lateinit var disconnect: Button; private set
    lateinit var wirelessCard: LinearLayout; private set
    private fun dp(n: Float) = (n * context.resources.displayMetrics.density).roundToInt()
    private fun unit(n: Int, floor: Int = 0) = dp((n * scale).coerceAtLeast(floor.toFloat()))
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun label(text: String, size: Int, color: Int = muted, bold: Boolean = false) = TextView(context).apply {
        this.text = text; textSize = (size * scale).coerceAtLeast(14f)
        setTextColor(color); includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setLineSpacing(unit(5).toFloat(), 1f)
    }
    private fun surface(primary: Boolean = false, card: Boolean = false) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (primary) intArrayOf(Color.rgb(255, 224, 189), Color.rgb(255, 199, 155))
        else intArrayOf(0xee2b374f.toInt(), 0xe61c283b.toInt())
    ).apply {
        cornerRadius = unit(if (card) 35 else 37, 18).toFloat()
        setStroke(dp(1f), if (primary) 0xffffe9ca.toInt() else 0x9959677e.toInt())
    }
    private fun action(text: String, icon: Int, primary: Boolean = false, click: () -> Unit) = object : Button(context) {
        private val glyph = context.getDrawable(icon)!!.mutate()
        override fun onDraw(canvas: Canvas) {
            val size = unit(46, 26); val gap = unit(12, 8)
            val shift = (size + gap) / 2f
            canvas.save(); canvas.translate(shift, 0f); super.onDraw(canvas); canvas.restore()
            val left = ((width - paint.measureText(text.toString()) - size - gap) / 2f).toInt().coerceAtLeast(unit(16))
            glyph.setBounds(left, (height-size)/2, left+size, (height+size)/2); glyph.draw(canvas)
        }
    }.apply {
        this.text = text; isAllCaps = false; textSize = (31 * scale).coerceAtLeast(18f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) 0xff18273f.toInt() else textColor)
        background = RippleDrawable(ColorStateList.valueOf(0x30ffffff), surface(primary), null)
        minimumWidth = 0; minimumHeight = unit(if (primary) 98 else 84, 48)
        minHeight = minimumHeight; gravity = Gravity.CENTER
        setPadding(unit(28, 16), unit(14, 8), unit(28, 16), unit(14, 8))
        setOnClickListener { click() }
        // Keep disabled/keyboard focus states legible without changing the connection behavior.
        setOnFocusChangeListener { _, focused -> alpha = if (focused) .82f else 1f }
    }
    private fun LinearLayout.add(view: View, top: Int = 0) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = unit(top) })
    }
    private fun columns(left: View, right: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
        addView(left, LinearLayout.LayoutParams(0, -2, 1.55f))
        addView(Space(context), LinearLayout.LayoutParams(unit(52, 20), 1))
        addView(right, LinearLayout.LayoutParams(0, -2, 1f))
    }

    fun populate(content: LinearLayout, hint: String, version: String,
                 onConnect: () -> Unit, onChoose: () -> Unit, onDisconnect: () -> Unit,
                 onUsb: () -> Unit, onSettings: () -> Unit, onUpdate: () -> Unit, onGitHub: () -> Unit) {
        content.setPadding(unit(54, 16), unit(48, 20), unit(54, 16), unit(36, 16))
        val hero = column().apply {
            add(label(context.getString(R.string.journey_eyebrow), 21, peach).apply { letterSpacing = .16f })
            add(label(context.getString(R.string.journey_title), if (twoColumns) 70 else 60, textColor, true), 28)
            add(label(context.getString(R.string.journey_subtitle), 31), 18)
        }
        val logo = ImageView(context).apply {
            setImageDrawable(JourneyIcon(context)); scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val branding = column().apply {
            gravity = Gravity.CENTER
            addView(logo, LinearLayout.LayoutParams(unit(213, 96), unit(200, 90)).apply { topMargin = unit(35) })
        }
        if (twoColumns) content.add(columns(hero, branding)) else content.add(hero)

        wirelessCard = column().apply {
            background = surface(card = true); setPadding(unit(40, 20), unit(32, 18), unit(40, 20), unit(32, 18))
        }
        val wireless = label(context.getString(R.string.journey_wireless), 24, peach, true)
        val wifi = context.getDrawable(R.drawable.home_wifi)!!
        wifi.setBounds(0, 0, unit(44, 24), unit(44, 24))
        wireless.setCompoundDrawablesRelative(wifi, null, null, null); wireless.compoundDrawablePadding = unit(20, 10)
        wirelessCard.add(wireless)
        status = label(context.getString(R.string.ready_when_you_are), 42, textColor, true)
        wirelessCard.add(status, 18)
        connect = action(context.getString(R.string.journey_connect), R.drawable.home_link, true, onConnect)
        wirelessCard.add(connect, 26)
        wirelessCard.add(label(hint, 23), 24)
        wirelessCard.add(action(context.getString(R.string.journey_choose_device), R.drawable.home_phone, click = onChoose), 24)
        disconnect = action(context.getString(R.string.disconnect), R.drawable.home_disconnect, click = onDisconnect)
        wirelessCard.add(disconnect, 14)

        val right = column().apply {
            add(action(context.getString(R.string.connect_with_usb), R.drawable.home_usb, click = onUsb))
            add(label(context.getString(R.string.journey_usb_hint), 24).apply { setPadding(unit(28), 0, unit(18), 0) }, 22)
            add(action(context.getString(R.string.settings), R.drawable.home_settings, click = onSettings), 34)
            add(label(context.getString(R.string.journey_settings_hint), 23).apply { setPadding(unit(28), 0, unit(18), 0) }, 22)
            add(label(context.getString(R.string.journey_release, version), 18).apply {
                minHeight = dp(48f); gravity = Gravity.CENTER_VERTICAL; setOnClickListener { onUpdate() }
                contentDescription = context.getString(R.string.update_check)
            }, 18)
            add(label("GitHub · 1456581280/jilicarplay", 17, peach).apply {
                minHeight = dp(48f); gravity = Gravity.CENTER_VERTICAL; setOnClickListener { onGitHub() }
            })
        }
        if (twoColumns) content.add(columns(wirelessCard, right), 30)
        else { content.add(wirelessCard, 26); content.add(right, 20) }
    }
}

/** Scalable scenery; SVG palette, with no screenshot text baked into the background. */
internal class JourneyBackdrop(context: Context) : Drawable() {
    private val mountains = BitmapFactory.decodeResource(context.resources, R.drawable.home_mountains)
    private val roadImage = BitmapFactory.decodeResource(context.resources, R.drawable.home_road)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xff15253e.toInt(), 0xff0c1b32.toInt(), 0xff121827.toInt()), floatArrayOf(0f, .57f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = RadialGradient(w*.87f, h*.23f, w*.6f, 0x60a66e68, 0x00a66e68, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint); paint.shader = null
        fun hill(y: Float, color: Int, offset: Float) {
            val p = Path().apply {
                moveTo(0f, h*y)
                cubicTo(w*.18f, h*(y-.14f), w*.18f, h*(y+.07f), w*.36f, h*(y-.04f))
                cubicTo(w*.48f, h*(y-.17f+offset), w*.51f, h*(y+.04f), w*.63f, h*y)
                cubicTo(w*.8f, h*(y-.15f), w*.82f, h*(y+.02f), w, h*(y-.13f))
                lineTo(w,h); lineTo(0f,h); close()
            }; paint.color = color; canvas.drawPath(p,paint)
        }
        hill(.3f, 0xff15243a.toInt(), 0f); hill(.39f, 0xff102037.toInt(), .06f)
        // Clip the supplied screenshot fragments to scenery only, excluding baked-in text.
        fun fragment(bitmap: Bitmap, source: Rect, target: RectF, opacity: Int, fadeLeft: Boolean) {
            val layer = canvas.saveLayer(target, null)
            paint.alpha = opacity
            canvas.drawBitmap(bitmap, source, target, paint)
            paint.alpha = 255; paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            paint.shader = LinearGradient(0f, target.top, 0f, target.bottom,
                if (fadeLeft) intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK) else intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(target, paint)
            if (fadeLeft) {
                paint.shader = LinearGradient(target.left, 0f, target.right, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
                canvas.drawRect(target, paint)
            }
            paint.shader = null; paint.xfermode = null; canvas.restoreToCount(layer)
        }
        fragment(mountains, Rect(24,0,mountains.width,mountains.height), RectF(0f,0f,w,h*.72f), 200, false)
        fragment(roadImage, Rect(0,40,roadImage.width,roadImage.height), RectF(w*.48f,h*.64f,w,h), 210, true)

    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.OPAQUE
}

/** Crop only at draw time, keeping the supplied source image intact. */
internal class JourneyIcon(context: Context) : Drawable() {
    private val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.home_journey_icon)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun draw(canvas: Canvas) {
        val rect = RectF(bounds)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(rect, rect.width()*.23f, rect.height()*.23f, Path.Direction.CW) })
        canvas.drawBitmap(bitmap, Rect(28,8,213,196), rect, paint)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
