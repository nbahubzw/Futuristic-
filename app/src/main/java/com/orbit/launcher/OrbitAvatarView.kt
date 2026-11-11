package com.orbit.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Orbit hero, avatar: an animated hooded figure with a glowing visor, faint falling code and fine rings.
// Colour shows the mood using iOS system colours: teal idle, pink listening, orange thinking, green speaking.

class OrbitAvatarView(context: Context) : View(context) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode: Mode = Mode.IDLE
        set(value) {
            field = value
            invalidate()
        }

    /** Microphone loudness 0..1 while listening. */
    var level: Float = 0f

    private val startedAt = SystemClock.uptimeMillis()
    private var running = false
    private val density = resources.displayMetrics.density

    private val glyphs = "01ABCDEF0123456789<>/\\{}[]#\$%&*+=".toCharArray()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val code = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }

    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private val disc = Path()
    private val hood = Path()
    private val face = RectF()
    private val faceClip = Path()
    private val visor = RectF()
    private val tmp = RectF()
    private var discShader: Shader? = null
    private var glossShader: Shader? = null
    private val glowShaders = HashMap<Mode, Shader>()
    private val visorShaders = HashMap<Mode, Shader>()
    private var columns = 0
    private var colW = 0f
    private var colSpeed = FloatArray(0)
    private var colOffset = FloatArray(0)

    init {
        isClickable = true
        contentDescription = "Orbit assistant. Tap to talk."
    }

    private fun accent(m: Mode = mode): Int = when (m) {
        Mode.IDLE -> 0xFF64D2FF.toInt()
        Mode.LISTENING -> 0xFFFF375F.toInt()
        Mode.THINKING -> 0xFFFF9F0A.toInt()
        Mode.SPEAKING -> 0xFF30D158.toInt()
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        r = min(w, h) / 2f * 0.80f
        disc.reset()
        disc.addCircle(cx, cy, r * 0.86f, Path.Direction.CW)

        hood.reset()
        hood.moveTo(cx - 0.86f * r, cy + 0.98f * r)
        hood.cubicTo(cx - 0.82f * r, cy + 0.55f * r, cx - 0.70f * r, cy + 0.20f * r, cx - 0.62f * r, cy - 0.05f * r)
        hood.cubicTo(cx - 0.55f * r, cy - 0.45f * r, cx - 0.28f * r, cy - 0.72f * r, cx, cy - 0.72f * r)
        hood.cubicTo(cx + 0.28f * r, cy - 0.72f * r, cx + 0.55f * r, cy - 0.45f * r, cx + 0.62f * r, cy - 0.05f * r)
        hood.cubicTo(cx + 0.70f * r, cy + 0.20f * r, cx + 0.82f * r, cy + 0.55f * r, cx + 0.86f * r, cy + 0.98f * r)
        hood.close()

        face.set(cx - 0.36f * r, cy - 0.42f * r, cx + 0.36f * r, cy + 0.50f * r)
        faceClip.reset()
        faceClip.addOval(face, Path.Direction.CW)
        visor.set(cx - 0.33f * r, cy - 0.13f * r, cx + 0.33f * r, cy + 0.06f * r)

        discShader = RadialGradient(cx, cy - r * 0.2f, r * 1.0f, 0xF2141A22.toInt(), 0xF2030508.toInt(), Shader.TileMode.CLAMP)
        glossShader = LinearGradient(0f, cy - r * 0.86f, 0f, cy, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        glowShaders.clear()
        visorShaders.clear()
        for (m in Mode.values()) {
            val a = accent(m)
            glowShaders[m] = RadialGradient(
                cx, cy, r * 1.2f,
                intArrayOf(withAlpha(a, 0), withAlpha(a, 0), withAlpha(a, 0x3A), withAlpha(a, 0)),
                floatArrayOf(0f, 0.62f, 0.72f, 1f), Shader.TileMode.CLAMP
            )
            visorShaders[m] = LinearGradient(
                visor.left, 0f, visor.right, 0f,
                intArrayOf(withAlpha(a, 0x30), withAlpha(a, 0xA0), withAlpha(a, 0x30)),
                null, Shader.TileMode.CLAMP
            )
        }

        code.textSize = max(8f, r * 0.13f)
        colW = code.textSize * 0.9f
        columns = max(1, ((r * 1.72f) / colW).toInt())
        colSpeed = FloatArray(columns) { 0.6f + ((it * 37) % 11) / 10f }
        colOffset = FloatArray(columns) { ((it * 53) % 17) / 17f }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = isShown
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        running = isVisible
        if (isVisible) invalidate()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val s = if (isPressed) 0.93f else 1f
        animate().scaleX(s).scaleY(s).setDuration(120).start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (r <= 0f) return
        val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
        val a = accent()

        // Glow
        fill.shader = glowShaders[mode]
        canvas.drawCircle(cx, cy, r * 1.2f, fill)
        fill.shader = null

        // Listening pulses
        if (mode == Mode.LISTENING) {
            stroke.strokeWidth = 2f * density
            for (k in 0 until 3) {
                val phase = (t * 0.9f + k / 3f) % 1f
                stroke.color = withAlpha(a, ((1f - phase) * (90 + 140 * level)).toInt())
                canvas.drawCircle(cx, cy, r * (0.9f + 0.32f * phase), stroke)
            }
        }

        // Disc
        fill.shader = discShader
        canvas.drawCircle(cx, cy, r * 0.86f, fill)
        fill.shader = null

        // Falling code inside the disc
        canvas.save()
        canvas.clipPath(disc)
        val top = cy - r * 0.86f
        val height = r * 1.72f
        val step = code.textSize * 1.05f
        val left = cx - r * 0.86f + colW * 0.2f
        val tick = (t * 8f).toInt()
        for (i in 0 until columns) {
            val head = ((t * colSpeed[i] * 0.35f + colOffset[i]) % 1.3f) * height * 1.2f
            for (j in 0 until 8) {
                val y = top + head - j * step
                if (y < top - step || y > top + height + step) continue
                val g = ((i * 31 + j * 17 + tick + (head / step).toInt()) and 0x7fffffff) % glyphs.size
                code.color = withAlpha(0xFF30D158.toInt(), if (j == 0) 80 else (52 - j * 6))
                canvas.drawText(glyphs, g, 1, left + i * colW, y, code)
            }
        }
        canvas.restore()

        // Hood
        canvas.save()
        canvas.clipPath(disc)
        fill.color = 0xFF05080C.toInt()
        canvas.drawPath(hood, fill)
        stroke.strokeWidth = 1.2f * density
        stroke.color = withAlpha(a, 0x80)
        canvas.drawPath(hood, stroke)
        canvas.restore()

        // Face void
        fill.color = 0xFF000000.toInt()
        canvas.drawOval(face, fill)
        stroke.strokeWidth = 1f * density
        stroke.color = withAlpha(a, 0x40)
        canvas.drawOval(face, stroke)

        // Scan line over the face
        canvas.save()
        canvas.clipPath(faceClip)
        val scanSpeed = if (mode == Mode.THINKING) 1.6f else 0.45f
        val sy = face.top + ((t * scanSpeed) % 1f) * face.height()
        stroke.strokeWidth = 1.2f * density
        stroke.color = withAlpha(a, 0x55)
        canvas.drawLine(face.left, sy, face.right, sy, stroke)
        canvas.restore()

        // Visor
        val vr = r * 0.09f
        fill.shader = visorShaders[mode]
        canvas.drawRoundRect(visor, vr, vr, fill)
        fill.shader = null
        stroke.strokeWidth = 1.5f * density
        stroke.color = a
        canvas.drawRoundRect(visor, vr, vr, stroke)

        // Eyes (blink every few seconds)
        val blinkPhase = t % 4.2f
        val open = if (blinkPhase < 0.14f) abs(blinkPhase - 0.07f) / 0.07f else 1f
        val eyeW = r * 0.12f
        val eyeH = max(1.5f * density, r * 0.05f * open)
        val ey = cy - 0.035f * r
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * 0.14f * r
            fill.color = withAlpha(a, 0x55)
            tmp.set(ex - eyeW / 2 - 2 * density, ey - eyeH / 2 - 2 * density, ex + eyeW / 2 + 2 * density, ey + eyeH / 2 + 2 * density)
            canvas.drawRoundRect(tmp, eyeH, eyeH, fill)
            fill.color = 0xFFFFFFFF.toInt()
            tmp.set(ex - eyeW / 2, ey - eyeH / 2, ex + eyeW / 2, ey + eyeH / 2)
            canvas.drawRoundRect(tmp, eyeH / 2, eyeH / 2, fill)
        }

        // Voice bars
        val bars = 7
        val bw = r * 0.035f
        val gap = r * 0.07f
        val by = cy + 0.30f * r
        fill.color = a
        for (i in 0 until bars) {
            val x = cx + (i - bars / 2) * gap
            val h = when (mode) {
                Mode.IDLE -> r * (0.02f + 0.01f * (1 + sin(t * 1.5f + i)))
                Mode.SPEAKING -> r * (0.03f + 0.12f * abs(sin(t * 12f + i * 0.9f)) * (0.55f + 0.45f * sin(t * 3f + i)))
                Mode.LISTENING -> r * (0.03f + 0.15f * level * (0.6f + 0.4f * sin(t * 10f + i)))
                Mode.THINKING -> r * (0.02f + 0.06f * (sin(t * 6f - i * 0.8f) + 1f) / 2f)
            }
            tmp.set(x - bw / 2, by - h / 2, x + bw / 2, by + h / 2)
            canvas.drawRoundRect(tmp, bw / 2, bw / 2, fill)
        }

        // Glass highlight on the top half of the disc
        fill.shader = glossShader
        canvas.drawCircle(cx, cy, r * 0.86f, fill)
        fill.shader = null
        stroke.strokeWidth = 1f * density
        stroke.color = 0x33FFFFFF
        canvas.drawCircle(cx, cy, r * 0.86f, stroke)

        // Rings
        val spin = when (mode) {
            Mode.THINKING -> 140f
            Mode.LISTENING -> 70f
            else -> 28f
        }
        stroke.strokeWidth = 2f * density
        stroke.color = withAlpha(a, 0xE6)
        tmp.set(cx - r * 0.94f, cy - r * 0.94f, cx + r * 0.94f, cy + r * 0.94f)
        for (k in 0 until 3) canvas.drawArc(tmp, t * spin + k * 120f, 64f, false, stroke)
        stroke.strokeWidth = 0.8f * density
        stroke.color = withAlpha(a, 0x45)
        val rot = -t * 12f
        for (k in 0 until 48) {
            val ang = Math.toRadians((rot + k * 7.5f).toDouble())
            val inner = if (k % 4 == 0) r * 0.97f else r * 1.0f
            val outer = r * 1.04f
            val c = Math.cos(ang).toFloat()
            val s = Math.sin(ang).toFloat()
            canvas.drawLine(cx + c * inner, cy + s * inner, cx + c * outer, cy + s * outer, stroke)
        }

        if (running) {
            if (mode == Mode.IDLE) postInvalidateDelayed(40) else postInvalidateOnAnimation()
        }
    }
}
