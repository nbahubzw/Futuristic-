package com.orbit.launcher

/*
 * Orbit Assistant, file 3 of 4. Offline command bar for the Orbit launcher.
 * All four files go in the same folder as MainActivity.kt:
 *   AssistantApp.kt, OrbitMatcher.kt, OrbitAssistant.kt, OrbitAssistantDialog.kt
 *
 *   OrbitAssistant.show(context)      open the command bar (swipe up on the home screen)
 *   OrbitAssistant.dismiss()          close it (returns true if it was open)
 *
 * Understands: open YouTube, yt, play Subway Surfers, whats app, open google maps,
 *   phone, call Mum, dial 0772 123 456, dial *151#, games, social, money, tools
 * Sorts apps into Social, Media, Games, Work, Money, Tools, Other, plus "Most used".
 */

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.Executors

object OrbitAssistant {

    const val MOST_USED = "Most used"
    val GROUP_ORDER = listOf("Social", "Media", "Games", "Work", "Money", "Tools", "Other")

    private const val PREFS = "orbit_assistant_usage"

    private val worker = Executors.newSingleThreadExecutor()
    private val mainThread by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var cache: List<AssistantApp> = emptyList()

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

    fun parse(raw: String, apps: List<AssistantApp>, context: Context): OrbitCommand =
        OrbitMatcher.parse(raw, apps, usageOf(context))

    /** Opening or calling (true) versus a question for Orbit's AI (false). */
    fun isCommand(raw: String, apps: List<AssistantApp>, context: Context): Boolean =
        OrbitMatcher.isCommand(raw, apps, usageOf(context))

    fun suggestions(raw: String, apps: List<AssistantApp>, context: Context, limit: Int = 6): List<AssistantApp> =
        OrbitMatcher.suggestions(raw, apps, limit, usageOf(context))

    fun describe(cmd: OrbitCommand): String = OrbitMatcher.describe(cmd)

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

    // ---------------- Indexing ----------------

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun usageOf(context: Context): (String) -> Int {
        val p = prefs(context)
        return { p.getInt(it, 0) }
    }

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
            out.add(AssistantApp(label, ai.packageName, ai.name, groupFor(ai.applicationInfo, label, ai.packageName), icon))
        }
        out.sortBy { it.label.lowercase(Locale.ROOT) }
        return out
    }

    @Suppress("DEPRECATION")
    internal fun groupFor(ai: ApplicationInfo?, label: String, pkg: String): String {
        if (ai != null) {
            if (ai.flags and ApplicationInfo.FLAG_IS_GAME != 0) return "Games"
            if (Build.VERSION.SDK_INT >= 26) {
                when (ai.category) {
                    ApplicationInfo.CATEGORY_GAME -> return "Games"
                    ApplicationInfo.CATEGORY_SOCIAL -> return "Social"
                    ApplicationInfo.CATEGORY_AUDIO,
                    ApplicationInfo.CATEGORY_VIDEO,
                    ApplicationInfo.CATEGORY_IMAGE,
                    ApplicationInfo.CATEGORY_NEWS -> return "Media"
                    ApplicationInfo.CATEGORY_PRODUCTIVITY -> return "Work"
                    ApplicationInfo.CATEGORY_MAPS -> return "Tools"
                }
            }
        }
        val text = "$label $pkg".lowercase(Locale.ROOT)
        for ((group, keys) in GROUP_KEYWORDS) {
            if (keys.any { text.contains(it) }) return group
        }
        return "Other"
    }
}
