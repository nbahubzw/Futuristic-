package com.orbit.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// Orbit Tasks: the "Today" screen you get by swiping on the home screen.
// Shows your work plan as a timeline, what's coming up and your notes, and lets you add, tick and edit tasks.

object OrbitTasks {
    private var current: WeakReference<Dialog>? = null

    fun show(context: Context) {
        val activity = context.findActivity() ?: return
        dismiss()
        val d = OrbitTasksDialog(activity)
        current = WeakReference(d)
        d.show()
    }

    /** Closes the Tasks screen if it is open. Returns true if it was open. */
    fun dismiss(): Boolean {
        val d = current?.get()
        current = null
        if (d != null && d.isShowing) {
            d.dismiss()
            return true
        }
        return false
    }

    /** Opens the edit sheet. Pass null to create a new task. */
    fun edit(context: Context, reminder: Reminder?) {
        val activity = context.findActivity() ?: return
        TaskEditSheet(activity, reminder).show()
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper && c !is Activity) c = c.baseContext
    return (c as? Activity)?.takeIf { !it.isFinishing }
}

/** Short relative time: "in 25 min", "in 3 h", "2 h ago". */
private fun relative(t: LocalDateTime, now: LocalDateTime): String {
    val mins = ChronoUnit.MINUTES.between(now, t)
    return when {
        mins in 0..0 -> "now"
        mins in 1..59 -> "in $mins min"
        mins in 60..(24 * 60 - 1) -> "in ${mins / 60} h" + if (mins % 60 >= 15) " ${mins % 60} min" else ""
        mins in -59..-1 -> "${-mins} min ago"
        mins < 0 -> "${-mins / 60} h ago"
        else -> ""
    }
}

class OrbitTasksDialog(private val activity: Activity) : Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {

    private val c = IosColors.of(activity)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var panel: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private val anchors = HashMap<String, View>()
    private var closing = false
    private var showCompleted = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        ui.post { if (isShowing && !closing) render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val blur = Build.VERSION.SDK_INT >= 31
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            setWindowAnimations(0)
            if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
            if (blur) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes.also { it.blurBehindRadius = context.dp(30) }
            }
        }

        val root = FrameLayout(context)
        root.setBackgroundColor(if (blur) 0x80000000.toInt() else 0xE0000000.toInt())
        panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            panel.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        // Top bar
        val bar = FrameLayout(context)
        val done = context.label("Done", 17f, Color.WHITE, IosType.semibold).apply {
            setPadding(context.dp(16), context.dp(10), context.dp(20), context.dp(10))
            pressable { dismiss() }
        }
        bar.addView(done, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        val gear = FrameLayout(context).apply {
            setPadding(context.dp(18), context.dp(11), context.dp(18), context.dp(11))
            addView(Glyph(context, Glyph.Kind.GEAR, Color.WHITE, 1.7f), FrameLayout.LayoutParams(context.dp(24), context.dp(24)))
            contentDescription = "Settings"
            pressable { OrbitSettings.show(context) }
        }
        bar.addView(gear, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        panel.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(48)))

        // Content
        scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), 0, context.dp(16), context.dp(24))
        }
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // New task button
        val add = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(0x33FFFFFF, context.dp(16).toFloat())
            addView(Glyph(context, Glyph.Kind.PLUS, Color.WHITE, 2.2f), LinearLayout.LayoutParams(context.dp(20), context.dp(20)))
            addView(context.label("New Task", 17f, Color.WHITE, IosType.semibold).apply { setPadding(context.dp(8), 0, 0, 0) })
            pressable { OrbitTasks.edit(activity, null) }
        }
        panel.addView(add, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(52)).apply {
            leftMargin = context.dp(16)
            rightMargin = context.dp(16)
            topMargin = context.dp(8)
            bottomMargin = context.dp(12)
        })

        setContentView(root)
        render()

        panel.alpha = 0f
        panel.translationY = -context.dp(28).toFloat()
        panel.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2.2f)).start()
    }

    override fun onStart() {
        super.onStart()
        ReminderStore.prefs(context).registerOnSharedPreferenceChangeListener(prefsListener)
        if (::list.isInitialized) render()
    }

    override fun onStop() {
        ReminderStore.prefs(context).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onStop()
    }

    override fun dismiss() {
        if (closing) return
        closing = true
        if (!::panel.isInitialized || !isShowing) {
            super.dismiss()
            return
        }
        panel.animate().alpha(0f).translationY(-context.dp(18).toFloat()).setDuration(180)
            .withEndAction { super.dismiss() }.start()
    }

    // ---------------- Content ----------------

    private fun render() {
        val ctx = context
        val scrollY = scroll.scrollY
        list.removeAllViews()
        anchors.clear()
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val all = ReminderStore.all(ctx)
        val open = all.filter { !it.done }
        val timed = open.filter { it.time != null }.sortedBy { it.time ?: 0L }
        val overdue = timed.filter { it.repeat == ReminderRepeat.NONE && it.local()?.isBefore(now) == true }
        val todayOpen = timed.filter { it !in overdue && it.local()?.toLocalDate() == today }
        val tomorrow = timed.filter { it.local()?.toLocalDate() == today.plusDays(1) }
        val later = timed.filter { (it.local()?.toLocalDate() ?: today).isAfter(today.plusDays(1)) }
        val notes = open.filter { it.time == null }
        val completed = all.filter { it.done }.sortedByDescending { it.doneAt ?: 0L }

        // Header
        list.addView(ctx.label(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)).uppercase(Locale.UK), 13f, 0x99FFFFFF.toInt(), IosType.semibold).apply {
            letterSpacing = 0.04f
            setPadding(ctx.dp(4), ctx.dp(4), 0, 0)
        })
        list.addView(ctx.label("Today", 34f, Color.WHITE, IosType.bold).apply { setPadding(ctx.dp(4), ctx.dp(2), 0, ctx.dp(14)) })

        // Permission banners
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            list.addView(banner(Glyph.Kind.BELL, c.red, "Turn on notifications", "So Orbit can remind you when tasks are due.") {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7303)
            })
        } else if (!ReminderNotifier.canRing(ctx) && Build.VERSION.SDK_INT >= 34) {
            list.addView(banner(Glyph.Kind.BELL, c.orange, "Allow alarms on the lock screen", "So due tasks ring even when your phone is locked.") {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName)))
                } catch (e: Exception) {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                }
            })
        }

        // Summary tiles
        val tiles = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.addView(tile(Glyph.Kind.CALENDAR, c.blue, (todayOpen.size + overdue.size).toString(), "Today") { scrollTo("plan") }, tileParams(false))
        tiles.addView(tile(Glyph.Kind.CLOCK, c.red, timed.size.toString(), "Scheduled") { scrollTo("upcoming") }, tileParams(true))
        tiles.addView(tile(Glyph.Kind.LIST, c.gray, notes.size.toString(), "Notes") { scrollTo("notes") }, tileParams(true))
        list.addView(tiles)

        if (open.isEmpty() && completed.isEmpty()) {
            list.addView(empty())
            restoreScroll(scrollY)
            return
        }

        if (overdue.isNotEmpty()) {
            list.addView(header("Overdue", c.red))
            list.addView(card(overdue.map { row(it, now) }))
        }

        anchors["plan"] = header("Work Plan").also { list.addView(it) }
        val planDone = completed.filter { it.local()?.toLocalDate() == today }
        list.addView(timeline((todayOpen + planDone).sortedBy { it.time ?: 0L }, now))

        if (tomorrow.isNotEmpty() || later.isNotEmpty()) {
            anchors["upcoming"] = header("Coming Up").also { list.addView(it) }
            if (tomorrow.isNotEmpty()) {
                list.addView(subheader("Tomorrow"))
                list.addView(card(tomorrow.map { row(it, now) }))
            }
            if (later.isNotEmpty()) {
                list.addView(subheader("Later"))
                list.addView(card(later.map { row(it, now) }))
            }
        }

        if (notes.isNotEmpty()) {
            anchors["notes"] = header("Notes").also { list.addView(it) }
            list.addView(card(notes.map { row(it, now) }))
        }

        if (completed.isNotEmpty()) {
            val h = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            h.addView(header("Completed"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            h.addView(ctx.label(if (showCompleted) "Hide" else "Show", 15f, Color.WHITE, IosType.medium).apply {
                setPadding(ctx.dp(12), 0, ctx.dp(4), ctx.dp(10))
                alpha = 0.85f
                pressable {
                    showCompleted = !showCompleted
                    render()
                }
            })
            list.addView(h)
            if (showCompleted) list.addView(card(completed.map { row(it, now) }))
        }
        restoreScroll(scrollY)
    }

    private fun restoreScroll(y: Int) {
        if (y > 0) scroll.post { scroll.scrollTo(0, y) }
    }

    private fun scrollTo(key: String) {
        val v = anchors[key] ?: return
        scroll.smoothScrollTo(0, (v.top - context.dp(8)).coerceAtLeast(0))
    }

    private fun header(text: String, color: Int = Color.WHITE): TextView =
        context.label(text, 20f, color, IosType.bold).apply { setPadding(context.dp(4), context.dp(26), 0, context.dp(10)) }

    private fun subheader(text: String): TextView =
        context.label(text.uppercase(Locale.UK), 13f, 0x99FFFFFF.toInt(), IosType.semibold).apply {
            letterSpacing = 0.03f
            setPadding(context.dp(4), context.dp(6), 0, context.dp(6))
        }

    private fun tileParams(gap: Boolean) = LinearLayout.LayoutParams(0, context.dp(84), 1f).apply { if (gap) leftMargin = context.dp(10) }

    private fun tile(kind: Glyph.Kind, tint: Int, count: String, name: String, onTap: () -> Unit): View {
        val ctx = context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c.card, ctx.dp(14).toFloat())
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
            pressable(onTap)
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = FrameLayout(ctx).apply { background = rounded(tint, ctx.dp(15).toFloat()) }
        dot.addView(Glyph(ctx, kind, Color.WHITE, 2f), FrameLayout.LayoutParams(ctx.dp(18), ctx.dp(18), Gravity.CENTER))
        top.addView(dot, LinearLayout.LayoutParams(ctx.dp(30), ctx.dp(30)))
        top.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(ctx.label(count, 26f, c.label, IosType.bold))
        box.addView(top)
        box.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        box.addView(ctx.label(name, 15f, c.secondary, IosType.semibold))
        return box
    }

    private fun banner(kind: Glyph.Kind, tint: Int, title: String, body: String, onTap: () -> Unit): View {
        val ctx = context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(c.card, ctx.dp(14).toFloat())
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(10), ctx.dp(12))
            pressable(onTap)
        }
        box.addView(ctx.iconTile(kind, tint))
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(12), 0, ctx.dp(8), 0)
        }
        texts.addView(ctx.label(title, 16f, c.label, IosType.semibold))
        texts.addView(ctx.label(body, 13f, c.secondary, IosType.regular, lines = 2).apply { setPadding(0, ctx.dp(3), 0, 0) })
        box.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(Glyph(ctx, Glyph.Kind.CHEVRON, c.ring, 2f), LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16)))
        box.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ctx.dp(12) }
        return box
    }

    private fun card(rows: List<View>): View {
        val ctx = context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c.card, ctx.dp(14).toFloat())
            clipToOutline = true
        }
        rows.forEachIndexed { i, v ->
            if (i > 0) box.addView(View(ctx).apply { setBackgroundColor(c.separator) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = ctx.dp(52) })
            box.addView(v)
        }
        return box
    }

    private fun empty(): View {
        val ctx = context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(24), ctx.dp(56), ctx.dp(24), ctx.dp(24))
        }
        box.addView(Glyph(ctx, Glyph.Kind.SUN, 0xCCFFFFFF.toInt(), 1.6f), LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
        box.addView(ctx.label("Your day is clear", 22f, Color.WHITE, IosType.semibold).apply { setPadding(0, ctx.dp(14), 0, ctx.dp(6)) })
        box.addView(ctx.label("Tap the avatar and say “Remind me to call Mum at 5pm”, or tap New Task.", 15f, 0xB3FFFFFF.toInt(), IosType.regular, lines = 3).apply {
            gravity = Gravity.CENTER
        })
        return box
    }

    /** A task row in Reminders style: circle, title, when. */
    private fun row(r: Reminder, now: LocalDateTime): View {
        val ctx = context
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(52)
            setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
        }
        line.addView(check(r), LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)))
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(14), 0, 0, 0)
        }
        texts.addView(titleView(r, 16f))
        val t = r.local()
        val sub = when {
            t == null -> null
            !r.done && r.repeat == ReminderRepeat.NONE && t.isBefore(now) -> "${ReminderText.label(ctx, t, now)} · ${relative(t, now)}"
            else -> ReminderText.label(ctx, t, now) + repeatSuffix(r)
        }
        if (sub != null) {
            val overdue = !r.done && r.repeat == ReminderRepeat.NONE && t != null && t.isBefore(now)
            texts.addView(ctx.label(sub, 13f, if (overdue) c.red else c.secondary).apply { setPadding(0, ctx.dp(3), 0, 0) })
        }
        line.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        line.pressable { OrbitTasks.edit(activity, r) }
        return line
    }

    private fun titleView(r: Reminder, size: Float): TextView =
        context.label(r.text, size, if (r.done) c.secondary else c.label, IosType.regular, lines = 2).apply {
            if (r.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }

    private fun repeatSuffix(r: Reminder) = when (r.repeat) {
        ReminderRepeat.DAILY -> " · Every day"
        ReminderRepeat.WEEKDAYS -> " · Every weekday"
        ReminderRepeat.WEEKLY -> " · Every week"
        ReminderRepeat.MONTHLY -> " · Every month"
        ReminderRepeat.NONE -> ""
    }

    private fun check(r: Reminder): CheckCircle = CheckCircle(context, c).apply {
        checked = r.done
        accent = c.orange
        setOnClickListener {
            checked = !r.done
            pop()
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            ui.postDelayed({ ReminderStore.setDone(context, r.id, !r.done) }, 280)
        }
    }

    /** Today's work plan as a timeline, with a red "Now" line. */
    private fun timeline(items: List<Reminder>, now: LocalDateTime): View {
        val ctx = context
        if (items.isEmpty()) {
            return card(listOf(ctx.label("Nothing else planned for today.", 15f, c.secondary).apply {
                setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
            }))
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c.card, ctx.dp(14).toFloat())
            setPadding(0, ctx.dp(8), 0, ctx.dp(8))
        }
        var nowShown = false
        items.forEachIndexed { i, r ->
            val t = r.local() ?: return@forEachIndexed
            if (!nowShown && t.isAfter(now)) {
                box.addView(nowLine(now))
                nowShown = true
            }
            box.addView(planRow(r, now, first = i == 0, last = i == items.lastIndex))
        }
        if (!nowShown) box.addView(nowLine(now))
        return box
    }

    private fun nowLine(now: LocalDateTime): View {
        val ctx = context
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), ctx.dp(2), ctx.dp(14), ctx.dp(2))
        }
        line.addView(ctx.label(ReminderText.clock(ctx, now), 12f, c.red, IosType.semibold).apply { minWidth = ctx.dp(50) })
        line.addView(object : View(ctx) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.red }
            override fun onDraw(canvas: Canvas) {
                val cy = height / 2f
                canvas.drawCircle(ctx.dp(10).toFloat(), cy, ctx.dp(4).toFloat(), p)
                p.strokeWidth = ctx.dp(1.5f).toFloat()
                canvas.drawLine(ctx.dp(10).toFloat(), cy, width.toFloat(), cy, p)
            }
        }, LinearLayout.LayoutParams(0, ctx.dp(14), 1f))
        return line
    }

    private fun planRow(r: Reminder, now: LocalDateTime, first: Boolean, last: Boolean): View {
        val ctx = context
        val t = r.local()!!
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
            minimumHeight = ctx.dp(58)
        }
        line.addView(ctx.label(ReminderText.clock(ctx, t), 15f, if (r.done) c.secondary else c.label, IosType.semibold).apply {
            minWidth = ctx.dp(50)
            setPadding(0, ctx.dp(12), 0, 0)
        })
        val dotColour = when {
            r.done -> c.green
            t.isBefore(now) -> c.orange
            else -> c.blue
        }
        line.addView(object : View(ctx) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f
                val dotY = ctx.dp(20).toFloat()
                p.color = c.separator
                p.strokeWidth = ctx.dp(2).toFloat()
                if (!first) canvas.drawLine(cx, 0f, cx, dotY, p)
                if (!last) canvas.drawLine(cx, dotY, cx, height.toFloat(), p)
                p.color = dotColour
                canvas.drawCircle(cx, dotY, ctx.dp(5).toFloat(), p)
            }
        }, LinearLayout.LayoutParams(ctx.dp(20), LinearLayout.LayoutParams.MATCH_PARENT))
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(10), ctx.dp(11), ctx.dp(8), ctx.dp(11))
        }
        texts.addView(titleView(r, 16f))
        val sub = if (r.done) "Done" else relative(t, now) + repeatSuffix(r)
        if (sub.isNotBlank()) texts.addView(ctx.label(sub.removePrefix(" · "), 13f, c.secondary).apply { setPadding(0, ctx.dp(3), 0, 0) })
        line.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val box = FrameLayout(ctx)
        box.addView(check(r), FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
        line.addView(box, LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(44)))
        line.pressable { OrbitTasks.edit(activity, r) }
        return line
    }
}

/** iOS-style bottom sheet to add or edit a task. */
class TaskEditSheet(private val activity: Activity, private val existing: Reminder?) :
    Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {

    private val c = IosColors.of(activity)
    private val pickerTheme = if (c.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    private lateinit var titleField: EditText
    private lateinit var dateValue: TextView
    private lateinit var timeValue: TextView
    private lateinit var repeatValue: TextView
    private lateinit var save: TextView

    private var date: LocalDate? = existing?.local()?.toLocalDate()
    private var time: LocalTime? = existing?.local()?.toLocalTime()
    private var repeat: ReminderRepeat = existing?.repeat ?: ReminderRepeat.NONE
    private var touched = existing != null
    private var parsed: ParsedReminder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.4f)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    if (existing == null) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            )
        }

        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedTop(c.sheet, ctx.dp(14).toFloat())
            setPadding(0, ctx.dp(6), 0, ctx.dp(20))
        }
        sheet.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, ctx.dp(6), 0, ctx.dp(20) + insets.systemWindowInsetBottom)
            insets
        }

        sheet.addView(View(ctx).apply { background = rounded(c.tertiary, ctx.dp(3).toFloat()) },
            LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // Navigation bar: Cancel  Title  Add
        val nav = FrameLayout(ctx)
        nav.addView(ctx.label("Cancel", 17f, c.blue).apply {
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            pressable { dismiss() }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        nav.addView(ctx.label(if (existing == null) "New Task" else "Details", 17f, c.label, IosType.semibold),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        save = ctx.label(if (existing == null) "Add" else "Done", 17f, c.blue, IosType.semibold).apply {
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            pressable { commit() }
        }
        nav.addView(save, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        sheet.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(52)))

        // Title
        titleField = EditText(ctx).apply {
            hint = "Title"
            setText(existing?.text ?: "")
            setSelection(text.length)
            textSize = 17f
            setTextColor(c.label)
            setHintTextColor(c.tertiary)
            background = null
            typeface = IosType.regular
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
        }
        sheet.addView(group(listOf(titleField)))
        if (existing == null) {
            sheet.addView(ctx.label("Type it naturally, like “Call Mum tomorrow at 5pm”. Orbit fills in the date and time.", 13f, c.secondary, lines = 3).apply {
                setPadding(ctx.dp(32), ctx.dp(8), ctx.dp(32), 0)
            })
        }

        // Date, time, repeat
        dateValue = ctx.label("", 17f, c.secondary)
        timeValue = ctx.label("", 17f, c.secondary)
        repeatValue = ctx.label("", 17f, c.secondary)
        sheet.addView(group(listOf(
            optionRow(Glyph.Kind.CALENDAR, c.red, "Date", dateValue) { chooseDate() },
            optionRow(Glyph.Kind.CLOCK, c.blue, "Time", timeValue) { chooseTime() },
            optionRow(Glyph.Kind.REPEAT, c.gray, "Repeat", repeatValue) { chooseRepeat() }
        ), separatorInset = 60).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = ctx.dp(20)
        })

        if (existing != null) {
            val del = ctx.label("Delete Task", 17f, c.red).apply {
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(14), 0, ctx.dp(14))
                pressable { confirmDelete() }
            }
            sheet.addView(group(listOf(del)).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ctx.dp(20) })
        }

        titleField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!touched) {
                    val t = s?.toString()?.trim().orEmpty()
                    parsed = if (t.isEmpty()) null else OrbitBrain.parseReminder(phrase(t), LocalDateTime.now())
                }
                refresh()
            }
        })

        setContentView(sheet)
        refresh()
        if (existing == null) titleField.requestFocus()
    }

    private fun phrase(t: String) = if (t.startsWith("remind", ignoreCase = true)) t else "remind me to $t"

    private fun group(views: List<View>, separatorInset: Int = 16): LinearLayout {
        val ctx = context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c.sheetCell, ctx.dp(12).toFloat())
            clipToOutline = true
        }
        views.forEachIndexed { i, v ->
            if (i > 0) box.addView(View(ctx).apply { setBackgroundColor(c.separator) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = ctx.dp(separatorInset) })
            box.addView(v)
        }
        box.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = ctx.dp(16)
            rightMargin = ctx.dp(16)
            topMargin = ctx.dp(8)
        }
        return box
    }

    private fun optionRow(kind: Glyph.Kind, tint: Int, name: String, value: TextView, onTap: () -> Unit): View {
        val ctx = context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(50)
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(12), ctx.dp(8))
            pressable(onTap)
        }
        row.addView(ctx.iconTile(kind, tint))
        row.addView(ctx.label(name, 17f, c.label).apply { setPadding(ctx.dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value)
        row.addView(Glyph(ctx, Glyph.Kind.CHEVRON, c.ring, 2f), LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16)).apply { leftMargin = ctx.dp(6) })
        return row
    }

    /** What will be saved, from the fields or, for a new task, from the typed sentence. */
    private fun effective(): Triple<LocalDate?, LocalTime?, ReminderRepeat> {
        if (!touched) parsed?.let { p ->
            return Triple(p.time?.toLocalDate(), p.time?.toLocalTime(), p.repeat)
        }
        return Triple(date, time, repeat)
    }

    private fun refresh() {
        val (d, t, rep) = effective()
        val today = LocalDate.now()
        dateValue.text = when {
            d == null -> "None"
            d == today -> "Today"
            d == today.plusDays(1) -> "Tomorrow"
            else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
        }
        timeValue.text = t?.let { ReminderText.clock(context, LocalDateTime.of(today, it)) } ?: "None"
        repeatValue.text = when (rep) {
            ReminderRepeat.NONE -> "Never"
            ReminderRepeat.DAILY -> "Every Day"
            ReminderRepeat.WEEKDAYS -> "Weekdays"
            ReminderRepeat.WEEKLY -> "Every Week"
            ReminderRepeat.MONTHLY -> "Every Month"
        }
        val ready = titleField.text.toString().isNotBlank()
        save.alpha = if (ready) 1f else 0.35f
        save.isEnabled = ready
    }

    private fun takeOver() {
        if (!touched) {
            val (d, t, rep) = effective()
            date = d
            time = t
            repeat = rep
            touched = true
        }
    }

    private fun chooseDate() {
        takeOver()
        val today = LocalDate.now()
        val options = arrayOf("Today", "Tomorrow", "Next Week", "Choose a Date…", "None")
        AlertDialog.Builder(activity, pickerTheme).setTitle("Date").setItems(options) { _, which ->
            when (which) {
                0 -> date = today
                1 -> date = today.plusDays(1)
                2 -> date = today.plusDays(7)
                3 -> {
                    val d = date ?: today
                    DatePickerDialog(activity, pickerTheme, { _, y, m, day ->
                        date = LocalDate.of(y, m + 1, day)
                        if (time == null) time = LocalTime.of(9, 0)
                        refresh()
                    }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                }
                4 -> {
                    date = null
                    time = null
                    repeat = ReminderRepeat.NONE
                }
            }
            if (date != null && time == null) time = LocalTime.of(9, 0)
            refresh()
        }.show()
    }

    private fun chooseTime() {
        takeOver()
        val t = time ?: LocalTime.now().plusHours(1).withMinute(0)
        TimePickerDialog(activity, pickerTheme, { _, h, m ->
            time = LocalTime.of(h, m)
            if (date == null) {
                val today = LocalDate.now()
                date = if (LocalDateTime.of(today, time!!).isAfter(LocalDateTime.now())) today else today.plusDays(1)
            }
            refresh()
        }, t.hour, t.minute, DateFormat.is24HourFormat(activity)).show()
    }

    private val repeatOrder = listOf(ReminderRepeat.NONE, ReminderRepeat.DAILY, ReminderRepeat.WEEKDAYS, ReminderRepeat.WEEKLY, ReminderRepeat.MONTHLY)
    private val repeatNames = arrayOf("Never", "Every Day", "Every Weekday (Mon to Fri)", "Every Week", "Every Month")

    private fun chooseRepeat() {
        takeOver()
        AlertDialog.Builder(activity, pickerTheme).setTitle("Repeat").setItems(repeatNames) { _, which ->
            repeat = repeatOrder[which]
            if (repeat != ReminderRepeat.NONE && date == null) {
                date = LocalDate.now()
                if (time == null) time = LocalTime.of(9, 0)
            }
            refresh()
        }.show()
    }

    private fun confirmDelete() {
        val r = existing ?: return
        AlertDialog.Builder(activity, pickerTheme)
            .setTitle("Delete this task?")
            .setMessage(r.text)
            .setPositiveButton("Delete") { _, _ ->
                ReminderStore.delete(context, r.id)
                dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun commit() {
        val raw = titleField.text.toString().trim()
        if (raw.isEmpty()) return
        val now = LocalDateTime.now()
        val (d, t, rep) = effective()
        val text = if (!touched) parsed?.text ?: raw else raw
        var at: LocalDateTime? = if (d != null) LocalDateTime.of(d, t ?: LocalTime.of(9, 0)) else null
        if (at != null && rep != ReminderRepeat.NONE) at = OrbitBrain.nextRepeat(at, rep, now.minusSeconds(1)) ?: at
        if (existing != null) {
            ReminderStore.edit(context, existing.id, text, at, rep)
        } else {
            ReminderStore.add(context, ParsedReminder(text, at, if (at == null) ReminderRepeat.NONE else rep))
            if (at != null && Build.VERSION.SDK_INT >= 33 &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7303)
            }
        }
        dismiss()
    }
}
