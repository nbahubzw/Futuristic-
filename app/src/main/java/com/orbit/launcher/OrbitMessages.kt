package com.orbit.launcher

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

// Orbit and your messages: who messaged you, what they said, opening the right chat and replying.
// Everything is read from your notifications on the phone itself. Message text is never sent to the AI.

object OrbitMessages {

    class Msg(val sender: String, val text: String, val time: Long)

    class Chat(
        val pkg: String,
        val app: String,
        val title: String,
        val group: Boolean,
        val missedCall: Boolean,
        val messages: List<Msg>,
        val open: PendingIntent?,
        val reply: Notification.Action?,
        val key: String
    ) {
        val time: Long get() = messages.maxOfOrNull { it.time } ?: 0L
        val last: Msg get() = messages.maxByOrNull { it.time } ?: messages.last()
    }

    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    private val MESSAGING_APPS = setOf(
        WHATSAPP, WHATSAPP_BUSINESS, "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging",
        "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca", "com.facebook.mlite", "com.instagram.android",
        "com.viber.voip", "com.imo.android.imoim", "com.microsoft.teams", "com.Slack", "com.discord", "com.snapchat.android",
        "com.google.android.apps.googlevoice", "com.truecaller"
    )

    private val COUNT_SUFFIX = Regex("\\s*\\(\\d+ (?:messages?|new messages?)\\)\\s*$", RegexOption.IGNORE_CASE)

    // ---------------- Access ----------------

    fun hasAccess(c: Context): Boolean {
        val enabled = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(c.packageName)
    }

    /** Access can be on while the connection to notifications is still starting up. */
    fun connected(): Boolean = NotificationService.instance != null

    fun reconnect(c: Context) {
        try {
            NotificationListenerService.requestRebind(ComponentName(c, NotificationService::class.java))
        } catch (e: Exception) {
        }
    }

    fun openAccessSettings(c: Context) {
        try {
            c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }

    // ---------------- Reading ----------------

    /** Your unread conversations and missed calls, newest first. */
    fun chats(c: Context): List<Chat> {
        val svc = NotificationService.instance ?: return emptyList()
        val active: Array<StatusBarNotification> = try {
            svc.activeNotifications ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        val byChat = LinkedHashMap<String, Chat>()
        for (sbn in active) {
            if (sbn.packageName == c.packageName) continue
            val n = sbn.notification ?: continue
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            if (sbn.isOngoing) continue
            val extras = n.extras ?: continue
            val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
            val missed = n.category == "missed_call"
            val isMessage = n.category == Notification.CATEGORY_MESSAGE || template.endsWith("MessagingStyle") ||
                sbn.packageName in MESSAGING_APPS || missed
            if (!isMessage) continue
            val chat = toChat(c, sbn, n, extras, missed) ?: continue
            val id = chat.pkg + "|" + chat.title.lowercase(Locale.ROOT)
            val existing = byChat[id]
            if (existing == null || chat.time > existing.time) byChat[id] = chat
        }
        return byChat.values.sortedByDescending { it.time }
    }

    private fun toChat(c: Context, sbn: StatusBarNotification, n: Notification, extras: Bundle, missed: Boolean): Chat? {
        val rawTitle = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val title = rawTitle.replace(COUNT_SUFFIX, "").trim()
        val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        val messages = ArrayList<Msg>()
        @Suppress("DEPRECATION")
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (bundles != null) {
            for (p in bundles) {
                val b = p as? Bundle ?: continue
                val text = b.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                @Suppress("DEPRECATION")
                val person = b.getParcelable<Person>("sender_person")
                val sender = (b.getCharSequence("sender") ?: person?.name)?.toString()?.trim()
                if (sender.isNullOrEmpty()) continue // your own message in the thread
                messages.add(Msg(sender, text, b.getLong("time", sbn.postTime)))
            }
        }
        if (messages.isEmpty()) {
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim().orEmpty()
            if (title.isEmpty() && text.isEmpty()) return null
            // Older style: "Group: Sender" in the title
            val sender = if (!group && title.contains(": ")) title.substringAfter(": ") else title
            messages.add(Msg(sender.ifEmpty { appName(c, sbn.packageName) }, text, sbn.postTime))
        }
        val reply = n.actions?.firstOrNull { a ->
            !a.remoteInputs.isNullOrEmpty() && (Build.VERSION.SDK_INT < 28 || a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY ||
                a.title?.toString()?.contains("reply", ignoreCase = true) == true)
        } ?: n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        return Chat(
            pkg = sbn.packageName,
            app = appName(c, sbn.packageName),
            title = title.ifEmpty { messages.last().sender },
            group = group || (title.contains(": ") && messages.size > 1),
            missedCall = missed,
            messages = messages.takeLast(6),
            open = n.contentIntent,
            reply = reply,
            key = sbn.key
        )
    }

    fun appName(c: Context, pkg: String): String = when (pkg) {
        WHATSAPP -> "WhatsApp"
        WHATSAPP_BUSINESS -> "WhatsApp Business"
        "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.messaging" -> "Messages"
        else -> try {
            c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            "a message"
        }
    }

    /** Finds a chat by the name you said ("Mum", "Tendai", "the family group"). */
    fun find(chats: List<Chat>, spoken: String): Chat? {
        val q = norm(spoken.replace(Regex("^(?:the|my)\\s+", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+(?:group|chat)$", RegexOption.IGNORE_CASE), ""))
        if (q.isEmpty()) return null
        var best: Chat? = null
        var bestScore = 0
        for (ch in chats) {
            val names = listOf(ch.title) + ch.messages.map { it.sender }
            for (n in names) {
                val s = nameScore(q, n)
                if (s > bestScore || (s == bestScore && best != null && ch.time > best.time)) {
                    best = ch
                    bestScore = s
                }
            }
        }
        return if (bestScore >= 50) best else null
    }

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun nameScore(q: String, name: String): Int {
        val n = norm(name)
        if (n.isEmpty()) return 0
        if (n == q) return 100
        val words = n.split(' ')
        if (words.any { it == q }) return 85
        if (n.startsWith(q)) return 80
        if (q.length >= 3 && n.contains(q)) return 60
        if (q.length >= 4 && words.any { levenshtein(it, q) <= 1 }) return 55
        return 0
    }

    private fun levenshtein(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }

    // ---------------- What Orbit says ----------------

    private fun quote(text: String): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        return "“" + (if (t.length > 160) t.take(157).trimEnd() + "…" else t) + "”"
    }

    private fun chatLine(ch: Chat): String {
        if (ch.missedCall) return "a missed call from ${ch.title}"
        val m = ch.last
        val count = ch.messages.size
        val via = if (ch.app == "a message") "" else " on ${ch.app}"
        return if (ch.group) {
            "${ch.title}$via, ${m.sender} said ${quote(m.text)}"
        } else {
            val many = if (count > 1) "$count messages from " else ""
            "$many${ch.title}$via: ${quote(m.text)}"
        }
    }

    /** "You have 4 new messages. Mum on WhatsApp: “…”. …" */
    fun summary(chats: List<Chat>, who: String): String {
        val calls = chats.filter { it.missedCall }
        val convos = chats.filter { !it.missedCall }
        if (chats.isEmpty()) return "No new messages$who."
        val sb = StringBuilder()
        val total = convos.sumOf { it.messages.size }
        if (convos.isNotEmpty()) {
            sb.append(if (convos.size == 1) "You have " else "You have messages in ${convos.size} chats. ")
            if (convos.size == 1) sb.append(if (total == 1) "one new message. " else "$total new messages. ")
            for (ch in convos.take(4)) sb.append(chatLine(ch).replaceFirstChar { it.uppercase() }).append(". ")
            if (convos.size > 4) sb.append("And ${convos.size - 4} more. ")
        }
        if (calls.isNotEmpty()) {
            sb.append(if (calls.size == 1) "You also have a missed call from ${calls[0].title}. " else "Missed calls from ${calls.take(3).joinToString(", ") { it.title }}. ")
        }
        return sb.toString().replace(". .", ".").trim()
    }

    fun readChat(ch: Chat): String {
        if (ch.missedCall) return "You have a missed call from ${ch.title}."
        val msgs = ch.messages.takeLast(3)
        val via = if (ch.app == "a message") "" else " on ${ch.app}"
        return if (ch.group) {
            "In ${ch.title}$via: " + msgs.joinToString(" ") { "${it.sender} said ${quote(it.text)}." }
        } else if (msgs.size == 1) {
            "${ch.title} said$via: ${quote(msgs[0].text)}"
        } else {
            "${ch.title}$via said: " + msgs.joinToString(" Then, ") { quote(it.text) }
        }
    }

    // ---------------- Acting ----------------

    /** Opens the exact conversation, as if you tapped the notification. */
    fun open(c: Context, ch: Chat): Boolean {
        val pending = ch.open ?: return launchApp(c, ch.pkg)
        return try {
            val options = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            pending.send(c, 0, null, null, null, null, options)
            true
        } catch (e: Exception) {
            launchApp(c, ch.pkg)
        }
    }

    private fun launchApp(c: Context, pkg: String): Boolean = try {
        val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    /** Sends a reply straight from the notification, like a smartwatch does. */
    fun reply(c: Context, ch: Chat, text: String): Boolean {
        val action = ch.reply ?: return false
        val inputs = action.remoteInputs ?: return false
        return try {
            val intent = Intent()
            val results = Bundle()
            for (r in inputs) results.putCharSequence(r.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(c, 0, intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun installed(c: Context, pkg: String) = try {
        c.packageManager.getLaunchIntentForPackage(pkg) != null
    } catch (e: Exception) {
        false
    }

    fun whatsappPackage(c: Context): String? = when {
        installed(c, WHATSAPP) -> WHATSAPP
        installed(c, WHATSAPP_BUSINESS) -> WHATSAPP_BUSINESS
        else -> null
    }

    /** "0772 123 456" -> "263772123456" using this phone's country. */
    fun international(c: Context, number: String): String? {
        val trimmed = number.trim()
        val iso = try {
            val tm = c.getSystemService(TelephonyManager::class.java)
            (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country)
                .uppercase(Locale.ROOT)
        } catch (e: Exception) {
            Locale.getDefault().country.uppercase(Locale.ROOT)
        }
        val e164 = try {
            PhoneNumberUtils.formatNumberToE164(trimmed, iso)
        } catch (e: Exception) {
            null
        }
        val digits = (e164 ?: trimmed).filter { it.isDigit() || it == '+' }
        return when {
            digits.startsWith("+") -> digits.drop(1)
            digits.startsWith("00") -> digits.drop(2)
            e164 != null -> digits
            else -> null
        }
    }

    /** Opens a WhatsApp chat with this number, with the message typed in, ready to send. */
    fun whatsappTo(c: Context, number: String, text: String?): Boolean {
        val pkg = whatsappPackage(c) ?: return false
        val intl = international(c, number) ?: return false
        val uri = "https://api.whatsapp.com/send?phone=$intl" + if (!text.isNullOrBlank()) "&text=" + Uri.encode(text) else ""
        return try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Opens a text message to this number, with the message typed in. */
    fun smsTo(c: Context, number: String, text: String?): Boolean = try {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!text.isNullOrBlank()) i.putExtra("sms_body", text)
        c.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }
}
