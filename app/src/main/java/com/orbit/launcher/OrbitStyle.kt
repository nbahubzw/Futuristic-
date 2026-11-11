package com.orbit.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.min

// Orbit design kit for the screens built with Android views: iOS system colours,
// type weights, a Reminders-style check circle and simple line icons.

class IosColors(val dark: Boolean) {
    val label = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val secondary = if (dark) 0x99EBEBF5.toInt() else 0x993C3C43.toInt()
    val tertiary = if (dark) 0x4DEBEBF5 else 0x4D3C3C43
    val separator = if (dark) 0x5C545458 else 0x2E3C3C43
    val ring = if (dark) 0xFF5A5A5E.toInt() else 0xFFC7C7CC.toInt()
    val card = if (dark) 0xE61C1C1E.toInt() else 0xF0FFFFFF.toInt()
    val cell = if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    val sheet = if (dark) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()
    val sheetCell = if (dark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
    val fill = if (dark) 0x3D787880 else 0x1F787880
    val blue = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    val orange = if (dark) 0xFFFF9F0A.toInt() else 0xFFFF9500.toInt()
    val red = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
    val green = if (dark) 0xFF30D158.toInt() else 0xFF34C759.toInt()
    val indigo = if (dark) 0xFF5E5CE6.toInt() else 0xFF5856D6.toInt()
    val teal = if (dark) 0xFF64D2FF.toInt() else 0xFF32ADE6.toInt()
    val gray = if (dark) 0xFF8E8E93.toInt() else 0xFF8E8E93.toInt()

    companion object {
        fun of(context: Context): IosColors =
            IosColors((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
    }
}

object IosType {
    val thin: Typeface = Typeface.create(Typeface.DEFAULT, 200, false)
    val light: Typeface = Typeface.create(Typeface.DEFAULT, 300, false)
    val regular: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)
    val medium: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    val semibold: Typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    val bold: Typeface = Typeface.create(Typeface.DEFAULT, 700, false)
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

fun Context.label(
    text: CharSequence = "",
    size: Float,
    color: Int,
    face: Typeface = IosType.regular,
    lines: Int = 1
): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    typeface = face
    includeFontPadding = false
    maxLines = lines
    ellipsize = TextUtils.TruncateAt.END
    letterSpacing = when {
        size >= 28f -> -0.012f
        size >= 20f -> -0.006f
        else -> 0f
    }
}

fun rounded(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

fun roundedTop(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
}

/** iOS-style touch highlight: dims while pressed, click still fires normally. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressable(onClick: () -> Unit) {
    setOnClickListener { onClick() }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().alpha(0.55f).setDuration(60).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().alpha(1f).setDuration(180).start()
        }
        false
    }
}

/** Reminders-style completion circle. */
class CheckCircle(context: Context, private val colors: IosColors) : View(context) {
    var accent: Int = colors.orange
    var checked: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val tick = Path()

    fun pop() {
        animate().scaleX(0.82f).scaleY(0.82f).setDuration(90).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val sw = s * 0.075f
        val r = s / 2f - sw
        if (checked) {
            paint.style = Paint.Style.FILL
            paint.color = accent
            canvas.drawCircle(cx, cy, r + sw / 2, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = s * 0.09f
            paint.color = 0xFFFFFFFF.toInt()
            tick.reset()
            tick.moveTo(cx - r * 0.42f, cy + r * 0.02f)
            tick.lineTo(cx - r * 0.10f, cy + r * 0.34f)
            tick.lineTo(cx + r * 0.46f, cy - r * 0.30f)
            canvas.drawPath(tick, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = sw
            paint.color = colors.ring
            canvas.drawCircle(cx, cy, r, paint)
        }
    }
}

/** Small line icons drawn in code, in the spirit of SF Symbols. */
class Glyph(context: Context, private val kind: Kind, var color: Int, private val strokeDp: Float = 1.8f) : View(context) {
    enum class Kind { PLUS, CHEVRON, MIC, CALENDAR, CLOCK, REPEAT, LIST, BELL, KEYBOARD, SUN }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height) / 24f
        val ox = (width - 24 * s) / 2f
        val oy = (height - 24 * s) / 2f
        fun x(v: Float) = ox + v * s
        fun y(v: Float) = oy + v * s
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeDp * resources.displayMetrics.density
        path.reset()
        when (kind) {
            Kind.PLUS -> {
                canvas.drawLine(x(12f), y(5f), x(12f), y(19f), paint)
                canvas.drawLine(x(5f), y(12f), x(19f), y(12f), paint)
            }
            Kind.CHEVRON -> {
                path.moveTo(x(9.5f), y(5.5f)); path.lineTo(x(15.5f), y(12f)); path.lineTo(x(9.5f), y(18.5f))
                canvas.drawPath(path, paint)
            }
            Kind.MIC -> {
                oval.set(x(9f), y(3f), x(15f), y(14.5f))
                canvas.drawRoundRect(oval, 3f * s, 3f * s, paint)
                oval.set(x(5.5f), y(5.5f), x(18.5f), y(17.5f))
                canvas.drawArc(oval, 20f, 140f, false, paint)
                canvas.drawLine(x(12f), y(17.5f), x(12f), y(21f), paint)
            }
            Kind.CALENDAR -> {
                oval.set(x(4f), y(5.5f), x(20f), y(20f))
                canvas.drawRoundRect(oval, 3f * s, 3f * s, paint)
                canvas.drawLine(x(4f), y(10f), x(20f), y(10f), paint)
                canvas.drawLine(x(8.5f), y(3.5f), x(8.5f), y(7f), paint)
                canvas.drawLine(x(15.5f), y(3.5f), x(15.5f), y(7f), paint)
            }
            Kind.CLOCK -> {
                canvas.drawCircle(x(12f), y(12f), 8.5f * s, paint)
                path.moveTo(x(12f), y(7.5f)); path.lineTo(x(12f), y(12f)); path.lineTo(x(15f), y(14f))
                canvas.drawPath(path, paint)
            }
            Kind.REPEAT -> {
                path.moveTo(x(5f), y(11f)); path.lineTo(x(5f), y(9.5f))
                path.quadTo(x(5f), y(7f), x(7.5f), y(7f)); path.lineTo(x(18f), y(7f))
                path.moveTo(x(15.5f), y(4.5f)); path.lineTo(x(18f), y(7f)); path.lineTo(x(15.5f), y(9.5f))
                path.moveTo(x(19f), y(13f)); path.lineTo(x(19f), y(14.5f))
                path.quadTo(x(19f), y(17f), x(16.5f), y(17f)); path.lineTo(x(6f), y(17f))
                path.moveTo(x(8.5f), y(14.5f)); path.lineTo(x(6f), y(17f)); path.lineTo(x(8.5f), y(19.5f))
                canvas.drawPath(path, paint)
            }
            Kind.LIST -> {
                paint.style = Paint.Style.FILL
                for (row in floatArrayOf(7f, 12f, 17f)) canvas.drawCircle(x(5.5f), y(row), 1.4f * s, paint)
                paint.style = Paint.Style.STROKE
                for (row in floatArrayOf(7f, 12f, 17f)) canvas.drawLine(x(9.5f), y(row), x(19.5f), y(row), paint)
            }
            Kind.BELL -> {
                path.moveTo(x(6f), y(16.5f))
                path.lineTo(x(6.8f), y(15f))
                path.quadTo(x(7.2f), y(14f), x(7.2f), y(11.5f))
                path.quadTo(x(7.2f), y(5.5f), x(12f), y(5.5f))
                path.quadTo(x(16.8f), y(5.5f), x(16.8f), y(11.5f))
                path.quadTo(x(16.8f), y(14f), x(17.2f), y(15f))
                path.lineTo(x(18f), y(16.5f))
                path.close()
                canvas.drawPath(path, paint)
                canvas.drawLine(x(10.5f), y(19.5f), x(13.5f), y(19.5f), paint)
            }
            Kind.KEYBOARD -> {
                oval.set(x(3f), y(6f), x(21f), y(18f))
                canvas.drawRoundRect(oval, 2.5f * s, 2.5f * s, paint)
                paint.style = Paint.Style.FILL
                for (cx in floatArrayOf(7f, 10.3f, 13.7f, 17f)) canvas.drawCircle(x(cx), y(10f), 0.9f * s, paint)
                paint.style = Paint.Style.STROKE
                canvas.drawLine(x(8f), y(14f), x(16f), y(14f), paint)
            }
            Kind.SUN -> {
                canvas.drawCircle(x(12f), y(12f), 4f * s, paint)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = Math.cos(a).toFloat()
                    val sn = Math.sin(a).toFloat()
                    canvas.drawLine(x(12f + c * 6.5f), y(12f + sn * 6.5f), x(12f + c * 8.5f), y(12f + sn * 8.5f), paint)
                }
            }
        }
    }
}

/** A white glyph on a rounded coloured tile, like iOS Settings rows. */
fun Context.iconTile(kind: Glyph.Kind, tint: Int, sizeDp: Int = 29): View {
    val tile = android.widget.FrameLayout(this)
    tile.background = rounded(tint, dp(7).toFloat())
    tile.addView(Glyph(this, kind, 0xFFFFFFFF.toInt(), 2f), android.widget.FrameLayout.LayoutParams(dp(sizeDp - 9), dp(sizeDp - 9), android.view.Gravity.CENTER))
    tile.layoutParams = android.view.ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
    return tile
}
