package com.orbit.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Orbit hero: the top of the main home screen. Greets you by name, listens when you tap the avatar,
// talks back, and keeps your reminders on a leaflet.

const val HERO_ROWS = 3

class OrbitHeroView(context: Context) : LinearLayout(context) {

    private val cyan = 0xFF00E5FF.toInt()
    private val green = 0xFF39FF14.toInt()
    private val amber = 0xFFFFB300.toInt()
    private val white = Color.WHITE
    private val dim = 0xCCFFFFFF.toInt()
    private val faint = 0x80FFFFFF.toInt()
    private val mono = Typeface.MONOSPACE
    private val monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val dialogTheme = android.R.style.Theme_Material_Dialog_Alert

    private val ui = Handler(Looper.getMainLooper())
    private val greetingView: TextView
    private val subline: TextView
    private val avatar: OrbitAvatarView
    private val status: TextView
    private val leafletItems: LinearLayout
    private val leafletCount: TextView

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var recogniser: SpeechRecognizer? = null
    private var listening = false
    private var attached = false
    private var askedName = false
    private var greetingTarget = ""
    private var typed = 0
    private var cursorOn = true
    private var leafletCapacity = 4

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        ui.post { if (attached) { refreshLeaflet(); refreshSubline() } }
    }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(8), dp(2), dp(8), dp(4))

        greetingView = TextView(context).apply {
            typeface = monoBold
            setTextColor(white)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            letterSpacing = 0.04f
            setAutoSizeTextTypeUniformWithConfiguration(12, 20, 1, TypedValue.COMPLEX_UNIT_SP)
            shadow(this)
            setOnClickListener { askName(first = false) }
        }
        subline = TextView(context).apply {
            typeface = mono
            textSize = 12f
            setTextColor(cyan)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            shadow(this)
            setOnClickListener { openCalendar() }
        }
        addView(greetingView, LayoutParams(LayoutParams.MATCH_PARENT, dp(28)))
        addView(subline, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(context).apply { orientation = HORIZONTAL }

        val left = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        avatar = OrbitAvatarView(context).apply { setOnClickListener { onAvatarTap() } }
        status = TextView(context).apply {
            typeface = mono
            textSize = 10.5f
            setTextColor(dim)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            shadow(this)
            setOnClickListener { showCommandDialog() }
        }
        left.addView(avatar, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        left.addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        row.addView(left, LayoutParams(0, LayoutParams.MATCH_PARENT, 0.46f))

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                setColor(0xB3060B12.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x6600E5FF)
            }
            setPadding(dp(12), dp(8), dp(10), dp(8))
        }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { showFullLeaflet() }
        }
        val title = TextView(context).apply {
            text = "▤ LEAFLET"
            typeface = monoBold
            textSize = 12f
            setTextColor(cyan)
            letterSpacing = 0.08f
        }
        leafletCount = TextView(context).apply {
            typeface = monoBold
            textSize = 10.5f
            setTextColor(0xFF000000.toInt())
            background = GradientDrawable().apply { setColor(cyan); cornerRadius = dp(9).toFloat() }
            setPadding(dp(6), 0, dp(6), 0)
        }
        val add = TextView(context).apply {
            text = "+"
            typeface = monoBold
            textSize = 20f
            setTextColor(cyan)
            setPadding(dp(10), 0, dp(2), 0)
            contentDescription = "Add reminder"
            setOnClickListener { showAddDialog() }
        }
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(leafletCount, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        header.addView(add, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        card.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        card.addView(View(context).apply { setBackgroundColor(0x3300E5FF) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4)
            bottomMargin = dp(2)
        })
        leafletItems = LinearLayout(context).apply { orientation = VERTICAL }
        card.addView(leafletItems, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        row.addView(card, LayoutParams(0, LayoutParams.MATCH_PARENT, 0.54f).apply {
            leftMargin = dp(8)
            topMargin = dp(6)
            bottomMargin = dp(4)
        })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(2) })

        leafletItems.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                val cap = maxOf(1, (bottom - top) / dp(29))
                if (cap != leafletCapacity) {
                    leafletCapacity = cap
                    ui.post { refreshLeaflet() }
                }
            }
        }
    }

    // ---------------- Lifecycle ----------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        ReminderStore.prefs(context).registerOnSharedPreferenceChangeListener(prefsListener)
        OrbitAssistant.loadApps(context) { }
        ReminderAlarms.rescheduleAll(context)
        initTts()
        status.text = idleHint()
        updateGreeting(animate = true)
        refreshSubline()
        refreshLeaflet()
        ui.postDelayed(minuteTick, msToNextMinute())
        ui.postDelayed(blinker, 530)
        ui.postDelayed({ if (attached && hasWindowFocus()) onHomeShown() }, 800)
    }

    override fun onDetachedFromWindow() {
        attached = false
        ui.removeCallbacksAndMessages(null)
        ReminderStore.prefs(context).unregisterOnSharedPreferenceChangeListener(prefsListener)
        recogniser?.destroy()
        recogniser = null
        listening = false
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && attached) onHomeShown()
    }

    /** Runs whenever you come back to the home screen. Greets once per morning, afternoon and evening. */
    private fun onHomeShown() {
        updateGreeting(animate = true)
        refreshSubline()
        refreshLeaflet()
        val name = OrbitProfile.name(context)
        if (name.isBlank()) {
            if (!askedName) {
                askedName = true
                ui.postDelayed({ if (attached) askName(first = true) }, 400)
            }
            return
        }
        val now = LocalDateTime.now()
        val key = periodKey(now)
        if (OrbitProfile.lastGreeting(context) != key) {
            OrbitProfile.setLastGreeting(context, key)
            say(greetingSummary(name, now))
        }
    }

    private fun periodKey(now: LocalDateTime) = now.toLocalDate().toString() + "-" + OrbitBrain.partOfDay(now.hour)

    private val minuteTick = object : Runnable {
        override fun run() {
            if (!attached) return
            refreshSubline()
            updateGreeting(animate = true)
            refreshLeaflet()
            ui.postDelayed(this, msToNextMinute())
        }
    }

    private val typer = object : Runnable {
        override fun run() {
            if (typed < greetingTarget.length) typed++
            renderGreeting()
            if (typed < greetingTarget.length) ui.postDelayed(this, 45)
        }
    }

    private val blinker = object : Runnable {
        override fun run() {
            if (!attached) return
            cursorOn = !cursorOn
            renderGreeting()
            ui.postDelayed(this, 530)
        }
    }

    private fun msToNextMinute(): Long = 60_000L - (System.currentTimeMillis() % 60_000L) + 50L

    // ---------------- Greeting, clock line, leaflet ----------------

    private fun currentGreeting(): String {
        val name = OrbitProfile.name(context)
        return if (name.isBlank()) "HELLO, HUMAN" else OrbitBrain.greeting(LocalDateTime.now().hour, name).uppercase(Locale.UK)
    }

    private fun updateGreeting(animate: Boolean) {
        val g = currentGreeting()
        if (g == greetingTarget) return
        greetingTarget = g
        typed = if (animate) 0 else g.length
        ui.removeCallbacks(typer)
        ui.post(typer)
    }

    private fun renderGreeting() {
        val sb = SpannableStringBuilder(greetingTarget.take(typed))
        val start = sb.length
        sb.append(if (cursorOn || typed < greetingTarget.length) "_" else " ")
        sb.setSpan(ForegroundColorSpan(green), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        greetingView.text = sb
    }

    private fun refreshSubline() {
        val now = LocalDateTime.now()
        val today = ReminderStore.all(context).count { !it.done && it.local()?.toLocalDate() == now.toLocalDate() }
        val date = now.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)).uppercase(Locale.UK)
        val clock = ReminderText.clock(context, now).uppercase(Locale.UK)
        subline.text = "$clock · $date" + if (today > 0) " · $today TODAY" else ""
    }

    private fun refreshLeaflet() {
        val items = ReminderStore.leaflet(context)
        val now = LocalDateTime.now()
        val open = items.count { !it.done }
        leafletCount.text = open.toString()
        leafletCount.visibility = if (open > 0) View.VISIBLE else View.GONE
        leafletItems.removeAllViews()
        if (items.isEmpty()) {
            leafletItems.addView(TextView(context).apply {
                text = "Nothing planned.\nTap the avatar and say\n“remind me to…”"
                typeface = mono
                textSize = 10.5f
                setTextColor(faint)
                setPadding(0, dp(6), 0, 0)
            })
            return
        }
        val shown = if (items.size > leafletCapacity) items.take(maxOf(1, leafletCapacity - 1)) else items
        for (r in shown) leafletItems.addView(row(r, now, full = false) { })
        if (items.size > shown.size) {
            leafletItems.addView(TextView(context).apply {
                text = "+${items.size - shown.size} more ›"
                typeface = monoBold
                textSize = 11f
                setTextColor(cyan)
                setPadding(0, dp(4), 0, 0)
                setOnClickListener { showFullLeaflet() }
            })
        }
    }

    private fun row(r: Reminder, now: LocalDateTime, full: Boolean, onChange: () -> Unit): View {
        val t = r.local()
        val overdue = !r.done && t != null && t.isBefore(now) && r.repeat == ReminderRepeat.NONE
        val label = when {
            t == null -> "NOTE"
            overdue -> "DUE"
            else -> ReminderText.label(context, t, now).uppercase(Locale.UK)
        }
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(if (full) 8 else 5), 0, dp(if (full) 8 else 5))
        }
        val timeView = TextView(context).apply {
            text = label
            typeface = monoBold
            textSize = if (full) 11.5f else 10.5f
            setTextColor(
                when {
                    r.done -> 0x66FFFFFF
                    overdue -> amber
                    t == null -> faint
                    else -> cyan
                }
            )
            maxLines = 1
        }
        val body = TextView(context).apply {
            text = (if (r.repeat != ReminderRepeat.NONE) "↻ " else "") + r.text
            textSize = if (full) 15f else 13f
            setTextColor(if (r.done) 0x66FFFFFF else white)
            maxLines = if (full) 3 else 1
            ellipsize = TextUtils.TruncateAt.END
            if (r.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        line.addView(timeView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        line.addView(body, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        val toggle = {
            line.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            ReminderStore.setDone(context, r.id, !r.done)
            status.text = if (r.done) "> back on the leaflet: ${r.text}" else "> ticked off: ${r.text}"
            onChange()
        }
        if (full) {
            line.addView(button(if (r.done) "↺" else "✓") { toggle() })
            line.addView(button("✕") {
                ReminderStore.delete(context, r.id)
                onChange()
            })
        } else {
            line.setOnClickListener { toggle() }
        }
        return line
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 18f
        setTextColor(cyan)
        gravity = Gravity.CENTER
        minWidth = dp(40)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { onClick() }
    }

    private fun greetingSummary(name: String, now: LocalDateTime): String {
        val sb = StringBuilder(OrbitBrain.greeting(now.hour, name)).append('.')
        val open = ReminderStore.all(context).filter { !it.done && it.time != null }
        val today = open.filter { it.local()?.toLocalDate() == now.toLocalDate() }
        val due = today.count { it.repeat == ReminderRepeat.NONE && it.local()?.isBefore(now) == true }
        val next = open.filter { it.local()?.isAfter(now) == true }.minByOrNull { it.time ?: Long.MAX_VALUE }
        when {
            today.isEmpty() && next == null -> sb.append(" Your schedule is clear.")
            today.isEmpty() -> sb.append(" Nothing else today.")
            else -> sb.append(" You have ${today.size} reminder${if (today.size == 1) "" else "s"} today.")
        }
        if (due > 0) sb.append(" $due ${if (due == 1) "is" else "are"} overdue.")
        if (next != null) next.local()?.let { sb.append(" Next: ${lowerFirst(next.text)}, ${ReminderText.spoken(it, now)}.") }
        return sb.toString()
    }

    private fun scheduleSummary(now: LocalDateTime, who: String): String {
        val open = ReminderStore.leaflet(context).filter { !it.done }
        if (open.isEmpty()) return "Your leaflet is empty$who. Nothing scheduled."
        val parts = open.take(3).map { r ->
            val t = r.local()
            when {
                t == null -> lowerFirst(r.text)
                t.isBefore(now) && r.repeat == ReminderRepeat.NONE -> lowerFirst(r.text) + ", overdue"
                else -> lowerFirst(r.text) + ", " + ReminderText.spoken(t, now)
            }
        }
        val more = if (open.size > 3) " And ${open.size - 3} more on your leaflet." else ""
        return "You have ${open.size} reminder${if (open.size == 1) "" else "s"}$who. " + parts.joinToString(". ") + "." + more
    }

    private fun idleHint() = "> tap me to talk\n> tap here to type"

    // ---------------- Talking ----------------

    private fun onAvatarTap() {
        if (OrbitProfile.name(context).isBlank()) {
            askName(first = true)
            return
        }
        if (listening) {
            recogniser?.stopListening()
            return
        }
        tts?.stop()
        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status.text = "< Voice isn't available on this phone. Type instead."
            showCommandDialog()
            return
        }
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            ask(Manifest.permission.RECORD_AUDIO)
            status.text = "< Allow the microphone, then tap me again."
            return
        }
        val rec = recogniser ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(voiceListener)
            recogniser = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        listening = true
        avatar.mode = OrbitAvatarView.Mode.LISTENING
        status.text = "> listening…"
        avatar.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        rec.startListening(intent)
    }

    private val voiceListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            avatar.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onEndOfSpeech() {
            if (attached) avatar.mode = OrbitAvatarView.Mode.THINKING
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!attached) return
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                if (it.isNotBlank()) status.text = "> $it"
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            avatar.level = 0f
            if (!attached) return
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
            if (heard.isEmpty()) {
                avatar.mode = OrbitAvatarView.Mode.IDLE
                status.text = "< I didn't catch that. Tap me and try again."
                return
            }
            handle(bestGuess(heard))
        }

        override fun onError(error: Int) {
            listening = false
            avatar.level = 0f
            if (!attached) return
            avatar.mode = OrbitAvatarView.Mode.IDLE
            status.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "< I didn't catch that. Tap me and try again."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "< Voice needs data or an offline speech pack. Tap here to type."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "< Allow the microphone, then tap me again."
                else -> "< Voice stopped (code $error). Tap here to type."
            }
        }
    }

    /** The recogniser offers several guesses; prefer one that means something. */
    private fun bestGuess(heard: List<String>): String {
        val now = LocalDateTime.now()
        val apps = OrbitAssistant.cachedApps()
        return heard.firstOrNull { h ->
            when (val i = OrbitBrain.interpret(h, now)) {
                is HeroIntent.Other -> apps.isEmpty() || OrbitAssistant.parse(i.raw, apps, context) !is OrbitCommand.NotFound
                else -> true
            }
        } ?: heard.first()
    }

    private fun handle(raw: String) {
        val now = LocalDateTime.now()
        val name = OrbitProfile.name(context)
        val who = if (name.isBlank()) "" else ", $name"
        status.text = "> $raw"
        avatar.mode = OrbitAvatarView.Mode.THINKING
        ui.postDelayed({ if (avatar.mode == OrbitAvatarView.Mode.THINKING) avatar.mode = OrbitAvatarView.Mode.IDLE }, 3000)
        when (val intent = OrbitBrain.interpret(raw, now)) {
            is HeroIntent.SetName -> {
                OrbitProfile.setName(context, intent.name)
                OrbitProfile.setLastGreeting(context, periodKey(now))
                updateGreeting(animate = true)
                say("Got it. I'll call you ${intent.name}.")
            }
            is HeroIntent.AddReminder -> addReminder(intent.reminder)
            HeroIntent.ListReminders -> say(scheduleSummary(now, who))
            HeroIntent.ClearDone -> {
                val n = ReminderStore.clearDone(context)
                say(if (n == 0) "There's nothing ticked off to clear$who." else "Cleared $n ticked reminder${if (n == 1) "" else "s"}.")
            }
            HeroIntent.TellTime -> say("It's ${ReminderText.spokenClock(now)}$who.")
            HeroIntent.TellDate -> say("Today is " + now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.UK)) + ".")
            HeroIntent.Hello -> say("Hello$who. What can I do for you?")
            HeroIntent.Thanks -> say("Any time$who.")
            HeroIntent.Help -> say("Try: open YouTube. Call Mum. Remind me to pay the rent on Friday at 9. Or ask: what's on my schedule?")
            is HeroIntent.Voice -> {
                if (intent.on) {
                    OrbitProfile.setVoiceOn(context, true)
                    say("Voice on$who.")
                } else {
                    OrbitProfile.setVoiceOn(context, false)
                    tts?.stop()
                    say("Okay$who, I'll stay quiet. Say \"voice on\" when you want me back.")
                }
            }
            is HeroIntent.Other -> runAppCommand(intent.raw, who)
            HeroIntent.Empty -> {
                avatar.mode = OrbitAvatarView.Mode.IDLE
                status.text = idleHint()
            }
        }
    }

    private fun addReminder(p: ParsedReminder) {
        val who = OrbitProfile.name(context).let { if (it.isBlank()) "" else ", $it" }
        if (p.time == null && p.text == "Reminder") {
            say("What should I remind you about$who?")
            showAddDialog()
            return
        }
        ReminderStore.add(context, p)
        if (p.time != null && Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            ask(Manifest.permission.POST_NOTIFICATIONS)
        }
        val now = LocalDateTime.now()
        val time = p.time
        val whenText = when {
            time == null -> null
            p.repeat == ReminderRepeat.DAILY -> "every day at " + ReminderText.spokenClock(time)
            p.repeat == ReminderRepeat.WEEKLY ->
                "every " + time.format(DateTimeFormatter.ofPattern("EEEE", Locale.UK)) + " at " + ReminderText.spokenClock(time)
            else -> ReminderText.spoken(time, now)
        }
        say(
            if (whenText == null) "Added to your leaflet$who: ${lowerFirst(p.text)}."
            else "Okay$who. I'll remind you $whenText: ${lowerFirst(p.text)}."
        )
    }

    private fun runAppCommand(raw: String, who: String) {
        withApps { apps ->
            when (val cmd = OrbitAssistant.parse(raw, apps, context)) {
                is OrbitCommand.OpenApp -> {
                    say("Opening ${cmd.app.label}.")
                    if (!OrbitAssistant.launch(context, cmd.app)) say("Sorry$who, I couldn't open ${cmd.app.label}.")
                }
                is OrbitCommand.Dial -> {
                    say("Dialling " + cmd.number.toCharArray().joinToString(" ") + ".")
                    dial(cmd.number)
                }
                is OrbitCommand.CallContact -> callContact(cmd.name, who)
                OrbitCommand.OpenDialler -> {
                    say("Opening the phone.")
                    dial(null)
                }
                is OrbitCommand.ShowGroup -> {
                    say("Here are all your apps, sorted into groups.")
                    OrbitAssistant.show(context)
                }
                is OrbitCommand.NotFound -> say("Sorry$who, I couldn't find ${cmd.query}.")
                OrbitCommand.Empty -> {
                    avatar.mode = OrbitAvatarView.Mode.IDLE
                    status.text = idleHint()
                }
            }
        }
    }

    private fun withApps(block: (List<AssistantApp>) -> Unit) {
        val cached = OrbitAssistant.cachedApps()
        if (cached.isNotEmpty()) {
            block(cached)
            return
        }
        var done = false
        OrbitAssistant.loadApps(context) { list ->
            if (!done && attached) {
                done = true
                block(list)
            }
        }
    }

    private fun callContact(name: String, who: String) {
        if (!has(Manifest.permission.READ_CONTACTS)) {
            ask(Manifest.permission.READ_CONTACTS)
            say("I need access to your contacts first$who. Allow it, then ask me again.")
            return
        }
        val hit = OrbitAssistant.findContact(context, name)
        if (hit == null) {
            say("I couldn't find $name in your contacts.")
        } else {
            say("Calling ${hit.first}.")
            dial(hit.second)
        }
    }

    private fun dial(number: String?) {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            if (number != null) data = Uri.parse("tel:" + Uri.encode(number))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            status.text = "< No phone app found."
        }
    }

    // ---------------- Voice output ----------------

    private fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { result ->
            ui.post {
                val engine = tts ?: return@post
                if (result != TextToSpeech.SUCCESS) return@post
                val lang = engine.setLanguage(Locale.UK)
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) engine.setLanguage(Locale.getDefault())
                engine.setPitch(0.9f)
                engine.setSpeechRate(1.02f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        ui.post { if (!listening) avatar.mode = OrbitAvatarView.Mode.SPEAKING }
                    }

                    override fun onDone(utteranceId: String?) {
                        ui.post { if (!listening) avatar.mode = OrbitAvatarView.Mode.IDLE }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        ui.post { if (!listening) avatar.mode = OrbitAvatarView.Mode.IDLE }
                    }
                })
                ttsReady = true
                pendingSpeech?.let {
                    pendingSpeech = null
                    say(it)
                }
            }
        }
    }

    /** Shows the reply under the avatar and speaks it (unless voice is off). */
    private fun say(text: String) {
        status.text = "< $text"
        if (!OrbitProfile.voiceOn(context)) {
            if (!listening) avatar.mode = OrbitAvatarView.Mode.IDLE
            return
        }
        val engine = tts
        if (engine == null || !ttsReady) {
            pendingSpeech = text
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "orbit-" + SystemClock.uptimeMillis())
    }

    // ---------------- Dialogs ----------------

    private fun askName(first: Boolean) {
        val act = activity() ?: return
        val field = field("Your name", OrbitProfile.name(context)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        }
        if (first) say("Hello! I'm Orbit, your assistant. What should I call you?")
        val d = AlertDialog.Builder(act, dialogTheme)
            .setTitle(if (first) "Hi, I'm Orbit" else "Your name")
            .setMessage("What should I call you?")
            .setView(padded(field))
            .setPositiveButton("Save") { _, _ ->
                val n = OrbitBrain.cleanName(field.text.toString())
                if (n.isNotEmpty()) {
                    val now = LocalDateTime.now()
                    OrbitProfile.setName(context, n)
                    OrbitProfile.setLastGreeting(context, periodKey(now))
                    updateGreeting(animate = true)
                    say(
                        if (first) "Nice to meet you, $n. Tap me any time and tell me what to open, or what to remind you about."
                        else "Got it. I'll call you $n."
                    )
                }
            }
            .setNegativeButton("Later", null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        field.requestFocus()
    }

    private fun showAddDialog() {
        val act = activity() ?: return
        val field = field("e.g. Call Mum tomorrow at 5pm")
        val d = AlertDialog.Builder(act, dialogTheme)
            .setTitle("New reminder")
            .setMessage("Type it the way you'd say it. Leave out the time for a simple note.")
            .setView(padded(field))
            .setPositiveButton("Add") { _, _ ->
                val t = field.text.toString().trim()
                if (t.isNotEmpty()) {
                    val phrase = if (t.startsWith("remind", ignoreCase = true)) t else "remind me to $t"
                    addReminder(OrbitBrain.parseReminder(phrase, LocalDateTime.now()))
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        field.requestFocus()
    }

    private fun showCommandDialog() {
        val act = activity() ?: return
        val field = field("e.g. open YouTube, remind me to…")
        val d = AlertDialog.Builder(act, dialogTheme)
            .setTitle("Ask Orbit")
            .setView(padded(field))
            .setPositiveButton("Go") { _, _ ->
                val t = field.text.toString().trim()
                if (t.isNotEmpty()) handle(t)
            }
            .setNegativeButton("Cancel", null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        field.requestFocus()
    }

    private fun showFullLeaflet() {
        val act = activity() ?: return
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(4), dp(12), dp(4))
        }
        val scroll = ScrollView(context).apply { addView(list) }
        fun fill() {
            list.removeAllViews()
            val items = ReminderStore.leaflet(context)
            val now = LocalDateTime.now()
            if (items.isEmpty()) {
                list.addView(TextView(context).apply {
                    text = "Nothing on your leaflet yet."
                    setTextColor(faint)
                    textSize = 15f
                    setPadding(0, dp(12), 0, dp(12))
                })
            }
            for (r in items) list.addView(row(r, now, full = true) { ui.post { fill() } })
        }
        fill()
        val name = OrbitProfile.name(context)
        val d = AlertDialog.Builder(act, dialogTheme)
            .setTitle(if (name.isBlank()) "Leaflet" else "$name's leaflet")
            .setView(scroll)
            .setPositiveButton("Add", null)
            .setNeutralButton("Clear ticked", null)
            .setNegativeButton("Close", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                d.dismiss()
                showAddDialog()
            }
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                ReminderStore.clearDone(context)
                fill()
            }
        }
        d.show()
    }

    // ---------------- Helpers ----------------

    private fun field(hint: String, initial: String = "") = EditText(context).apply {
        this.hint = hint
        setText(initial)
        setSelection(text.length)
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_DONE
    }

    private fun padded(v: View) = FrameLayout(context).apply {
        setPadding(dp(22), dp(4), dp(22), 0)
        addView(v)
    }

    private fun openCalendar() {
        try {
            context.startActivity(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            status.text = "< No calendar app found."
        }
    }

    private fun activity(): Activity? {
        var c: Context? = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        return (c as? Activity)?.takeIf { !it.isFinishing }
    }

    private fun has(permission: String) =
        Build.VERSION.SDK_INT < 23 || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun ask(permission: String) {
        if (Build.VERSION.SDK_INT >= 23) activity()?.requestPermissions(arrayOf(permission), 7302)
    }

    /** "Call Mum" -> "call Mum", but keeps "ZESA bill" as is. */
    private fun lowerFirst(s: String): String =
        if (s.length > 1 && s[1].isUpperCase()) s else s.replaceFirstChar { it.lowercase(Locale.UK) }

    private fun shadow(v: TextView) = v.setShadowLayer(6f, 0f, 1f, 0xCC000000.toInt())

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
