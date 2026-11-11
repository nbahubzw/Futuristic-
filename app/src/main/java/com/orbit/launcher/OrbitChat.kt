package com.orbit.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The Orbit conversation: an iOS Messages-style sheet with everything you've asked and every answer.
// Open it by tapping the "Tap to talk" pill under the avatar. Type or tap the mic.

interface OrbitChatHost {
    fun chatSend(text: String)
    fun chatListen()
}

object OrbitChat {
    private var current: WeakReference<OrbitChatSheet>? = null

    fun show(context: Context, host: OrbitChatHost, typeNow: Boolean = false) {
        val act = context.findActivity() ?: return
        current?.get()?.let { if (it.isShowing) return }
        val d = OrbitChatSheet(act, host, typeNow)
        current = WeakReference(d)
        d.show()
    }

    fun dismiss(): Boolean {
        val d = current?.get()
        current = null
        if (d != null && d.isShowing) {
            d.dismiss()
            return true
        }
        return false
    }

    fun isShowing(): Boolean = current?.get()?.isShowing == true

    /** "Listening…" while the mic is on, or null. */
    fun setStatus(text: String?) {
        current?.get()?.setStatus(text)
    }
}

class OrbitChatSheet(
    private val activity: Activity,
    host: OrbitChatHost,
    private val typeNow: Boolean
) : Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {

    private val host = WeakReference(host)
    private val c = IosColors.of(activity)
    private val orbitBubble = if (c.dark) 0xFF262629.toInt() else 0xFFE9E9EB.toInt()
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var field: EditText
    private lateinit var action: FrameLayout
    private lateinit var actionGlyph: Glyph
    private lateinit var subtitle: TextView
    private var status: String? = null
    private var dots: ValueAnimator? = null
    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.92f).toInt())
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.4f)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            @Suppress("DEPRECATION")
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    if (typeNow) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            )
        }

        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedTop(c.sheet, ctx.dp(14).toFloat())
        }
        sheet.addView(View(ctx).apply { background = rounded(c.tertiary, ctx.dp(3).toFloat()) },
            LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = ctx.dp(6)
            })

        // Navigation bar: Clear   Orbit / status   Done
        val nav = FrameLayout(ctx)
        val titles = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        titles.addView(ctx.label("Orbit", 17f, c.label, IosType.semibold))
        subtitle = ctx.label("", 12f, c.secondary).apply { setPadding(0, ctx.dp(1), 0, 0) }
        titles.addView(subtitle)
        nav.addView(titles, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        nav.addView(ctx.label("Clear", 17f, c.blue).apply {
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            pressable { OrbitAI.clearConversation() }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        nav.addView(ctx.label("Done", 17f, c.blue, IosType.semibold).apply {
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            pressable { dismiss() }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        sheet.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(54)))
        sheet.addView(View(ctx).apply { setBackgroundColor(c.separator) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))

        // Messages
        list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(12))
        }
        scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(list)
        }
        sheet.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Input bar
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(10), ctx.dp(8))
        }
        val fieldBox = FrameLayout(ctx).apply { background = rounded(c.sheet, ctx.dp(19).toFloat(), ctx.dp(1), c.separator) }
        field = EditText(ctx).apply {
            background = null
            hint = "Ask Orbit anything"
            setHintTextColor(c.tertiary)
            setTextColor(c.label)
            textSize = 16f
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
            setOnEditorActionListener { _, id, event ->
                if (id == EditorInfo.IME_ACTION_SEND || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    send()
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = refreshAction()
            })
        }
        fieldBox.addView(field, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        bar.addView(fieldBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        action = FrameLayout(ctx)
        actionGlyph = Glyph(ctx, Glyph.Kind.MIC, Color.WHITE, 2.2f)
        action.addView(actionGlyph, FrameLayout.LayoutParams(ctx.dp(18), ctx.dp(18), Gravity.CENTER))
        action.pressable {
            if (field.text.toString().isNotBlank()) send()
            else {
                hideKeyboard()
                host.get()?.chatListen()
            }
        }
        bar.addView(action, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)).apply { leftMargin = ctx.dp(8) })
        sheet.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        sheet.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, 0, 0, ctx.dp(22) + insets.systemWindowInsetBottom)
            insets
        }
        setContentView(sheet)
        refreshAction()
        render()
        if (typeNow) field.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        OrbitAI.addListener(listener)
        render()
    }

    override fun onStop() {
        OrbitAI.removeListener(listener)
        dots?.cancel()
        super.onStop()
    }

    fun setStatus(text: String?) {
        status = text
        if (::field.isInitialized) {
            field.hint = text ?: "Ask Orbit anything"
            refreshAction()
        }
    }

    private fun send() {
        val t = field.text.toString().trim()
        if (t.isEmpty()) return
        field.setText("")
        host.get()?.chatSend(t)
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun refreshAction() {
        val typing = field.text.toString().isNotBlank()
        val listening = status != null
        actionGlyph.kind = if (typing) Glyph.Kind.SEND else Glyph.Kind.MIC
        actionGlyph.color = if (typing || listening) Color.WHITE else c.secondary
        action.background = when {
            typing -> rounded(c.blue, context.dp(17).toFloat())
            listening -> rounded(c.red, context.dp(17).toFloat())
            else -> rounded(c.fill, context.dp(17).toFloat())
        }
        actionGlyph.invalidate()
    }

    // ---------------- Messages ----------------

    private fun render() {
        if (!::list.isInitialized) return
        val ctx = context
        subtitle.text = OrbitAI.status(ctx)
        list.removeAllViews()
        dots?.cancel()
        val lines = OrbitAI.transcript()
        if (lines.isEmpty() && !OrbitAI.thinking) {
            list.addView(emptyState())
            return
        }
        var lastAt = 0L
        for ((i, l) in lines.withIndex()) {
            if (l.at - lastAt > 10 * 60 * 1000L) list.addView(timeLabel(l.at))
            lastAt = l.at
            val nextSameSide = i + 1 < lines.size && lines[i + 1].user == l.user
            list.addView(bubble(l, tail = !nextSameSide))
        }
        if (OrbitAI.thinking) list.addView(thinkingBubble())
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun timeLabel(at: Long): View {
        val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        val day = if (t.toLocalDate() == LocalDate.now()) "Today" else t.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
        return context.label("$day " + ReminderText.clock(context, t.toLocalDateTime()), 12f, c.secondary, IosType.medium).apply {
            gravity = Gravity.CENTER
            setPadding(0, context.dp(10), 0, context.dp(6))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun bubble(l: OrbitAI.Line, tail: Boolean): View {
        val ctx = context
        val maxW = (ctx.resources.displayMetrics.widthPixels * 0.76f).toInt()
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (l.user) Gravity.END else Gravity.START
            setPadding(0, ctx.dp(2), 0, if (tail) ctx.dp(8) else ctx.dp(1))
        }
        val text = ctx.label(l.text, 16f, if (l.user) Color.WHITE else c.label, lines = 200).apply {
            maxWidth = maxW
            setLineSpacing(ctx.dp(2).toFloat(), 1f)
            setTextIsSelectable(true)
            background = rounded(if (l.user) c.blue else orbitBubble, ctx.dp(18).toFloat())
            setPadding(ctx.dp(13), ctx.dp(8), ctx.dp(13), ctx.dp(9))
        }
        text.ellipsize = null
        wrap.addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val tag = when (l.source) {
            OrbitAI.Source.ONLINE -> "Gemini"
            OrbitAI.Source.OFFLINE -> "On this phone"
            else -> null
        }
        if (!l.user && tag != null && tail) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(6), ctx.dp(3), 0, 0)
            }
            row.addView(Glyph(ctx, if (l.source == OrbitAI.Source.ONLINE) Glyph.Kind.SPARKLE else Glyph.Kind.CHIP, c.secondary, 1.4f),
                LinearLayout.LayoutParams(ctx.dp(11), ctx.dp(11)).apply { rightMargin = ctx.dp(4) })
            row.addView(ctx.label(tag, 11f, c.secondary, IosType.medium))
            wrap.addView(row)
        }
        wrap.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        return wrap
    }

    private fun thinkingBubble(): View {
        val ctx = context
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, ctx.dp(2), 0, ctx.dp(8))
        }
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(orbitBubble, ctx.dp(18).toFloat())
            setPadding(ctx.dp(14), ctx.dp(13), ctx.dp(14), ctx.dp(13))
        }
        val dotViews = (0 until 3).map {
            View(ctx).apply { background = rounded(c.secondary, ctx.dp(4).toFloat()) }.also { d ->
                pill.addView(d, LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)).apply { if (it > 0) leftMargin = ctx.dp(5) })
            }
        }
        wrap.addView(pill)
        dots = ValueAnimator.ofFloat(0f, 3f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                val v = a.animatedValue as Float
                for ((i, d) in dotViews.withIndex()) {
                    val phase = ((v - i + 3f) % 3f)
                    d.alpha = if (phase < 1f) 0.35f + 0.65f * (1f - kotlin.math.abs(phase - 0.5f) * 2f) else 0.35f
                }
            }
            start()
        }
        wrap.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        return wrap
    }

    private fun emptyState(): View {
        val ctx = context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ctx.dp(20), ctx.dp(40), ctx.dp(20), ctx.dp(20))
        }
        val badge = FrameLayout(ctx).apply { background = rounded(c.indigo, ctx.dp(16).toFloat()) }
        badge.addView(Glyph(ctx, Glyph.Kind.SPARKLE, Color.WHITE, 2f), FrameLayout.LayoutParams(ctx.dp(30), ctx.dp(30), Gravity.CENTER))
        box.addView(badge, LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)))
        box.addView(ctx.label("Ask me anything", 20f, c.label, IosType.bold).apply { setPadding(0, ctx.dp(14), 0, ctx.dp(4)) })
        val hint = if (OrbitAI.available(ctx)) "Questions, ideas, writing, news, or just a chat. I can also open apps, make calls and keep your tasks."
        else "I can open apps, make calls and keep your tasks now. To answer any question, set up my AI in Orbit Settings, under Intelligence."
        box.addView(ctx.label(hint, 15f, c.secondary, lines = 4).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, ctx.dp(18))
        })
        val ideas = listOf("Who messaged me?", "What's next?", "What's in the news today?", "Play some music", "Remind me at 5 to call Mum")
        for (idea in ideas) {
            box.addView(ctx.label(idea, 15f, c.blue, IosType.medium).apply {
                gravity = Gravity.CENTER
                background = rounded(c.sheetCell, ctx.dp(18).toFloat())
                setPadding(ctx.dp(16), ctx.dp(9), ctx.dp(16), ctx.dp(9))
                pressable { host.get()?.chatSend(idea) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(8) })
        }
        box.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        return box
    }
}
