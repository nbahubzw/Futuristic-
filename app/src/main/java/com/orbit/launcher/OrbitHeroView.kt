package com.orbit.launcher

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Orbit hero: the top of the main home screen. Greets you by name, listens when you tap the avatar,
// talks back, and shows your tasks on the Leaflet widget. Swipe on the home screen for the full Tasks view.

const val HERO_ROWS = 3

private val YES_WORDS = Regex("^(?:yes|yeah|yep|yup|ya|yah|sure|ok|okay|send|send it|go ahead|do it|correct|confirm|please do|yes please)\\b", RegexOption.IGNORE_CASE)
private val NO_WORDS = Regex("^(?:no|nope|nah|cancel|don'?t|do not|stop|never ?mind|forget it|leave it)\\b", RegexOption.IGNORE_CASE)
private val SEPARATOR = Regex("\\s+(?:saying|that|to say|and say|with the message)\\s+|\\s*:\\s*", RegexOption.IGNORE_CASE)

private val SOFT_VERB = Regex("^(?:(?:hey|hi|ok|okay)\\s+)?(?:orbit\\W*)?(?:please\\s+)?(?:play|show|go to|take me to)\\b", RegexOption.IGNORE_CASE)

class OrbitHeroView(context: Context) : LinearLayout(context) {

    private val c = IosColors.of(context)
    private val dialogTheme = if (c.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    private val shadow = 0x59000000

    private val ui = Handler(Looper.getMainLooper())
    private val greetingView: TextView
    private val subline: TextView
    private val avatar: OrbitAvatarView
    private val caption: TextView
    private val captionIcon: Glyph
    private val leafletCount: TextView
    private val leafletItems: LinearLayout

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var recogniser: SpeechRecognizer? = null
    private var listening = false
    private var attached = false
    private var askedName = false
    private var shownGreeting = ""
    private var leafletCapacity = 4
    private var replyAloud = true // typed in the chat: answer on screen only
    private var listenAfterSpeech = false // Orbit asked a question out loud: listen for the answer
    private var lastUtterance: String? = null
    private var lastChat: OrbitMessages.Chat? = null
    private var pendingAction: (() -> Unit)? = null // runs when you say "yes"
    private var pendingCompose: ((String) -> Unit)? = null // your next words are the message

    private val chatHost = object : OrbitChatHost {
        override fun chatSend(text: String) = handle(text, spoken = false)
        override fun chatListen() {
            if (!listening) {
                tts?.stop()
                startListening()
            }
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        ui.post { if (attached) { refreshLeaflet(); refreshSubline() } }
    }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(8), 0, dp(8), dp(4))

        greetingView = context.label("", 30f, Color.WHITE, IosType.bold).apply {
            setAutoSizeTextTypeUniformWithConfiguration(20, 30, 1, TypedValue.COMPLEX_UNIT_SP)
            setShadowLayer(10f, 0f, 1f, shadow)
            pressable { OrbitSettings.show(context) }
        }
        subline = context.label("", 15f, 0xE6FFFFFF.toInt(), IosType.medium).apply {
            setShadowLayer(8f, 0f, 1f, shadow)
            pressable { openCalendar() }
        }
        addView(greetingView, LayoutParams(LayoutParams.MATCH_PARENT, dp(38)))
        addView(subline, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })

        val row = LinearLayout(context).apply { orientation = HORIZONTAL }

        // Left: avatar and caption pill
        val left = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        avatar = OrbitAvatarView(context).apply { setOnClickListener { onAvatarTap() } }
        left.addView(avatar, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val pill = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(0x38FFFFFF, dp(15).toFloat())
            setPadding(dp(12), dp(7), dp(12), dp(7))
            pressable { OrbitChat.show(context, chatHost, typeNow = OrbitAI.transcript().isEmpty()) }
            setOnLongClickListener {
                chooseAccent()
                true
            }
        }
        captionIcon = Glyph(context, Glyph.Kind.MIC, Color.WHITE, 1.8f)
        pill.addView(captionIcon, LayoutParams(dp(15), dp(15)).apply { rightMargin = dp(6) })
        caption = context.label("", 13f, Color.WHITE, IosType.medium, lines = 2).apply { gravity = Gravity.CENTER }
        pill.addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        left.addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
        row.addView(left, LayoutParams(0, LayoutParams.MATCH_PARENT, 0.46f))

        // Right: Leaflet widget
        val widget = LinearLayout(context).apply {
            orientation = VERTICAL
            background = rounded(c.cell, dp(22).toFloat())
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(13), dp(12), dp(10))
        }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            pressable { OrbitTasks.show(context) }
        }
        val badge = FrameLayout(context).apply { background = rounded(c.orange, dp(13).toFloat()) }
        badge.addView(Glyph(context, Glyph.Kind.LIST, Color.WHITE, 2f), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        header.addView(badge, LayoutParams(dp(26), dp(26)))
        header.addView(context.label("Leaflet", 15f, c.orange, IosType.semibold).apply { setPadding(dp(8), 0, 0, 0) },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        leafletCount = context.label("", 24f, c.label, IosType.bold)
        header.addView(leafletCount)
        widget.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        leafletItems = LinearLayout(context).apply { orientation = VERTICAL }
        widget.addView(leafletItems, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
        row.addView(widget, LayoutParams(0, LayoutParams.MATCH_PARENT, 0.54f).apply {
            leftMargin = dp(10)
            topMargin = dp(10)
            bottomMargin = dp(6)
        })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(2) })

        leafletItems.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                val cap = maxOf(1, (bottom - top) / dp(44))
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
        setCaption(idleHint(), mic = true)
        updateGreeting()
        refreshSubline()
        refreshLeaflet()
        ui.postDelayed(minuteTick, msToNextMinute())
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
        OrbitNav.safetyCheck(context)
        updateGreeting()
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
            replyAloud = true
            say(greetingSummary(name, now))
        }
        // Xiaomi turns its own gestures off for other launchers: offer Orbit's, once
        ui.postDelayed({ if (attached && hasWindowFocus()) OrbitSettings.maybeOfferGestures(context) }, 1500)
    }

    private fun periodKey(now: LocalDateTime) = now.toLocalDate().toString() + "-" + OrbitBrain.partOfDay(now.hour)

    private val minuteTick = object : Runnable {
        override fun run() {
            if (!attached) return
            refreshSubline()
            updateGreeting()
            refreshLeaflet()
            ui.postDelayed(this, msToNextMinute())
        }
    }

    private fun msToNextMinute(): Long = 60_000L - (System.currentTimeMillis() % 60_000L) + 50L

    // ---------------- Greeting, date line, Leaflet ----------------

    private fun updateGreeting() {
        val name = OrbitProfile.name(context)
        val g = if (name.isBlank()) OrbitBrain.greeting(LocalDateTime.now().hour, "") else OrbitBrain.greeting(LocalDateTime.now().hour, name)
        if (g == shownGreeting) return
        val first = shownGreeting.isEmpty()
        shownGreeting = g
        if (first) {
            greetingView.text = g
            greetingView.alpha = 0f
            greetingView.translationY = dp(6).toFloat()
            greetingView.animate().alpha(1f).translationY(0f).setDuration(420).start()
        } else {
            greetingView.animate().alpha(0f).setDuration(160).withEndAction {
                greetingView.text = g
                greetingView.animate().alpha(1f).setDuration(260).start()
            }.start()
        }
    }

    private fun refreshSubline() {
        val now = LocalDateTime.now()
        val today = ReminderStore.all(context).count { !it.done && it.local()?.toLocalDate() == now.toLocalDate() }
        val date = now.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
        val clock = ReminderText.clock(context, now)
        subline.text = "$clock · $date" + when (today) {
            0 -> ""
            1 -> " · 1 task today"
            else -> " · $today tasks today"
        }
    }

    private fun refreshLeaflet() {
        val now = LocalDateTime.now()
        val open = ReminderStore.leaflet(context).filter { !it.done }
        leafletCount.text = if (open.isEmpty()) "" else open.size.toString()
        leafletItems.removeAllViews()
        if (open.isEmpty()) {
            val empty = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                pressable { OrbitTasks.show(context) }
            }
            empty.addView(context.label("All clear", 15f, c.label, IosType.semibold).apply { gravity = Gravity.CENTER })
            empty.addView(context.label("Tap the avatar and say “Remind me to…”", 12f, c.secondary, lines = 3).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), 0)
            })
            leafletItems.addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            return
        }
        val shown = if (open.size > leafletCapacity) open.take(maxOf(1, leafletCapacity - 1)) else open
        for (r in shown) leafletItems.addView(leafletRow(r, now))
        if (open.size > shown.size) {
            leafletItems.addView(context.label("${open.size - shown.size} more", 12f, c.secondary, IosType.medium).apply {
                setPadding(dp(30), dp(4), 0, 0)
                pressable { OrbitTasks.show(context) }
            })
        }
    }

    private fun leafletRow(r: Reminder, now: LocalDateTime): View {
        val t = r.local()
        val overdue = t != null && t.isBefore(now) && r.repeat == ReminderRepeat.NONE
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            pressable { OrbitTasks.edit(context, r) }
        }
        val check = CheckCircle(context, c).apply {
            accent = c.orange
            setOnClickListener {
                checked = true
                pop()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                ui.postDelayed({ ReminderStore.setDone(context, r.id, true) }, 350)
            }
        }
        line.addView(check, LayoutParams(dp(20), dp(20)))
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        texts.addView(context.label(r.text, 14f, c.label))
        val sub = when {
            t == null -> null
            overdue -> "Overdue"
            r.repeat == ReminderRepeat.DAILY -> ReminderText.clock(context, t) + " · Daily"
            r.repeat == ReminderRepeat.WEEKDAYS -> ReminderText.clock(context, t) + " · Weekdays"
            r.repeat == ReminderRepeat.WEEKLY -> ReminderText.label(context, t, now) + " · Weekly"
            r.repeat == ReminderRepeat.MONTHLY -> ReminderText.label(context, t, now) + " · Monthly"
            else -> ReminderText.label(context, t, now)
        }
        if (sub != null) texts.addView(context.label(sub, 12f, if (overdue) c.red else c.secondary).apply { setPadding(0, dp(2), 0, 0) })
        line.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        return line
    }

    private fun greetingSummary(name: String, now: LocalDateTime): String {
        // Mornings get a full briefing of the day
        if (OrbitBrain.partOfDay(now.hour) == "morning") return OrbitBrain.greeting(now.hour, name) + ". " + dayBody(0, now, "")
        val sb = StringBuilder(OrbitBrain.greeting(now.hour, name)).append('.')
        val open = ReminderStore.all(context).filter { !it.done && it.time != null }
        val today = open.filter { it.local()?.toLocalDate() == now.toLocalDate() }
        val due = today.count { it.repeat == ReminderRepeat.NONE && it.local()?.isBefore(now) == true }
        val next = open.filter { it.local()?.isAfter(now) == true }.minByOrNull { it.time ?: Long.MAX_VALUE }
        when {
            today.isEmpty() && next == null -> sb.append(" Your schedule is clear.")
            today.isEmpty() -> sb.append(" Nothing else today.")
            else -> sb.append(" You have ${today.size} task${if (today.size == 1) "" else "s"} today.")
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
        return "You have ${open.size} task${if (open.size == 1) "" else "s"}$who. " + parts.joinToString(". ") + "." + more
    }

    // ---------------- Spoken summaries ----------------

    private fun openTasks(): List<Reminder> = ReminderStore.all(context).filter { !it.done }

    /** "in 20 minutes", "in about 2 hours", or "" when it's further away. */
    private fun spokenIn(t: LocalDateTime, now: LocalDateTime): String {
        val mins = java.time.temporal.ChronoUnit.MINUTES.between(now, t)
        return when {
            mins <= 1 -> "in a minute"
            mins < 60 -> "in $mins minutes"
            mins < 12 * 60 -> {
                val h = mins / 60
                val half = mins % 60 >= 30
                "in about " + if (h == 1L && !half) "an hour" else if (half) "$h and a half hours" else "$h hours"
            }
            else -> ""
        }
    }

    /** The day in a few sentences: what's left, what's overdue, what's done. */
    private fun dayBody(daysAhead: Int, now: LocalDateTime, who: String): String {
        val day = now.toLocalDate().plusDays(daysAhead.toLong())
        val all = ReminderStore.all(context)
        val items = all.filter { !it.done && it.local()?.toLocalDate() == day }.sortedBy { it.time ?: 0L }
        val notes = all.count { !it.done && it.time == null }
        fun list(xs: List<Reminder>) = xs.take(4).joinToString(" ") { r ->
            "At ${ReminderText.spokenClock(r.local()!!)}, ${lowerFirst(r.text)}."
        } + if (xs.size > 4) " And ${xs.size - 4} more." else ""
        if (daysAhead > 0) {
            if (items.isEmpty()) return "Tomorrow is clear$who. Nothing scheduled yet."
            return "Tomorrow you have ${items.size} task${if (items.size == 1) "" else "s"}. " + list(items)
        }
        val upcoming = items.filter { it.local()?.isAfter(now) == true }
        val overdue = all.filter { !it.done && it.repeat == ReminderRepeat.NONE && it.local()?.isBefore(now) == true }
        val doneToday = all.count { it.done && it.local()?.toLocalDate() == day }
        val sb = StringBuilder()
        if (upcoming.isEmpty() && overdue.isEmpty()) {
            sb.append("Your day is clear$who.")
            if (doneToday > 0) sb.append(" You've finished $doneToday task${if (doneToday == 1) "" else "s"} today.")
            all.filter { !it.done && it.local()?.toLocalDate() == day.plusDays(1) }.minByOrNull { it.time ?: Long.MAX_VALUE }?.let {
                sb.append(" Tomorrow starts with ${lowerFirst(it.text)}, at ${ReminderText.spokenClock(it.local()!!)}.")
            }
        } else {
            if (upcoming.isNotEmpty()) sb.append("You have ${upcoming.size} task${if (upcoming.size == 1) "" else "s"} still to do today. ").append(list(upcoming))
            if (overdue.isNotEmpty()) {
                sb.append(" Overdue: ").append(overdue.take(2).joinToString(" and ") { lowerFirst(it.text) })
                if (overdue.size > 2) sb.append(", and ${overdue.size - 2} more")
                sb.append('.')
            }
            if (doneToday > 0) sb.append(" You've already done $doneToday.")
        }
        if (notes > 0) sb.append(" Plus $notes note${if (notes == 1) "" else "s"} on your leaflet.")
        return sb.toString().trim()
    }

    private fun whatsNext(now: LocalDateTime, who: String): String {
        val upcoming = openTasks().filter { it.local()?.isAfter(now) == true }.sortedBy { it.time ?: 0L }
        if (upcoming.isEmpty()) {
            val overdue = openTasks().filter { it.repeat == ReminderRepeat.NONE && it.local()?.isBefore(now) == true }
            return if (overdue.isEmpty()) "Nothing else is scheduled$who. You're all clear."
            else "Nothing else is scheduled$who, but ${lowerFirst(overdue.first().text)} is still overdue."
        }
        val first = upcoming[0]
        val t = first.local()!!
        val soon = spokenIn(t, now)
        val sb = StringBuilder("Next up: ${lowerFirst(first.text)}, ${ReminderText.spoken(t, now)}")
        if (soon.isNotEmpty()) sb.append(", $soon")
        sb.append('.')
        upcoming.getOrNull(1)?.let { sb.append(" After that: ${lowerFirst(it.text)}, ${ReminderText.spoken(it.local()!!, now)}.") }
        return sb.toString()
    }

    private fun whatsDue(now: LocalDateTime, who: String): String {
        val open = openTasks()
        val overdue = open.filter { it.repeat == ReminderRepeat.NONE && it.local()?.isBefore(now) == true }.sortedBy { it.time ?: 0L }
        val soon = open.filter { val t = it.local(); t != null && t.isAfter(now) && t.isBefore(now.plusMinutes(60)) }.sortedBy { it.time ?: 0L }
        if (overdue.isEmpty() && soon.isEmpty()) return "Nothing is due$who. " + whatsNext(now, "")
        val sb = StringBuilder()
        if (overdue.isNotEmpty()) {
            sb.append("You have ${overdue.size} overdue task${if (overdue.size == 1) "" else "s"}: ")
            sb.append(overdue.take(3).joinToString("; ") { r ->
                val t = r.local()!!
                val from = if (t.toLocalDate() == now.toLocalDate()) "from ${ReminderText.spokenClock(t)}"
                else if (t.toLocalDate() == now.toLocalDate().minusDays(1)) "from yesterday"
                else "from " + t.format(DateTimeFormatter.ofPattern("EEEE", Locale.UK))
                "${lowerFirst(r.text)}, $from"
            })
            sb.append('.')
        }
        if (soon.isNotEmpty()) {
            sb.append(" Due soon: ").append(soon.take(2).joinToString(" and ") { "${lowerFirst(it.text)}, ${spokenIn(it.local()!!, now)}" }).append('.')
        }
        return sb.toString().trim()
    }

    private fun idleHint() = "Tap to talk"

    private fun setCaption(text: String, mic: Boolean = false) {
        caption.text = text
        captionIcon.visibility = if (mic) View.VISIBLE else View.GONE
    }

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
            setCaption("Voice isn't available. Tap here to type.")
            OrbitChat.show(context, chatHost, typeNow = true)
            return
        }
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            ask(Manifest.permission.RECORD_AUDIO)
            setCaption("Allow the microphone, then tap again")
            return
        }
        val rec = recogniser ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(voiceListener)
            recogniser = it
        }
        val lang = OrbitProfile.speechLocale(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            if (lang.isNotEmpty()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            }
            // Hints so app names and Orbit's commands are heard correctly (supported by newer recognisers)
            if (Build.VERSION.SDK_INT >= 33) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, hintWords())
        }
        listening = true
        avatar.mode = OrbitAvatarView.Mode.LISTENING
        setCaption("Listening…")
        OrbitChat.setStatus("Listening…")
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
                if (it.isNotBlank()) setCaption("“$it”")
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            avatar.level = 0f
            OrbitChat.setStatus(null)
            if (!attached) return
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
            if (heard.isEmpty()) {
                avatar.mode = OrbitAvatarView.Mode.IDLE
                setCaption("I didn't catch that", mic = true)
                return
            }
            handle(bestGuess(heard))
        }

        override fun onError(error: Int) {
            listening = false
            avatar.level = 0f
            OrbitChat.setStatus(null)
            if (!attached) return
            avatar.mode = OrbitAvatarView.Mode.IDLE
            if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                // This phone can't do the chosen accent: step down to UK English, then the phone default
                val next = when (OrbitProfile.speechLocale(context)) {
                    "" -> null
                    "en-GB", "en-US" -> ""
                    else -> "en-GB"
                }
                if (next != null) {
                    OrbitProfile.setSpeechLocale(context, next)
                    setCaption("Switched to ${accentName(next)}. Tap to talk.", mic = true)
                    return
                }
            }
            setCaption(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice needs data. Tap here to type."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone, then tap again"
                    else -> "Voice stopped. Tap here to type."
                },
                mic = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            )
        }
    }

    /** The recogniser offers several guesses; prefer one that means something. */
    private fun bestGuess(heard: List<String>): String {
        val now = LocalDateTime.now()
        val apps = OrbitAssistant.cachedApps()
        val fixed = heard.map { OrbitBrain.fixHeard(it) }.filter { it.isNotBlank() }
        if (fixed.isEmpty()) return heard.first()
        return fixed.firstOrNull { h ->
            when (val i = OrbitBrain.interpret(h, now)) {
                is HeroIntent.Other -> apps.isEmpty() || !OrbitAssistant.isCommand(i.raw, apps, context) ||
                    OrbitAssistant.parse(i.raw, apps, context) !is OrbitCommand.NotFound
                else -> true
            }
        } ?: fixed.first()
    }

    private fun hintWords(): ArrayList<String> {
        val words = ArrayList<String>()
        words.addAll(OrbitAssistant.cachedApps().map { it.label }.distinct().take(60))
        words.addAll(listOf(
            "remind me", "every day", "every weekday", "every month", "tomorrow", "tonight",
            "what's next", "what's due", "what does my day look like", "open", "call", "play",
            "orbit settings", "gesture settings", "read my messages", "who messaged me", "reply", "WhatsApp",
            "Spotify", "pause", "next song", "turn it up"
        ))
        OrbitProfile.name(context).takeIf { it.isNotBlank() }?.let { words.add(it) }
        return words
    }

    private fun accentName(tag: String) = OrbitVoice.name(tag)

    /** Lets you pick which English the speech recogniser listens for. Hold the "Tap to talk" pill, or say "change accent". */
    private fun chooseAccent() {
        OrbitVoice.choose(context) { tag -> setCaption("Listening for ${accentName(tag)}", mic = true) }
    }

    private fun handle(raw: String, spoken: Boolean = true) {
        val now = LocalDateTime.now()
        val name = OrbitProfile.name(context)
        val who = if (name.isBlank()) "" else ", $name"
        replyAloud = spoken
        if (!spoken) tts?.stop()
        listenAfterSpeech = false
        val intent = OrbitBrain.interpret(raw, now)
        val composing = pendingCompose
        val confirming = pendingAction
        // Anything about your messages stays on the phone: never sent to the AI
        val privateLine = composing != null || confirming != null || intent is HeroIntent.Messages ||
            intent is HeroIntent.OpenChat || intent is HeroIntent.SendMessage
        OrbitAI.record(true, raw, private = privateLine)
        setCaption("“$raw”")
        avatar.mode = OrbitAvatarView.Mode.THINKING
        ui.postDelayed({ if (avatar.mode == OrbitAvatarView.Mode.THINKING && !OrbitAI.thinking) avatar.mode = OrbitAvatarView.Mode.IDLE }, 3000)

        // Answering a question Orbit just asked
        if (composing != null) {
            pendingCompose = null
            if (NO_WORDS.matches(raw.trim().trimEnd('.', '!')) || raw.trim().equals("cancel", true)) say("Okay, cancelled$who.") else composing(raw.trim())
            return
        }
        if (confirming != null) {
            pendingAction = null
            if (YES_WORDS.containsMatchIn(raw.trim())) {
                confirming()
                return
            }
            if (NO_WORDS.containsMatchIn(raw.trim())) {
                say("Okay, I won't send it$who.")
                return
            }
        }

        when (intent) {
            is HeroIntent.SetName -> {
                OrbitProfile.setName(context, intent.name)
                OrbitProfile.setLastGreeting(context, periodKey(now))
                updateGreeting()
                say("Got it. I'll call you ${intent.name}.")
            }
            is HeroIntent.AddReminder -> addReminder(intent.reminder)
            HeroIntent.ListReminders -> {
                say(scheduleSummary(now, who))
                OrbitTasks.show(context)
            }
            is HeroIntent.DayBrief -> {
                say((if (intent.daysAhead == 0) "Here's your day$who. " else "") + dayBody(intent.daysAhead, now, who))
                OrbitTasks.show(context)
            }
            HeroIntent.WhatsNext -> say(whatsNext(now, who))
            HeroIntent.WhatsDue -> say(whatsDue(now, who))
            HeroIntent.VoiceSettings -> chooseAccent()
            HeroIntent.Settings -> {
                say("Here are your Orbit settings$who.")
                OrbitSettings.show(context)
            }
            HeroIntent.ClearDone -> {
                val n = ReminderStore.clearDone(context)
                say(if (n == 0) "There's nothing ticked off to clear$who." else "Cleared $n completed task${if (n == 1) "" else "s"}.")
            }
            HeroIntent.TellTime -> say("It's ${ReminderText.spokenClock(now)}$who.")
            HeroIntent.TellDate -> say("Today is " + now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.UK)) + ".")
            HeroIntent.Hello -> say("Hello$who. What can I do for you?")
            is HeroIntent.SmallTalk -> say(smallTalk(intent.kind, raw, who, now))
            is HeroIntent.Messages -> readMessages(intent.from, intent.calls, who)
            is HeroIntent.OpenChat -> openChat(intent.name, intent.app, who)
            is HeroIntent.SendMessage -> sendMessage(intent.rest, intent.verb, intent.app, who, raw)
            is HeroIntent.PlayMusic -> playMusic(intent.query, intent.app, who)
            is HeroIntent.Media -> media(intent.action, who)
            HeroIntent.Thanks -> say("Any time$who.")
            HeroIntent.Help -> say("Try: open YouTube. Call Mum. Who messaged me? Message Mum that I'm on my way. Play Jah Prayzah. Remind me every weekday at 8 to check the stock. Or ask me anything at all, like what's in the news today.")
            is HeroIntent.Voice -> {
                if (intent.on) {
                    OrbitProfile.setVoiceOn(context, true)
                    say("Voice on$who.")
                } else {
                    OrbitProfile.setVoiceOn(context, false)
                    tts?.stop()
                    say("Okay$who, I'll stay quiet. Say “voice on” to hear me again.")
                }
            }
            is HeroIntent.Other -> withApps { apps ->
                // Opening and calling stay instant; everything else goes to Orbit's AI
                if (OrbitAssistant.isCommand(intent.raw, apps, context)) runAppCommand(intent.raw, who, raw) else askAI(raw)
            }
            HeroIntent.Empty -> {
                avatar.mode = OrbitAvatarView.Mode.IDLE
                setCaption(idleHint(), mic = true)
            }
        }
    }

    private fun addReminder(p: ParsedReminder) {
        val who = OrbitProfile.name(context).let { if (it.isBlank()) "" else ", $it" }
        if (p.time == null && p.text == "Reminder") {
            say("What should I remind you about$who?")
            OrbitTasks.edit(context, null)
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
            p.repeat != ReminderRepeat.NONE -> OrbitBrain.repeatPhrase(p.repeat, time) + " at " + ReminderText.spokenClock(time)
            else -> ReminderText.spoken(time, now)
        }
        say(
            if (whenText == null) "Added to your leaflet$who: ${lowerFirst(p.text)}."
            else "Okay$who. I'll remind you $whenText: ${lowerFirst(p.text)}."
        )
    }

    private fun runAppCommand(raw: String, who: String, original: String = raw) {
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
                    say("Here are your apps, sorted into groups.")
                    OrbitAssistant.show(context)
                }
                is OrbitCommand.NotFound ->
                    if (SOFT_VERB.containsMatchIn(original)) askAI(original) // "show me the news", "play some music"
                    else say("Sorry$who, I couldn't find ${cmd.query}.")
                OrbitCommand.Empty -> {
                    avatar.mode = OrbitAvatarView.Mode.IDLE
                    setCaption(idleHint(), mic = true)
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
            setCaption("No phone app found")
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
                engine.setPitch(0.95f)
                engine.setSpeechRate(1.0f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        ui.post { if (!listening) avatar.mode = OrbitAvatarView.Mode.SPEAKING }
                    }

                    override fun onDone(utteranceId: String?) {
                        ui.post {
                            if (!listening) avatar.mode = OrbitAvatarView.Mode.IDLE
                            if (attached && listenAfterSpeech && utteranceId == lastUtterance && !listening) {
                                listenAfterSpeech = false
                                startListening()
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        ui.post { if (!listening) avatar.mode = OrbitAvatarView.Mode.IDLE }
                    }
                })
                ttsReady = true
                pendingSpeech?.let {
                    pendingSpeech = null
                    speak(it)
                }
            }
        }
    }

    /** Shows the reply under the avatar, adds it to the conversation and speaks it (unless voice is off or it was typed). */
    private fun say(
        text: String,
        source: OrbitAI.Source = OrbitAI.Source.LOCAL,
        private: Boolean = false,
        aloud: Boolean = true,
        expectReply: Boolean = false
    ) {
        setCaption(text)
        OrbitAI.record(false, text, source, private)
        if (!aloud || !OrbitProfile.voiceOn(context) || !replyAloud) {
            if (!listening) avatar.mode = OrbitAvatarView.Mode.IDLE
            replyAloud = true
            return
        }
        listenAfterSpeech = expectReply
        speak(text)
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            pendingSpeech = text
            return
        }
        // Long answers are spoken in pieces (speech engines have a length limit)
        val limit = minOf(TextToSpeech.getMaxSpeechInputLength() - 100, 3500)
        val pieces = ArrayList<String>()
        var rest = text.replace('\n', ' ')
        while (rest.length > limit) {
            var cut = rest.lastIndexOf(". ", limit)
            if (cut < limit / 2) cut = rest.lastIndexOf(' ', limit)
            if (cut <= 0) cut = limit
            pieces.add(rest.substring(0, cut + 1))
            rest = rest.substring(cut + 1).trimStart()
        }
        if (rest.isNotBlank()) pieces.add(rest)
        for ((i, piece) in pieces.withIndex()) {
            val id = "orbit-" + SystemClock.uptimeMillis() + "-" + i
            lastUtterance = id
            engine.speak(piece, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    // ---------------- Messages ----------------

    private sealed class Target {
        class InChat(val chat: OrbitMessages.Chat) : Target()
        class Person(val name: String, val number: String) : Target()
    }

    private fun nameOf(t: Target) = when (t) {
        is Target.InChat -> t.chat.title
        is Target.Person -> t.name
    }

    private fun needAccess(who: String): Boolean {
        if (OrbitMessages.hasAccess(context)) return false
        say("To see your messages I need notification access$who. Switch Orbit on in the list, then ask me again.")
        OrbitMessages.openAccessSettings(context)
        return true
    }

    private fun currentChats(): List<OrbitMessages.Chat> =
        if (OrbitMessages.hasAccess(context)) OrbitMessages.chats(context) else emptyList()

    /** The chat you were just talking about, refreshed if it's still there. */
    private fun recentChat(chats: List<OrbitMessages.Chat>): OrbitMessages.Chat? =
        lastChat?.let { l -> chats.firstOrNull { it.key == l.key } ?: chats.firstOrNull { it.pkg == l.pkg && it.title == l.title } ?: l }
            ?: chats.firstOrNull { !it.missedCall }

    private fun readMessages(from: String?, calls: Boolean, who: String) {
        if (needAccess(who)) return
        if (!OrbitMessages.connected()) {
            OrbitMessages.reconnect(context)
            say("Give me a second to reconnect to your notifications$who, then ask me again.")
            return
        }
        val chats = OrbitMessages.chats(context)
        if (calls) {
            val missed = chats.filter { it.missedCall }
            say(
                when {
                    missed.isEmpty() -> "No missed calls$who."
                    missed.size == 1 -> "You have a missed call from ${missed[0].title}."
                    else -> "Missed calls from " + missed.take(4).joinToString(", ") { it.title } + "."
                },
                private = true
            )
            return
        }
        val convos = chats.filter { !it.missedCall }
        if (from != null) {
            val ch = OrbitMessages.find(convos, from)
            if (ch == null) {
                say("No new messages from $from$who.", private = true)
                return
            }
            lastChat = ch
            say(OrbitMessages.readChat(ch) + if (ch.reply != null) " Say reply, then your message." else "", private = true)
            return
        }
        lastChat = convos.firstOrNull()
        val hint = if (convos.size == 1 && convos[0].reply != null) " Say reply, then your message." else ""
        say(OrbitMessages.summary(chats, who) + hint, private = true)
    }

    private fun openChat(name: String?, app: String?, who: String) {
        val chats = currentChats()
        val ch = if (name == null) recentChat(chats) else OrbitMessages.find(chats, name)
        if (ch != null) {
            lastChat = ch
            say("Opening ${ch.title}.", private = true)
            OrbitMessages.open(context, ch)
            return
        }
        if (name == null) {
            say("There's no new message to open$who.")
            return
        }
        val t = resolve(name, chats, who, quiet = false) as? Target.Person ?: return
        val sms = app == "sms" || app == "messages"
        val ok = if (sms) OrbitMessages.smsTo(context, t.number, null)
        else OrbitMessages.whatsappTo(context, t.number, null) || OrbitMessages.smsTo(context, t.number, null)
        say(if (ok) "Opening your chat with ${t.name}." else "I couldn't open a chat with ${t.name}$who.", private = true)
    }

    /** Finds who you mean: someone in your notifications first, then your contacts. */
    private fun resolve(name: String, chats: List<OrbitMessages.Chat>, who: String, quiet: Boolean): Target? {
        val cleanName = name.trim().replace(Regex("^(?:to|my)\\s+", RegexOption.IGNORE_CASE), "")
        if (cleanName.length < 2) return null
        OrbitMessages.find(chats.filter { !it.missedCall }, cleanName)?.let { return Target.InChat(it) }
        if (!has(Manifest.permission.READ_CONTACTS)) {
            if (!quiet) {
                ask(Manifest.permission.READ_CONTACTS)
                say("I need access to your contacts first$who. Allow it, then ask me again.")
            }
            return null
        }
        val hit = OrbitAssistant.findContact(context, cleanName)
        // When guessing where the name ends, only accept a contact whose name really starts that way
        val fits = hit != null && (!quiet || hit.first.lowercase(Locale.UK).split(' ').any { it.startsWith(cleanName.lowercase(Locale.UK)) } ||
            hit.first.lowercase(Locale.UK).startsWith(cleanName.lowercase(Locale.UK)))
        if (hit == null || !fits) {
            if (!quiet) say("I couldn't find $cleanName in your chats or contacts$who.")
            return null
        }
        return Target.Person(hit.first, hit.second)
    }

    private fun sendMessage(rest: String, verb: String, app: String?, who: String, original: String) {
        val chats = currentChats().filter { !it.missedCall }
        var r = rest.trim()
        var target: Target? = null
        var text = ""
        if (verb == "reply" && !r.lowercase(Locale.UK).startsWith("to ")) {
            val ch = recentChat(chats)
            if (ch == null) {
                say("There's no message to reply to$who.")
                return
            }
            target = Target.InChat(ch)
            text = r.replace(Regex("^(?:saying|that|with|and say)\\s+", RegexOption.IGNORE_CASE), "")
        } else {
            if (verb == "reply") r = r.drop(3).trim()
            if (r.isEmpty()) {
                say("Who should I message$who?", expectReply = true)
                pendingCompose = { answer -> sendMessage(answer, "message", app, who, answer) }
                return
            }
            val sep = SEPARATOR.find(r)
            if (sep != null && sep.range.first > 0) {
                text = r.substring(sep.range.last + 1).trim()
                target = resolve(r.substring(0, sep.range.first), chats, who, quiet = verb == "tell")
                if (target == null) {
                    if (verb == "tell") askAI(original)
                    return
                }
            } else {
                // No "that" or "saying": the name is the first one, two or three words ("Mum I'm late")
                val words = r.split(' ').filter { it.isNotBlank() }
                for (k in minOf(3, words.size) downTo 1) {
                    val t = resolve(words.take(k).joinToString(" "), chats, who, quiet = true) ?: continue
                    target = t
                    text = words.drop(k).joinToString(" ")
                    break
                }
                if (target == null) {
                    when {
                        verb == "tell" -> askAI(original)
                        !has(Manifest.permission.READ_CONTACTS) -> {
                            ask(Manifest.permission.READ_CONTACTS)
                            say("I need access to your contacts first$who. Allow it, then ask me again.")
                        }
                        else -> say("I couldn't find ${words.first()} in your chats or contacts$who.")
                    }
                    return
                }
            }
        }
        val tgt = target ?: return
        if (text.isBlank()) {
            say("What should I say to ${nameOf(tgt)}?", private = true, expectReply = true)
            pendingCompose = { answer -> deliver(tgt, answer, app, who) }
            return
        }
        deliver(tgt, text, app, who)
    }

    private fun deliver(target: Target, text: String, app: String?, who: String) {
        val message = text.trim().replaceFirstChar { it.uppercase(Locale.UK) }
        when (target) {
            is Target.InChat -> {
                val ch = target.chat
                lastChat = ch
                if (ch.reply != null) {
                    say("Send “$message” to ${ch.title} on ${ch.app}? Say yes to send it.", private = true, expectReply = true)
                    pendingAction = {
                        if (OrbitMessages.reply(context, ch, message)) {
                            say("Sent to ${ch.title}$who.", private = true)
                        } else {
                            copyText(message)
                            OrbitMessages.open(context, ch)
                            say("I couldn't send it from here, so I've opened the chat and copied your message. Paste it and tap send.", private = true)
                        }
                    }
                } else {
                    copyText(message)
                    OrbitMessages.open(context, ch)
                    say("I've opened your chat with ${ch.title} and copied your message. Paste it and tap send.", private = true)
                }
            }
            is Target.Person -> {
                val sms = app == "sms" || app == "text" || app == "text message" || app == "messages"
                val ok = if (sms) OrbitMessages.smsTo(context, target.number, message)
                else OrbitMessages.whatsappTo(context, target.number, message) || OrbitMessages.smsTo(context, target.number, message)
                say(if (ok) "Your message to ${target.name} is ready. Just tap send." else "I couldn't open a chat with ${target.name}$who.", private = true)
            }
        }
    }

    private fun copyText(text: String) {
        try {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Message", text))
        } catch (e: Exception) {
        }
    }

    // ---------------- Music ----------------

    private fun playMusic(query: String?, app: String?, who: String) {
        if (query == null) {
            musicReply(OrbitMedia.resume(context, app, who))
            return
        }
        if (app != null) {
            musicReply(OrbitMedia.play(context, query, app, who))
            return
        }
        withApps { apps ->
            // "play Subway Surfers" is a game, not a song
            val cmd = "play $query"
            if (OrbitAssistant.isCommand(cmd, apps, context) && OrbitAssistant.parse(cmd, apps, context) is OrbitCommand.OpenApp) runAppCommand(cmd, who)
            else musicReply(OrbitMedia.play(context, query, null, who))
        }
    }

    /** Music starting shouldn't be talked over: success shows on screen, problems are spoken. */
    private fun musicReply(text: String) {
        val ok = text.startsWith("Playing") || text.startsWith("Here's") || text.startsWith("Opening")
        say(text, aloud = !ok)
    }

    private fun media(action: String, who: String) {
        tts?.stop()
        val spoken = OrbitMedia.control(context, action, who)
        val caption = when (action) {
            "pause" -> "Paused"
            "resume" -> "Playing"
            "next" -> "Next song"
            "previous" -> "Previous song"
            "mute" -> "Muted"
            else -> spoken
        }
        say(caption.ifEmpty { "Done" }, aloud = false)
    }

    // ---------------- Orbit's AI ----------------

    private fun askAI(question: String) {
        val aloud = replyAloud
        avatar.mode = OrbitAvatarView.Mode.THINKING
        setCaption("Thinking…")
        OrbitAI.setThinking(true)
        OrbitAI.ask(context, question) { reply ->
            OrbitAI.setThinking(false)
            if (!attached) {
                OrbitAI.record(false, reply.text, reply.source)
                return@ask
            }
            replyAloud = aloud
            say(reply.text, reply.source)
        }
    }

    private fun smallTalk(kind: String, raw: String, who: String, now: LocalDateTime): String {
        val first = raw.trim().lowercase(Locale.UK).substringBefore(' ').trimEnd(',', '!', '.', '?')
        return when (kind) {
            "how_are_you" -> listOf(
                "I'm doing well, thanks$who. How can I help?",
                "All good here$who. What can I do for you?",
                "Very well, thank you$who. What's on your mind?"
            ).random()
            "makadii" -> "Ndiripo, makadiiwo$who?"
            "shona_hello" -> when (first) {
                "mangwanani" -> "Mangwanani$who! What can I do for you this morning?"
                "masikati" -> "Masikati$who! What can I do for you?"
                "manheru" -> "Manheru$who! What can I do for you?"
                else -> "Mhoro$who! What can I do for you?"
            }
            "who" -> "I'm Orbit, your assistant$who. I can open apps, make calls, keep your tasks and reminders, and answer just about anything you ask."
            "night" -> {
                val tomorrow = openTasks().mapNotNull { r -> r.local()?.let { r to it } }
                    .filter { it.second.toLocalDate() == now.toLocalDate().plusDays(1) }
                    .minByOrNull { it.second }
                if (tomorrow == null) "Good night$who. Sleep well."
                else "Good night$who. Your first task tomorrow is ${lowerFirst(tomorrow.first.text)} at ${ReminderText.spokenClock(tomorrow.second)}. Sleep well."
            }
            "bye" -> "Bye for now$who."
            "praise" -> "Thank you$who, that's kind of you."
            else -> "Hello$who."
        }
    }

    // ---------------- Dialogs ----------------

    private fun askName(first: Boolean) {
        val act = context.findActivity() ?: return
        val field = field("Your name", OrbitProfile.name(context)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        }
        if (first) say("Hello! I'm Orbit, your assistant. What should I call you?")
        val d = AlertDialog.Builder(act, dialogTheme)
            .setTitle(if (first) "Hi, I'm Orbit" else "Your Name")
            .setMessage("What should I call you?")
            .setView(padded(field))
            .setPositiveButton("Save") { _, _ ->
                val n = OrbitBrain.cleanName(field.text.toString())
                if (n.isNotEmpty()) {
                    OrbitProfile.setName(context, n)
                    OrbitProfile.setLastGreeting(context, periodKey(LocalDateTime.now()))
                    updateGreeting()
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
            setCaption("No calendar app found")
        }
    }

    private fun has(permission: String) =
        Build.VERSION.SDK_INT < 23 || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun ask(permission: String) {
        if (Build.VERSION.SDK_INT >= 23) context.findActivity()?.requestPermissions(arrayOf(permission), 7302)
    }

    /** "Call Mum" -> "call Mum", but keeps "ZESA bill" as is. */
    private fun lowerFirst(s: String): String =
        if (s.length > 1 && s[1].isUpperCase()) s else s.replaceFirstChar { it.lowercase(Locale.UK) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
