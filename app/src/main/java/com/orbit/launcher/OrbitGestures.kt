package com.orbit.launcher

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

// Orbit Gestures: iOS-style navigation for phones (like Xiaomi/Redmi) that switch gestures off
// when another launcher is the default.
//   Swipe up from the bottom      Home
//   Swipe up and hold             Recent apps
//   Swipe in from either edge     Back
// Orbit hides Android's buttons only while these gestures are running, and puts them back
// the moment the gesture service stops, so you are never left without navigation.

object OrbitNav {
    private const val PREFS = "orbit_nav"
    private const val NAV_KEY = "force_fsg_nav_bar" // Xiaomi's switch for the button bar
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
    const val PHONE_COMMAND = "pm grant com.orbit.launcher android.permission.WRITE_SECURE_SETTINGS"
    const val COMPUTER_COMMAND = "adb shell pm grant com.orbit.launcher android.permission.WRITE_SECURE_SETTINGS"

    private fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun wantHidden(c: Context) = prefs(c).getBoolean("hide_buttons", true)
    fun setWantHidden(c: Context, on: Boolean) = prefs(c).edit().putBoolean("hide_buttons", on).apply()
    fun backEnabled(c: Context) = prefs(c).getBoolean("back", true)
    fun setBackEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("back", on).apply()
    fun barVisible(c: Context) = prefs(c).getBoolean("bar", true)
    fun setBarVisible(c: Context, on: Boolean) = prefs(c).edit().putBoolean("bar", on).apply()
    fun haptics(c: Context) = prefs(c).getBoolean("haptics", true)
    fun setHaptics(c: Context, on: Boolean) = prefs(c).edit().putBoolean("haptics", on).apply()
    fun prompted(c: Context) = prefs(c).getBoolean("prompted", false)
    fun setPrompted(c: Context) = prefs(c).edit().putBoolean("prompted", true).apply()
    private fun weHid(c: Context) = prefs(c).getBoolean("we_hid", false)

    fun isXiaomi(): Boolean = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** True once the one-time permission has been granted (from a computer or the LADB app). */
    fun canHideButtons(c: Context) = c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun buttonsHidden(c: Context): Boolean =
        try { Settings.Global.getInt(c.contentResolver, NAV_KEY, 0) == 1 } catch (e: Exception) { false }

    private fun writeButtonsHidden(c: Context, hide: Boolean): Boolean = try {
        Settings.Global.putInt(c.contentResolver, NAV_KEY, if (hide) 1 else 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Hides the buttons (remembering Orbit did it) when allowed and wanted. */
    fun hideButtons(c: Context) {
        if (canHideButtons(c) && writeButtonsHidden(c, true)) prefs(c).edit().putBoolean("we_hid", true).apply()
    }

    /** Brings the buttons back, but only if Orbit was the one that hid them. */
    fun restoreButtons(c: Context) {
        if (weHid(c) && writeButtonsHidden(c, false)) prefs(c).edit().putBoolean("we_hid", false).apply()
    }

    val navUri get() = Settings.Global.getUriFor(NAV_KEY)

    /** If the buttons are hidden but Orbit's gestures are not running, bring the buttons back. */
    fun safetyCheck(c: Context) {
        if (!OrbitGestureService.running) restoreButtons(c)
    }

    fun gesturesOn(c: Context): Boolean {
        val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(c, OrbitGestureService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun openAccessibilitySettings(c: Context) {
        try {
            c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }
}

class OrbitGestureService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: OrbitGestureService? = null
        val running: Boolean get() = instance != null

        /** Re-applies settings after they change in Orbit. */
        fun refresh() {
            instance?.let { it.handler().post { it.apply() } }
        }
    }

    private var wm: WindowManager? = null
    private var bar: HomeBarView? = null
    private var leftEdge: EdgeView? = null
    private var rightEdge: EdgeView? = null
    private var arrow: BackArrowView? = null
    private var arrowParams: WindowManager.LayoutParams? = null
    private var barParams: WindowManager.LayoutParams? = null
    private var keyboardUp = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun handler() = mainHandler

    // If the phone itself switches the buttons back on, step aside straight away
    private val navObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            if (instance !== this@OrbitGestureService) return
            val hidden = OrbitNav.buttonsHidden(this@OrbitGestureService)
            if (hidden && bar == null) addOverlays() else if (!hidden && bar != null) removeOverlays()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WindowManager::class.java)
        try { contentResolver.registerContentObserver(OrbitNav.navUri, false, navObserver) } catch (e: Exception) { }
        apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOWS_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Only checks whether a keyboard is on screen, so the bar can make room for it
        val up = try { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } } catch (e: Exception) { false }
        if (up != keyboardUp) {
            keyboardUp = up
            layoutBar()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (instance === this) {
            removeOverlays()
            if (OrbitNav.buttonsHidden(this)) addOverlays()
        }
    }

    fun apply() {
        if (OrbitNav.wantHidden(this)) OrbitNav.hideButtons(this) else OrbitNav.restoreButtons(this)
        removeOverlays()
        // Gestures only take over once the buttons are gone, so the two never fight
        if (OrbitNav.buttonsHidden(this)) addOverlays()
    }

    private fun shutdown() {
        try { contentResolver.unregisterContentObserver(navObserver) } catch (e: Exception) { }
        removeOverlays()
        OrbitNav.restoreButtons(this)
        if (instance === this) instance = null
    }

    // ---------------- Actions ----------------

    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun tick(v: View) {
        if (OrbitNav.haptics(this)) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun arrowStart(left: Boolean, rawY: Float) {
        val a = arrow ?: return
        val p = arrowParams ?: return
        p.gravity = Gravity.TOP or if (left) Gravity.START else Gravity.END
        p.y = (rawY - a.layoutHeight / 2f).toInt().coerceAtLeast(0)
        a.fromLeft = left
        a.progress = 0f
        try { wm?.updateViewLayout(a, p) } catch (e: Exception) { }
    }

    fun arrowProgress(progress: Float, armed: Boolean) {
        arrow?.let {
            it.progress = progress
            it.armed = armed
            it.invalidate()
        }
    }

    fun arrowEnd() {
        arrow?.let { a ->
            ValueAnimator.ofFloat(a.progress, 0f).apply {
                duration = 160
                addUpdateListener { a.progress = it.animatedValue as Float; a.invalidate() }
            }.start()
        }
    }

    // ---------------- Overlays ----------------

    private fun dp(v: Float) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun params(w: Int, h: Int, gravity: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply {
        this.gravity = gravity
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun addOverlays() {
        val m = wm ?: return
        try {
            val a = BackArrowView(this)
            val ap = params(a.layoutWidth, a.layoutHeight, Gravity.TOP or Gravity.START, touchable = false)
            m.addView(a, ap)
            arrow = a
            arrowParams = ap

            if (OrbitNav.backEnabled(this)) {
                val h = (resources.displayMetrics.heightPixels * 0.62f).toInt()
                leftEdge = EdgeView(this, fromLeft = true).also { m.addView(it, params(dp(16f), h, Gravity.START or Gravity.CENTER_VERTICAL, true)) }
                rightEdge = EdgeView(this, fromLeft = false).also { m.addView(it, params(dp(16f), h, Gravity.END or Gravity.CENTER_VERTICAL, true)) }
            }

            val b = HomeBarView(this)
            val bp = params(ViewGroup.LayoutParams.MATCH_PARENT, barHeight(), Gravity.BOTTOM, true)
            m.addView(b, bp)
            bar = b
            barParams = bp
            layoutBar()
        } catch (e: Exception) {
            removeOverlays()
        }
    }

    private fun barHeight() = if (keyboardUp) dp(10f) else dp(24f)

    private fun layoutBar() {
        val b = bar ?: return
        val p = barParams ?: return
        b.showPill = OrbitNav.barVisible(this) && !keyboardUp
        p.height = barHeight()
        try { wm?.updateViewLayout(b, p) } catch (e: Exception) { }
        b.invalidate()
    }

    private fun removeOverlays() {
        for (v in listOf<View?>(bar, leftEdge, rightEdge, arrow)) {
            if (v != null) try { wm?.removeViewImmediate(v) } catch (e: Exception) { }
        }
        bar = null
        leftEdge = null
        rightEdge = null
        arrow = null
        arrowParams = null
        barParams = null
    }
}

/** The bottom strip with the iOS-style home indicator. */
private class HomeBarView(private val svc: OrbitGestureService) : View(svc) {
    var showPill = true
    private val density = resources.displayMetrics.density
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    private val rect = RectF()
    private var lift = 0f
    private var sway = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var tracking = false
    private var holdFired = false
    private var velocity: VelocityTracker? = null

    private val hold = Runnable {
        if (tracking && !holdFired && downY - lastY > 48 * density) {
            holdFired = true
            svc.tick(this)
            svc.recents()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!showPill) return
        val w = 134 * density
        val h = 5 * density
        val cx = width / 2f + sway
        val bottom = height - 8 * density - lift
        rect.set(cx - w / 2 - density, bottom - h - density, cx + w / 2 + density, bottom + density)
        canvas.drawRoundRect(rect, h, h, edge)
        rect.set(cx - w / 2, bottom - h, cx + w / 2, bottom)
        canvas.drawRoundRect(rect, h / 2, h / 2, pill)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                holdFired = false
                downX = e.rawX
                downY = e.rawY
                lastX = downX
                lastY = downY
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (abs(e.rawY - lastY) > 4 * density || abs(e.rawX - lastX) > 4 * density) {
                    removeCallbacks(hold)
                    postDelayed(hold, 200)
                    lastX = e.rawX
                    lastY = e.rawY
                }
                lift = ((downY - e.rawY) * 0.25f).coerceIn(0f, 9 * density)
                sway = ((e.rawX - downX) * 0.15f).coerceIn(-24 * density, 24 * density)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                val v = velocity
                v?.addMovement(e)
                v?.computeCurrentVelocity(1000)
                val upSpeed = -(v?.yVelocity ?: 0f)
                val dy = downY - e.rawY
                if (!holdFired && (dy > 30 * density || (upSpeed > 360 * density && dy > 12 * density))) {
                    svc.tick(this)
                    svc.home()
                }
                settle()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                settle()
            }
        }
        return true
    }

    private fun settle() {
        tracking = false
        velocity?.recycle()
        velocity = null
        val startLift = lift
        val startSway = sway
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                lift = startLift * f
                sway = startSway * f
                invalidate()
            }
        }.start()
    }
}

/** A thin strip on the left or right edge; swipe inwards to go back. */
private class EdgeView(private val svc: OrbitGestureService, private val fromLeft: Boolean) : View(svc) {
    private val density = resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var armed = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                armed = false
                svc.arrowStart(fromLeft, e.rawY)
            }
            MotionEvent.ACTION_MOVE -> {
                val dist = if (fromLeft) e.rawX - downX else downX - e.rawX
                val sideways = dist > abs(e.rawY - downY) * 0.7f
                val nowArmed = sideways && dist > 64 * density
                if (nowArmed && !armed) svc.tick(this)
                armed = nowArmed
                svc.arrowProgress((dist / (96 * density)).coerceIn(0f, 1f), armed)
            }
            MotionEvent.ACTION_UP -> {
                if (armed) svc.back()
                armed = false
                svc.arrowEnd()
            }
            MotionEvent.ACTION_CANCEL -> {
                armed = false
                svc.arrowEnd()
            }
        }
        return true
    }
}

/** The back bubble that slides out from the edge while you swipe. */
private class BackArrowView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    val layoutWidth = (72 * density).toInt()
    val layoutHeight = (120 * density).toInt()
    var fromLeft = true
    var progress = 0f
    var armed = false
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0.01f) return
        val r = 22 * density
        val cy = height / 2f
        val travel = r + 14 * density
        val cxFromEdge = -r + travel * progress
        val cx = if (fromLeft) cxFromEdge else width - cxFromEdge
        bubble.color = if (armed) 0xE6000000.toInt() else ((0x99 * progress).toInt() shl 24)
        canvas.drawCircle(cx, cy, r * (0.85f + 0.15f * progress), bubble)
        chevron.strokeWidth = (if (armed) 3f else 2.4f) * density
        chevron.alpha = (255 * progress).toInt().coerceIn(0, 255)
        val s = 7 * density
        path.reset()
        if (fromLeft) {
            path.moveTo(cx + s * 0.4f, cy - s)
            path.lineTo(cx - s * 0.6f, cy)
            path.lineTo(cx + s * 0.4f, cy + s)
        } else {
            path.moveTo(cx - s * 0.4f, cy - s)
            path.lineTo(cx + s * 0.6f, cy)
            path.lineTo(cx - s * 0.4f, cy + s)
        }
        canvas.drawPath(path, chevron)
    }
}
