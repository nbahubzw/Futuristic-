package com.orbit.launcher

// Orbit Assistant, file 4 of 4: the command bar screen (built in code, no layout XML needed).

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import java.util.Locale

class OrbitAssistantDialog(
    private val activity: Activity,
    private val startListening: Boolean
) : Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {

    private val bgColour = 0xF2121318.toInt()
    private val fieldColour = 0xFF262A33.toInt()
    private val textMain = Color.WHITE
    private val textDim = 0xFF9AA0A6.toInt()
    private val accent = 0xFF8AB4F8.toInt()

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var input: EditText
    private lateinit var mic: ImageButton
    private lateinit var status: TextView
    private lateinit var results: LinearLayout

    private var apps: List<AssistantApp> = emptyList()
    private var shownGroup: String? = null
    private var recogniser: SpeechRecognizer? = null
    private var listening = false
    private var closed = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        window?.apply {
            setBackgroundDrawable(ColorDrawable(bgColour))
            setLayout(match, match)
            setWindowAnimations(android.R.style.Animation_Dialog)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        render("")
        OrbitAssistant.loadApps(context) { list ->
            apps = list
            if (!closed) render(input.text.toString())
        }
        if (startListening) startVoice() else input.requestFocus()
    }

    override fun onStop() {
        closed = true
        recogniser?.destroy()
        recogniser = null
        OrbitAssistant.closed(this)
        super.onStop()
    }

    // ---------------- Layout ----------------

    @Suppress("DEPRECATION")
    private fun buildLayout(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        if (Build.VERSION.SDK_INT >= 23) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(dp(16), dp(16) + insets.systemWindowInsetTop, dp(16), dp(8) + insets.systemWindowInsetBottom)
                insets
            }
        }

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(context).apply {
            hint = "Ask Orbit: open YouTube, call Mum..."
            setHintTextColor(textDim)
            setTextColor(textMain)
            textSize = 16f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_GO
            background = rounded(fieldColour, dp(24))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnEditorActionListener { _, actionId, event ->
                val enterKey = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enterKey) {
                    execute(text.toString())
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    shownGroup = null
                    render(s?.toString().orEmpty())
                }
            })
        }
        mic = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = rounded(fieldColour, dp(24))
            contentDescription = "Speak"
            setOnClickListener { if (listening) stopVoice() else startVoice() }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, wrap, 1f))
        bar.addView(mic, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })

        status = TextView(context).apply {
            setTextColor(textDim)
            textSize = 13f
            setPadding(dp(6), dp(10), dp(6), dp(6))
        }

        results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(results, ViewGroup.LayoutParams(match, wrap))
        }

        root.addView(bar, LinearLayout.LayoutParams(match, wrap))
        root.addView(status, LinearLayout.LayoutParams(match, wrap))
        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        return root
    }

    private fun render(query: String) {
        if (closed) return
        results.removeAllViews()
        val q = query.trim()
        val group = shownGroup
        when {
            group != null -> {
                val list = OrbitAssistant.organise(context, apps).firstOrNull { it.first == group }?.second.orEmpty()
                if (list.isEmpty()) status.text = "No ${group.lowercase(Locale.ROOT)} apps yet" else addSection(group, list)
            }
            q.isEmpty() -> {
                status.text = if (apps.isEmpty()) "Loading your apps..." else "Try: open YouTube, play Subway Surfers, call Mum, dial *151#"
                for ((name, list) in OrbitAssistant.organise(context, apps)) addSection(name, list)
            }
            else -> {
                status.text = OrbitAssistant.describe(OrbitAssistant.parse(q, apps, context))
                for (app in OrbitAssistant.suggestions(q, apps, context)) addRow(app)
            }
        }
    }

    private fun addSection(name: String, list: List<AssistantApp>) {
        results.addView(textView(name.uppercase(Locale.ROOT), 12f, accent).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(16), dp(6), dp(4))
        })
        for (chunk in list.chunked(4)) {
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 4f
            }
            for (app in chunk) line.addView(cell(app), LinearLayout.LayoutParams(0, wrap, 1f))
            repeat(4 - chunk.size) { line.addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            results.addView(line, LinearLayout.LayoutParams(match, wrap))
        }
    }

    private fun cell(app: AssistantApp): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4), dp(8), dp(4), dp(8))
        addView(ImageView(context).apply { setImageDrawable(iconFor(app)) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(textView(app.label, 12f, textMain).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(match, wrap))
        setOnClickListener { open(app) }
    }

    private fun addRow(app: AssistantApp) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { open(app) }
        }
        row.addView(ImageView(context).apply { setImageDrawable(iconFor(app)) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        texts.addView(textView(app.label, 16f, textMain))
        texts.addView(textView(app.group, 12f, textDim))
        row.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f))
        results.addView(row, LinearLayout.LayoutParams(match, wrap))
    }

    // ---------------- Actions ----------------

    private fun execute(text: String) {
        when (val cmd = OrbitAssistant.parse(text, apps, context)) {
            is OrbitCommand.OpenApp -> open(cmd.app)
            is OrbitCommand.Dial -> dial(cmd.number)
            is OrbitCommand.CallContact -> callContact(cmd.name)
            is OrbitCommand.ShowGroup -> {
                shownGroup = cmd.group
                render(input.text.toString())
                if (results.childCount > 0) status.text = "Your ${cmd.group.lowercase(Locale.ROOT)} apps"
            }
            is OrbitCommand.NotFound -> status.text = "I couldn't find \"${cmd.query}\""
            OrbitCommand.OpenDialler -> dial(null)
            OrbitCommand.Empty -> Unit
        }
    }

    private fun open(app: AssistantApp) {
        if (OrbitAssistant.launch(context, app)) dismiss() else status.text = "Couldn't open ${app.label}"
    }

    private fun dial(number: String?) {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            if (number != null) data = Uri.parse("tel:" + Uri.encode(number))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            dismiss()
        } catch (e: ActivityNotFoundException) {
            status.text = "No phone app found"
        }
    }

    private fun callContact(name: String) {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            askPermission(Manifest.permission.READ_CONTACTS)
            status.text = "Allow contacts access, then try again (or Settings > Apps > Orbit > Permissions)"
            return
        }
        val hit = OrbitAssistant.findContact(context, name)
        if (hit == null) status.text = "No contact called \"$name\"" else dial(hit.second)
    }

    // ---------------- Voice ----------------

    private fun startVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            status.text = "Voice isn't available on this phone. You can still type"
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            askPermission(Manifest.permission.RECORD_AUDIO)
            status.text = "Allow the microphone, then tap the mic again"
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
        mic.background = rounded(accent, dp(24))
        status.text = "Listening..."
        rec.startListening(intent)
    }

    private fun stopVoice() {
        recogniser?.stopListening()
        resetMic()
    }

    private fun resetMic() {
        listening = false
        mic.background = rounded(fieldColour, dp(24))
    }

    private val voiceListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onEndOfSpeech() {
            if (!closed) status.text = "Thinking..."
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (closed) return
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { setInput(it) }
        }

        override fun onResults(bundle: Bundle?) {
            if (closed) return
            resetMic()
            val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (heard.isEmpty()) {
                status.text = "I didn't catch that"
                return
            }
            // The recogniser gives several guesses; use the first one that matches something
            val best = heard.firstOrNull { OrbitAssistant.parse(it, apps, context) !is OrbitCommand.NotFound } ?: heard.first()
            setInput(best)
            input.post { if (!closed) execute(best) }
        }

        override fun onError(error: Int) {
            if (closed) return
            resetMic()
            status.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Tap the mic and try again"
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice needs data or an offline speech pack. You can still type"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone, then tap the mic again"
                else -> "Voice stopped (code $error). You can still type"
            }
        }
    }

    // ---------------- Helpers ----------------

    private fun setInput(text: String) {
        input.setText(text)
        input.setSelection(input.text.length)
    }

    private fun hasPermission(permission: String): Boolean =
        Build.VERSION.SDK_INT < 23 || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun askPermission(permission: String) {
        if (Build.VERSION.SDK_INT >= 23) activity.requestPermissions(arrayOf(permission), 7301)
    }

    private fun iconFor(app: AssistantApp): Drawable? =
        app.icon?.constantState?.newDrawable(context.resources)?.mutate() ?: app.icon

    private fun textView(s: String, size: Float, colour: Int) = TextView(context).apply {
        text = s
        textSize = size
        setTextColor(colour)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun rounded(colour: Int, radius: Int) = GradientDrawable().apply {
        setColor(colour)
        cornerRadius = radius.toFloat()
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
