package com.orbit.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference

// Orbit Settings: your name, voice and the navigation gestures, in an iOS-style sheet.
// Open it by tapping the greeting, the gear on the Today screen, or saying "Orbit settings".

object OrbitVoice {
    val accents = listOf(
        "en-ZA" to "English (South Africa) · closest to Zimbabwe",
        "en-KE" to "English (Kenya)",
        "en-NG" to "English (Nigeria)",
        "en-GB" to "English (UK)",
        "en-US" to "English (US)",
        "" to "Phone default"
    )

    fun name(tag: String): String =
        accents.firstOrNull { it.first == tag }?.second?.substringBefore(" ·") ?: "Phone default"

    /** Lets you pick which English the speech recogniser listens for. */
    fun choose(context: Context, onChosen: (String) -> Unit) {
        val act = context.findActivity() ?: return
        val current = accents.indexOfFirst { it.first == OrbitProfile.speechLocale(act) }.coerceAtLeast(0)
        AlertDialog.Builder(act, OrbitSettings.dialogTheme(act))
            .setTitle("Voice Accent")
            .setSingleChoiceItems(accents.map { it.second }.toTypedArray(), current) { d, which ->
                OrbitProfile.setSpeechLocale(act, accents[which].first)
                d.dismiss()
                onChosen(accents[which].first)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

object OrbitSettings {
    private var current: WeakReference<Dialog>? = null

    fun dialogTheme(c: Context) =
        if (IosColors.of(c).dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    fun show(context: Context) {
        val act = context.findActivity() ?: return
        dismiss()
        val d = OrbitSettingsSheet(act)
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

    /** Once, on Xiaomi phones: offer to bring swipe gestures back. */
    fun maybeOfferGestures(context: Context) {
        val act = context.findActivity() ?: return
        if (!OrbitNav.isXiaomi() || OrbitNav.gesturesOn(act) || OrbitNav.prompted(act)) return
        OrbitNav.setPrompted(act)
        AlertDialog.Builder(act, dialogTheme(act))
            .setTitle("Get your gestures back")
            .setMessage("Xiaomi switches off swipe gestures when another launcher is your home screen. Orbit can give you its own iOS-style gestures and hide the buttons.")
            .setPositiveButton("Set Up") { _, _ -> show(act) }
            .setNegativeButton("Not Now", null)
            .show()
    }

    fun showGestureGuide(act: Activity) {
        AlertDialog.Builder(act, dialogTheme(act))
            .setTitle("Turn on Orbit Gestures")
            .setMessage(
                "1. Tap Open Settings.\n" +
                    "2. Find Orbit Gestures (under Downloaded apps or Installed services) and switch it on.\n\n" +
                    "If the switch is greyed out, Android is protecting an app installed outside the Play Store. " +
                    "Tap App Info, open the ⋮ menu, choose Allow restricted settings, then try again."
            )
            .setPositiveButton("Open Settings") { _, _ -> OrbitNav.openAccessibilitySettings(act) }
            .setNeutralButton("App Info") { _, _ ->
                try {
                    act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.packageName)))
                } catch (e: Exception) {
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showGrantGuide(act: Activity) {
        val c = IosColors.of(act)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(22), act.dp(4), act.dp(22), 0)
        }
        fun para(text: String) = act.label(text, 15f, c.label, lines = 8).apply { setPadding(0, act.dp(8), 0, act.dp(4)) }
        fun code(text: String) = TextView(act).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setTextColor(c.label)
            setTextIsSelectable(true)
            background = rounded(c.fill, act.dp(8).toFloat())
            setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8))
        }
        box.addView(para("Android only lets Orbit hide its buttons after a one-time permission. You give it once, from the LADB app on this phone or from a computer."))
        box.addView(para("On this phone, open LADB (with Wireless debugging switched on in Developer options) and run:"))
        box.addView(code(OrbitNav.PHONE_COMMAND))
        box.addView(para("Or from a computer with ADB:"))
        box.addView(code(OrbitNav.COMPUTER_COMMAND))
        box.addView(para("Then come back and switch on Hide Android Buttons."))
        val scroll = ScrollView(act).apply { addView(box) }
        AlertDialog.Builder(act, dialogTheme(act))
            .setTitle("One-Time Setup")
            .setView(scroll)
            .setPositiveButton("Copy Command") { _, _ ->
                act.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Orbit command", OrbitNav.PHONE_COMMAND))
                Toast.makeText(act, "Command copied", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Get LADB") { _, _ ->
                try {
                    act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=LADB")))
                } catch (e: Exception) {
                    act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=LADB&c=apps")))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}

class OrbitSettingsSheet(private val activity: Activity) : Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {

    private val c = IosColors.of(activity)
    private val pink = if (c.dark) 0xFFFF375F.toInt() else 0xFFFF2D55.toInt()
    private lateinit var content: LinearLayout

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
        val nav = FrameLayout(ctx)
        nav.addView(ctx.label("Settings", 17f, c.label, IosType.semibold),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        nav.addView(ctx.label("Done", 17f, c.blue, IosType.semibold).apply {
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            pressable { dismiss() }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        sheet.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(50)))
        content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ctx.dp(40))
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        sheet.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(sheet)
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Coming back from Android settings or LADB: pick up any change
        if (hasFocus && ::content.isInitialized) render()
    }

    private fun render() {
        val ctx = context
        content.removeAllViews()
        val gestures = OrbitNav.gesturesOn(ctx)
        val allowed = OrbitNav.canHideButtons(ctx)
        val want = OrbitNav.wantHidden(ctx)
        if (gestures && allowed && want && !OrbitNav.buttonsHidden(ctx)) OrbitGestureService.refresh()

        // Profile
        content.addView(header("Profile"))
        val name = OrbitProfile.name(ctx)
        content.addView(group(listOf(valueRow(Glyph.Kind.PERSON, c.blue, "Name", name.ifBlank { "Not set" }) { editName() })))

        // Voice
        content.addView(header("Voice"))
        val replies = switchRow(Glyph.Kind.SPEAKER, pink, "Spoken Replies", OrbitProfile.voiceOn(ctx)) { on -> OrbitProfile.setVoiceOn(ctx, on) }
        content.addView(group(listOf(
            valueRow(Glyph.Kind.MIC, c.orange, "Accent", OrbitVoice.name(OrbitProfile.speechLocale(ctx))) {
                OrbitVoice.choose(activity) { render() }
            },
            replies
        )))
        content.addView(footer("Hold the “Tap to talk” pill or say “change accent” to switch quickly."))

        // Intelligence: answering anything
        content.addView(header("Intelligence"))
        val key = OrbitAI.geminiKey(ctx)
        val dl = OrbitOffline.progress(ctx)
        if (dl != null && dl.failed) Toast.makeText(ctx, "The offline brain download failed. Check your data and try again.", Toast.LENGTH_LONG).show()
        val offlineValue = when {
            dl != null && !dl.failed -> "Downloading ${dl.percent}%"
            OrbitOffline.ready(ctx) -> OrbitOffline.modelName(ctx)
            else -> "Off"
        }
        content.addView(group(listOf(
            valueRow(Glyph.Kind.SPARKLE, c.indigo, "Online AI", if (key.isBlank()) "Off" else "Gemini") { editGeminiKey() },
            valueRow(Glyph.Kind.CHIP, c.teal, "Offline Brain", offlineValue) { offlineOptions() }
        )))
        content.addView(footer(
            "Ask Orbit anything. Online AI uses Google Gemini with a free key and a little mobile data per question. " +
                "The offline brain runs on this phone with no data, but it's less clever. With both, Orbit uses Gemini when you're online and the offline brain when you're not."
        ))
        content.removeCallbacks(progressTick)
        if (dl != null && !dl.failed) content.postDelayed(progressTick, 1000)

        // Navigation gestures
        content.addView(header("Navigation Gestures"))
        val hide = switchRow(Glyph.Kind.NAVBAR, c.indigo, "Hide Android Buttons", want && allowed && gestures) { on ->
            when {
                on && !gestures -> {
                    render()
                    OrbitSettings.showGestureGuide(activity)
                }
                on && !allowed -> {
                    render()
                    OrbitSettings.showGrantGuide(activity)
                }
                else -> {
                    OrbitNav.setWantHidden(ctx, on)
                    OrbitGestureService.refresh()
                    content.postDelayed({ render() }, 250)
                }
            }
        }
        content.addView(group(listOf(
            valueRow(Glyph.Kind.SWIPE, c.blue, "Orbit Gestures", if (gestures) "On" else "Off") {
                if (gestures) OrbitNav.openAccessibilitySettings(activity) else OrbitSettings.showGestureGuide(activity)
            },
            hide,
            switchRow(Glyph.Kind.BACK, c.green, "Back Gesture", OrbitNav.backEnabled(ctx)) { on ->
                OrbitNav.setBackEnabled(ctx, on)
                OrbitGestureService.refresh()
            },
            switchRow(Glyph.Kind.BAR, c.gray, "Home Bar", OrbitNav.barVisible(ctx)) { on ->
                OrbitNav.setBarVisible(ctx, on)
                OrbitGestureService.refresh()
            },
            switchRow(Glyph.Kind.WAVE, c.red, "Haptics", OrbitNav.haptics(ctx)) { on -> OrbitNav.setHaptics(ctx, on) }
        )))
        val status = when {
            !gestures -> "Step 1: turn on Orbit Gestures. Step 2: hide Android's buttons."
            !allowed -> "One more step: Orbit needs a one-time permission to hide Android's buttons. Tap Hide Android Buttons to see how."
            !want -> "Android's buttons are showing. Switch on Hide Android Buttons to use Orbit's gestures instead."
            else -> "All set. Swipe up from the bottom to go home, swipe up and hold for recent apps, and swipe in from either side to go back."
        }
        content.addView(footer("$status\n\nBefore uninstalling Orbit, switch off Hide Android Buttons so your buttons come back."))
    }

    // ---------------- Building blocks ----------------

    private fun header(text: String) = context.label(text.uppercase(), 13f, c.secondary).apply {
        setPadding(context.dp(32), context.dp(22), context.dp(32), context.dp(7))
    }

    private fun footer(text: String) = context.label(text, 13f, c.secondary, lines = 8).apply {
        setPadding(context.dp(32), context.dp(7), context.dp(32), 0)
    }

    private fun group(rows: List<View>): View {
        val ctx = context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c.sheetCell, ctx.dp(12).toFloat())
            clipToOutline = true
        }
        rows.forEachIndexed { i, v ->
            if (i > 0) box.addView(View(ctx).apply { setBackgroundColor(c.separator) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = ctx.dp(60) })
            box.addView(v)
        }
        box.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = ctx.dp(16)
            rightMargin = ctx.dp(16)
        }
        return box
    }

    private fun baseRow(kind: Glyph.Kind, tint: Int, title: String): LinearLayout {
        val ctx = context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(50)
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(14), ctx.dp(8))
        }
        row.addView(ctx.iconTile(kind, tint))
        row.addView(ctx.label(title, 17f, c.label).apply { setPadding(ctx.dp(14), 0, ctx.dp(8), 0) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun valueRow(kind: Glyph.Kind, tint: Int, title: String, value: String, onTap: () -> Unit): View {
        val ctx = context
        val row = baseRow(kind, tint, title)
        row.addView(ctx.label(value, 17f, c.secondary).apply { maxWidth = ctx.dp(160) })
        row.addView(Glyph(ctx, Glyph.Kind.CHEVRON, c.ring, 2f), LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16)).apply { leftMargin = ctx.dp(6) })
        row.pressable(onTap)
        return row
    }

    private fun switchRow(kind: Glyph.Kind, tint: Int, title: String, on: Boolean, onChange: (Boolean) -> Unit): View {
        val row = baseRow(kind, tint, title)
        val sw = IosSwitch(context, c).apply {
            setChecked(on)
            this.onChange = onChange
        }
        row.addView(sw)
        return row
    }

    private val progressTick = Runnable { if (isShowing) render() }

    // ---------------- Intelligence ----------------

    private fun editGeminiKey() {
        val ctx = context
        val current = OrbitAI.geminiKey(ctx)
        val field = EditText(ctx).apply {
            setText(current)
            setSelection(text.length)
            hint = "Paste your key here"
            setSingleLine()
            typeface = Typeface.MONOSPACE
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val wrap = FrameLayout(ctx).apply {
            setPadding(ctx.dp(22), ctx.dp(4), ctx.dp(22), 0)
            addView(field)
        }
        val b = AlertDialog.Builder(activity, OrbitSettings.dialogTheme(activity))
            .setTitle("Online AI")
            .setMessage(
                "Orbit uses Google Gemini to answer anything. The key is free:\n\n" +
                    "1. Tap Get Free Key and sign in with your Google account.\n" +
                    "2. Tap Create API key, then copy it.\n" +
                    "3. Come back here, paste it and tap Save."
            )
            .setView(wrap)
            .setPositiveButton("Save") { _, _ -> saveKey(field.text.toString().trim()) }
            .setNeutralButton("Get Free Key") { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                } catch (e: Exception) {
                }
            }
        if (current.isNotBlank()) {
            b.setNegativeButton("Turn Off") { _, _ ->
                OrbitAI.setGeminiKey(ctx, "")
                render()
            }
        } else b.setNegativeButton("Cancel", null)
        val d = b.create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    private fun saveKey(key: String) {
        val ctx = context
        if (key.isBlank()) {
            OrbitAI.setGeminiKey(ctx, "")
            render()
            return
        }
        Toast.makeText(ctx, "Checking your key…", Toast.LENGTH_SHORT).show()
        OrbitAI.testKey(ctx, key) { ok, message ->
            if (!isShowing) {
                if (ok) OrbitAI.setGeminiKey(ctx, key)
                return@testKey
            }
            if (ok) {
                OrbitAI.setGeminiKey(ctx, key)
                Toast.makeText(ctx, "Gemini is on. Ask Orbit anything.", Toast.LENGTH_LONG).show()
                render()
            } else {
                AlertDialog.Builder(activity, OrbitSettings.dialogTheme(activity))
                    .setTitle("That key didn't work")
                    .setMessage(message)
                    .setPositiveButton("Save Anyway") { _, _ ->
                        OrbitAI.setGeminiKey(ctx, key)
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun offlineOptions() {
        val ctx = context
        val dl = OrbitOffline.progress(ctx)
        if (dl != null && !dl.failed) {
            AlertDialog.Builder(activity, OrbitSettings.dialogTheme(activity))
                .setTitle("Offline Brain")
                .setMessage("Downloading, ${dl.percent}% done. You can keep using your phone; it carries on in the background.")
                .setPositiveButton("OK", null)
                .setNegativeButton("Stop Download") { _, _ ->
                    OrbitOffline.cancelDownload(ctx)
                    render()
                }
                .show()
            return
        }
        val ready = OrbitOffline.ready(ctx)
        val q = OrbitOffline.QUICK
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("Download ${q.title} (${q.sizeMb} MB)" to { confirmDownload() })
        items.add("Get Gemma 3, smarter (529 MB)" to { gemmaGuide() })
        items.add("Use a Downloaded File" to { importFile() })
        if (ready) items.add("Delete Offline Brain" to { confirmDelete() })
        AlertDialog.Builder(activity, OrbitSettings.dialogTheme(activity))
            .setTitle(if (ready) "Offline Brain: ${OrbitOffline.modelName(ctx)}, ${OrbitOffline.sizeText(ctx)}" else "Offline Brain")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun freeMb(): Long = try {
        StatFs(OrbitOffline.dir(context).absolutePath).availableBytes / (1024 * 1024)
    } catch (e: Exception) {
        Long.MAX_VALUE
    }

    private fun confirmDownload() {
        val ctx = context
        val q = OrbitOffline.QUICK
        if (freeMb() < q.sizeMb + 300) {
            Toast.makeText(ctx, "Not enough space. Free up about ${q.sizeMb + 300} MB and try again.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(activity, OrbitSettings.dialogTheme(activity))
            .setTitle("Download ${q.title}?")
            .setMessage("${q.detail}. This downloads about ${q.sizeMb} MB once, so use Wi-Fi or a data bundle. After that, Orbit can answer questions with no data at all.")
            .setPositiveButton("Download") { _, _ ->
                if (OrbitOffline.startDownload(ctx, q)) {
                    Toast.makeText(ctx, "Downloading. You'll see progress in your notifications.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(ctx, "The download couldn't start. Please try again.", Toast.LENGTH_LONG).show()
                }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun gemmaGuide() {
        AlertDialog.Builder(activity, OrbitSettings.dialogTheme(activity))
            .setTitle("Get Gemma 3")
            .setMessage(
                "Gemma 3 is Google's small model and gives better answers. Google asks you to accept its licence first, so it downloads in your browser:\n\n" +
                    "1. Tap Open Page.\n" +
                    "2. Sign in, or make a free Hugging Face account.\n" +
                    "3. Accept the Gemma licence on that page.\n" +
                    "4. Open Files and versions and download ${OrbitOffline.GEMMA_FILE} (529 MB).\n" +
                    "5. Come back here and choose Use a Downloaded File."
            )
            .setPositiveButton("Open Page") { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OrbitOffline.GEMMA_PAGE)))
                } catch (e: Exception) {
                }
            }
            .setNeutralButton("Use a Downloaded File") { _, _ -> importFile() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importFile() {
        try {
            activity.startActivity(Intent(activity, ModelImportActivity::class.java))
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't open the file picker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(activity, OrbitSettings.dialogTheme(activity))
            .setTitle("Delete the offline brain?")
            .setMessage("This frees ${OrbitOffline.sizeText(context)}. Orbit will still answer with Gemini when you're online.")
            .setPositiveButton("Delete") { _, _ ->
                OrbitOffline.delete(context)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editName() {
        val ctx = context
        val field = EditText(ctx).apply {
            setText(OrbitProfile.name(ctx))
            setSelection(text.length)
            hint = "Your name"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        }
        val wrap = FrameLayout(ctx).apply {
            setPadding(ctx.dp(22), ctx.dp(4), ctx.dp(22), 0)
            addView(field)
        }
        val d = AlertDialog.Builder(activity, OrbitSettings.dialogTheme(activity))
            .setTitle("Your Name")
            .setMessage("What should Orbit call you?")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val n = OrbitBrain.cleanName(field.text.toString())
                if (n.isNotEmpty()) {
                    OrbitProfile.setName(ctx, n)
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        field.requestFocus()
    }
}
