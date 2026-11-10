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
        val dia
