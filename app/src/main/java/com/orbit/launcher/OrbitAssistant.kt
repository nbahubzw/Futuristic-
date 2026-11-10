package com.orbit.launcher

/*
 * Orbit Assistant
 * Offline command bar for the Orbit launcher. No internet, no API key.
 *
 *   OrbitAssistant.show(context)                          open the command bar (swipe up on the home screen)
 *   OrbitAssistant.show(context, startListening = true)   open it and start the mic straight away
 *   OrbitAssistant.dismiss()                              close it (returns true if it was open)
 *
 * Understands things like:
 *   open YouTube, yt, you tube, play Subway Surfers, subway, launch whats app, open google maps
 *   phone, open the dialler, call Mum, phone John, dial 0772 123 456, dial *151#
 *   games, social, media, work, money, tools  (shows just that group)
 *
 * Keeps things in order:
 *   Every app is sorted into Social, Media, Games, Work, Money, Tools or Other,
 *   alphabetical inside each group, with a "Most used" row at the top.
 */

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
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
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.Executors

// ------------------------------------------------------------------
// Data
// ------------------------------------------------------------------

data class AssistantApp(
    val label: String,
    val packageName: String,
    val activityName: String,
    val group: String,
    val icon: Drawable?
) {
    internal val norm: String = OrbitAssistant.normalise(label)
    internal val words: List<String> = OrbitAssistant.splitWords(label)
    internal val initials: String = words.joinToString("") { it.take(1) }
}

sealed class OrbitCommand {
    data class OpenApp(val app: AssistantApp) : OrbitCommand()
    data class Dial(val number: String) : OrbitCommand()
    data class CallContact(val name: String) : OrbitCommand()
    data class ShowGroup(val group: String) : OrbitCommand()
    data class NotFound(val query: String) : OrbitCommand()
    object OpenDialler : OrbitCommand()
    object Empty : OrbitCommand()
}

// ------------------------------------------------------------------
// Brain: indexing, grouping, matching, parsing
// ------------------------------------------------------------------

object OrbitAssistant {

    const val MOST_USED = "Most used"
    val GROUP_ORDER = listOf("Social", "Media", "Games", "Work", "Money", "Tools", "Other")

    private const val PREFS = "orbit_assistant_usage"
    private const val MIN_SCORE = 300

    private val worker = Executors.newSingleThreadExecutor()
    private val mainThread by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var cache: List<AssistantApp> = emptyList()

    private val CAMEL = Regex("([a-z])([A-Z])")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex("\\s+")
    private val END_PUNCT = Regex("[!?.]+$")
    private val FILLER = Regex("^(?:(?:hey|hi|ok|okay)\\s+)?(?:orbit\\b\\s*)?(?:(?:please|can you|could you|would you|i want to|i wanna)\\s+)*")
    private val TRAILING = Regex("\\s+(?:please|for me|now|app|application)$")
    private val CALL = Regex("^(?:call|ring|phone|dial)\\s+(.+)$")
    private val OPEN = Regex("^(?:open|launch|start|run|play|go to|goto|show me|show|take me to)\\s+(.+)$")
    private val LEADING_ARTICLE = Regex("^(?:the|my)\\s+")
    private val NUMBER = Regex("^[+*#0-9]{2,20}$")
    private val NUMBER_JUNK = Regex("[\\s().-]")

    // Short names people actually type or say
    private val NICKNAMES = mapOf(
        "yt" to "youtube",
        "wa" to "whatsapp",
        "ig" to "instagram",
        "insta" to "instagram",
        "fb" to "facebook",
        "tt" to "tiktok",
        "gmaps" to "maps",
        "browser" to "chrome",
        "internet" to "chrome",
        "calc" to "calculator",
        "pics" to "gallery",
        "subway" to "subwaysurfers"
    )

    // Brand words that should not decide a match on their own ("google maps" should find Maps, not Drive)
    private val GENERIC_WORDS = setOf(
        "google", "samsung", "galaxy", "microsoft", "android", "mi", "xiaomi",
        "huawei", "tecno", "infinix", "itel", "app", "the", "my"
    )

    private val DIALLER_WORDS = setOf("phone", "dialer", "dialler", "dial", "dialpad", "keypad", "call", "calls")

    private val GROUP_WORDS = mapOf(
        "social" to "Social", "chats" to "Social",
        "media" to "Media", "videos" to "Media",
        "games" to "Games", "game" to "Games", "gaming" to "Games",
        "work" to "Work",
        "money" to "Money", "banking" to "Money", "finance" to "Money",
        "tools" to "Tools", "utilities" to "Tools",
        "other" to "Other"
    )

    // Keyword fallback for apps that don't declare a category (checked in this order)
    private val GROUP_KEYWORDS = listOf(
        "Games" to listOf(
            "game", "kiloo", "sybo", "supercell", "rovio", "miniclip", "gameloft", ".king.",
            "roblox", "mojang", "minecraft", "pubg", "freefire", "ludo", "chess", "efootball", "fifa"
        ),
        "Money" to listOf(
            "ecocash", "innbucks", "onemoney", "telecash", "bank", "wallet", "pay", "mukuru",
            "worldremit", "zipit", "stanbic", "cbz", "steward", "fbc"
        ),
        "Work" to listOf(
            "office", "excel", "powerpoint", "docs", "sheets", "slides", "drive", "gmail", "outlook",
            "mail", "teams", "zoom", "meet", "notes", "keep", "calendar", "claude", "chatgpt",
            "acrobat", "pdf", "scanner", "wps"
        ),
        "Social" to listOf(
            "whatsapp", "telegram", "messenger", "instagram", "facebook", "snapchat", "tiktok",
            "twitter", "signal", "discord", "linkedin", "threads", "messag", "sms", "mms", "chat"
        ),
        "Media" to listOf(
            "youtube", "music", "spotify", "netflix", "video", "player", "gallery", "photo", "camera",
            "boomplay", "audiomack", "radio", "podcast", "showmax", "dstv"
        ),
        "Tools" to listOf(
            "settings", "calculator", "clock", "alarm", "files", "flashlight", "torch", "contacts",
            "phone", "dialer", "dialler", "browser", "chrome", "firefox", "opera", "internet", "maps",
            "weather", "recorder", "compass", "store", "vending", "security", "cleaner", "toolkit",
            "download", "manager", "keyboard", "vpn"
        )
    )

    // ---------------- Public API ----------------

    private var current: WeakReference<Dialog>? = null

    fun show(context: Context, startListening: Boolean = false) {
        var c: Context? = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        val activity = c as? Activity ?: return
        if (activity.isFinishing) return
        dismiss()
        val dialog = OrbitAssistantDialog(activity, startListening)
        current = WeakReference(dialog)
        dialog.show()
    }

    /** Closes the command bar if it is open. Returns true if it was open. */
    fun dismiss(): Boolean {
        val dialog = current?.get()
        current = null
        if (dialog != null && dialog.isShowing) {
            dialog.dismiss()
            return true
        }
        return false
    }

    internal fun closed(dialog: Dialog) {
        if (current?.get() === dialog) current = null
    }

    /** Last loaded app list (may be empty until loadApps has run once). */
    fun cachedApps(): List<AssistantApp> = cache

    /** Loads installed apps in the background. Calls back on the main thread (twice if a cache exists). */
    fun loadApps(context: Context, onReady: (List<AssistantApp>) -> Unit) {
        val appContext = context.applicationContext
        val cached = cache
        if (cached.isNotEmpty()) onReady(cached)
        worker.execute {
            val fresh = try { buildIndex(appContext) } catch (e: Exception) { cached }
            cache = fresh
            mainThread.post { onReady(fresh) }
        }
    }

    fun recordLaunch(context: Context, packageName: String) {
        val p = prefs(context)
        p.edit().putInt(packageName, p.getInt(packageName, 0) + 1).apply()
    }

    /** Groups in display order: "Most used" first, then Social, Media, Games, Work, Money, Tools, Other. */
    fun organise(context: Context, apps: List<AssistantApp>): List<Pair<String, List<AssistantApp>>> {
        val p = prefs(context)
        val sections = ArrayList<Pair<String, List<AssistantApp>>>()
        val mostUsed = apps
            .filter { p.getInt(it.packageName, 0) > 0 }
            .sortedByDescending { p.getInt(it.packageName, 0) }
            .distinctBy { it.packageName }
            .take(8)
        if (mostUsed.isNotEmpty()) sections.add(MOST_USED to mostUsed)
        for (group in GROUP_ORDER) {
            val inGroup = apps.filter { it.group == group } // already alphabetical
            if (inGroup.isNotEmpty()) sections.add(group to inGroup)
        }
        return sections
    }

    fun parse(raw: String, apps: List<AssistantApp>, context: Context): OrbitCommand {
        val p = prefs(context)
        return parse(raw, apps) { p.getInt(it, 0) }
    }

    internal fun parse(raw: String, apps: List<AssistantApp>, usage: (String) -> Int): OrbitCommand {
        val cleaned = clean(raw)
        if (cleaned.isEmpty()) return OrbitCommand.Empty

        CALL.matchEntire(cleaned)?.let { m ->
            val who = m.groupValues[1].replace(LEADING_ARTICLE, "").trim()
            val digits = who.replace(NUMBER_JUNK, "")
            return when {
                NUMBER.matches(digits) -> OrbitCommand.Dial(digits)
                normalise(who) in DIALLER_WORDS -> OrbitCommand.OpenDialler
                else -> OrbitCommand.CallContact(who)
            }
        }

        val target = targetOf(cleaned)
        val targetNorm = normalise(target)
        if (targetNorm in DIALLER_WORDS) return OrbitCommand.OpenDialler

        val top = rankCleaned(cleaned, apps, usage).firstOrNull()

        val digits = target.replace(NUMBER_JUNK, "")
        if (NUMBER.matches(digits) && digits.any { it.isDigit() } && (top == null || top.second < 1000)) {
            return OrbitCommand.Dial(digits)
        }
        GROUP_WORDS[targetNorm]?.let { group ->
            if (top == null || top.second < 1000) return OrbitCommand.ShowGroup(group)
        }
        return if (top != null) OrbitCommand.OpenApp(top.first) else OrbitCommand.NotFound(target)
    }

    fun suggestions(raw: String, apps: List<AssistantApp>, context: Context, limit: Int = 6): List<AssistantApp> {
        val p = prefs(context)
        return suggestions(raw, apps, limit) { p.getInt(it, 0) }
    }

    internal fun suggestions(raw: String, apps: List<AssistantApp>, limit: Int, usage: (String) -> Int): List<AssistantApp> =
        rankCleaned(clean(raw), apps, usage).take(limit).map { it.first }

    fun describe(cmd: OrbitCommand): String = when (cmd) {
        is OrbitCommand.OpenApp -> "Press Go to open ${cmd.app.label}"
        is OrbitCommand.Dial -> "Press Go to dial ${cmd.number}"
        is OrbitCommand.CallContact -> "Press Go to call ${titleCase(cmd.name)}"
        is OrbitCommand.ShowGroup -> "Press Go to show your ${cmd.group.lowercase(Locale.ROOT)} apps"
        is OrbitCommand.NotFound -> "No app matches \"${cmd.query}\""
        OrbitCommand.OpenDialler -> "Press Go to open the phone"
        OrbitCommand.Empty -> ""
    }

    fun launch(context: Context, app: AssistantApp): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, app.activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        return try {
            context.startActivity(intent)
            recordLaunch(context, app.packageName)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Returns (display name, number) of the best matching contact, or null. Needs READ_CONTACTS. */
    fun findContact(context: Context, name: String): Pair<String, String>? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val nameCol = ContactsContract.Contacts.DISPLAY_NAME
        val numberCol = ContactsContract.CommonDataKinds.Phone.NUMBER
        val cursor = try {
            context.contentResolver.query(uri, arrayOf(nameCol, numberCol), "$nameCol LIKE ?", arrayOf("%$name%"), null)
        } catch (e: Exception) {
            null
        } ?: return null
        cursor.use { c ->
            var best: Pair<String, String>? = null
            var bestRank = -1
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val rank = when {
                    n.equals(name, ignoreCase = true) -> 3
                    n.startsWith(name, ignoreCase = true) -> 2
                    else -> 1
                }
                if (rank > bestRank) {
                    best = n to num
                    bestRank = rank
                }
            }
            return best
        }
    }

    // ---------------- Matching ----------------

    fun normalise(s: String): String = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun splitWords(s: String): List<String> =
        s.replace(CAMEL, "\$1 \$2")
            .lowercase(Locale.ROOT)
            .split(NON_WORD)
            .filter { it.isNotEmpty() }

    private fun clean(raw: String): String {
        var t = raw.lowercase(Locale.ROOT)
            .replace(',', ' ')
            .replace(END_PUNCT, "")
            .replace(SPACES, " ")
            .trim()
        t = FILLER.replace(t, "").trim()
        repeat(2) { t = TRAILING.replace(t, "").trim() }
        return t
    }

    private fun targetOf(cleaned: String): String {
        val t = OPEN.matchEntire(cleaned)?.groupValues?.get(1) ?: cleaned
        return t.replace(LEADING_ARTICLE, "").trim()
    }

    private fun rankCleaned(cleaned: String, apps: List<AssistantApp>, usage: (String) -> Int): List<Pair<AssistantApp, Int>> {
        if (cleaned.isEmpty() || CALL.matches(cleaned)) return emptyList()
        val target = targetOf(cleaned)
        val forms = linkedSetOf(target)
        NICKNAMES[normalise(target)]?.let { forms.add(it) }
        val wholeNorm = normalise(cleaned) // so "play store" still finds Play Store
        return apps.asSequence()
            .map { app ->
                val s = if (app.norm == wholeNorm) 1000 else forms.maxOf { score(it, app) }
                app to s
            }
            .filter { it.second >= MIN_SCORE }
            .sortedWith(
                compareByDescending<Pair<AssistantApp, Int>> { it.second }
                    .thenByDescending { usage(it.first.packageName) }
                    .thenBy { it.first.label.length }
            )
            .toList()
    }

    internal fun score(query: String, app: AssistantApp): Int {
        val q = normalise(query)
        val label = app.norm
        if (q.isEmpty() || label.isEmpty()) return 0
        if (label == q) return 1000

        var best = 0
        fun hit(v: Int) { if (v > best) best = v }

        if (label.startsWith(q)) hit(900 - minOf(label.length - q.length, 50))
        if (q.length >= 2 && app.initials == q) hit(850)
        if (app.words.any { it.startsWith(q) }) hit(800)
        if (q.length >= 2 && app.initials.startsWith(q)) hit(700)
        if (q.length >= 3 && label.contains(q)) hit(650)

        val qWords = splitWords(query).filter { it !in GENERIC_WORDS }
        if (qWords.isNotEmpty()) {
            val hits = qWords.count { qw ->
                qw.length >= 2 && app.words.any { w ->
                    w.startsWith(qw) || (qw.length >= 4 && levenshtein(qw, w) <= 1)
                }
            }
            if (qWords.size > 1 || normalise(qWords[0]) != q) {
                if (hits == qWords.size) hit(750) else if (hits > 0) hit(minOf(450 + 50 * hits, 700))
            }
        }

        if (q.length >= 3 && q[0] == label[0] && isSubsequence(q, label)) hit(450)

        if (q.length >= 4) {
            val allowed = when {
                q.length <= 5 -> 1
                q.length <= 9 -> 2
                else -> 3
            }
            var d = minOf(levenshtein(q, label), levenshtein(q, label.take(q.length)))
            for (w in app.words) d = minOf(d, levenshtein(q, w))
            if (d <= allowed) hit(450 - 50 * d)
        }

        if (q.length >= 4 && app.packageName.substringAfterLast('.').lowercase(Locale.ROOT).contains(q)) hit(300)
        return best
    }

    private fun isSubsequence(q: String, s: String): Boolean {
        var i = 0
        for (c in s) if (i < q.length && q[i] == c) i++
        return i == q.length
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = curr
            curr = t
        }
        return prev[b.length]
    }

    private fun titleCase(s: String): String =
        s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }

    // ---------------- Indexing ----------------

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    private fun buildIndex(context: Context): List<AssistantApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(intent, 0)
        val out = ArrayList<AssistantApp>(infos.size)
        val seen = HashSet<String>()
        for (ri in infos) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == context.packageName) continue
            if (!seen.add(ai.packageName + "/" + ai.name)) continue
            val label = (ri.loadLabel(pm)?.toString() ?: "").trim().ifEmpty { ai.packageName }
            val icon = try { ri.loadIcon(pm) } catch (e: Exception) { null }
            out.add(AssistantApp(label, ai.packageName, ai.name, groupFor(ai.applicationInfo, label, ai.packageName), ic
