package com.orbit.launcher

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.text.format.DateFormat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// Orbit hero, reminders: saving, alarms, notifications (with Done and Snooze buttons).
// When a task is due and the phone is locked or the screen is off, a real alarm rings full screen.
// When you are using the phone, it shows as a notification instead.

data class Reminder(
    val id: Long,
    val text: String,
    val time: Long?, // epoch millis; null = a note with no alarm
    val repeat: ReminderRepeat,
    val done: Boolean,
    val doneAt: Long?,
    val created: Long
) {
    fun local(): LocalDateTime? = time?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
}

fun LocalDateTime.toMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** The user's name and voice settings. */
object OrbitProfile {
    private const val PREFS = "orbit_profile"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun name(context: Context): String = prefs(context).getString("name", "") ?: ""
    fun setName(context: Context, name: String) = prefs(context).edit().putString("name", name.trim()).apply()
    fun voiceOn(context: Context): Boolean = prefs(context).getBoolean("voice", true)
    fun setVoiceOn(context: Context, on: Boolean) = prefs(context).edit().putBoolean("voice", on).apply()
    fun lastGreeting(context: Context): String = prefs(context).getString("last_greeting", "") ?: ""
    fun setLastGreeting(context: Context, key: String) = prefs(context).edit().putString("last_greeting", key).apply()

    /** Speech recognition language. South African English is the closest Google model to Zimbabwean English. */
    fun speechLocale(context: Context): String = prefs(context).getString("speech_locale", "en-ZA") ?: "en-ZA"
    fun setSpeechLocale(context: Context, tag: String) = prefs(context).edit().putString("speech_locale", tag).apply()
}

object ReminderStore {
    private const val PREFS = "orbit_reminders"
    private const val KEY = "items"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(context: Context): List<Reminder> {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        val list = ArrayList<Reminder>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Reminder(
                        id = o.getLong("id"),
                        text = o.optString("text", "Reminder"),
                        time = if (o.has("time")) o.getLong("time") else null,
                        repeat = try { ReminderRepeat.valueOf(o.optString("repeat", "NONE")) } catch (e: Exception) { ReminderRepeat.NONE },
                        done = o.optBoolean("done", false),
                        doneAt = if (o.has("doneAt")) o.getLong("doneAt") else null,
                        created = o.optLong("created", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            return emptyList()
        }
        // Tidy up: ticked items disappear the next day, missed one-offs after a week
        val startOfToday = LocalDate.now().atStartOfDay().toMillis()
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        val kept = list.filterNot { r ->
            (r.done && (r.doneAt ?: 0L) < startOfToday) ||
                (!r.done && r.repeat == ReminderRepeat.NONE && r.time != null && r.time < weekAgo)
        }
        if (kept.size != list.size) save(context, kept)
        return kept
    }

    @Synchronized
    private fun save(context: Context, list: List<Reminder>) {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("id", r.id)
            o.put("text", r.text)
            r.time?.let { o.put("time", it) }
            o.put("repeat", r.repeat.name)
            o.put("done", r.done)
            r.doneAt?.let { o.put("doneAt", it) }
            o.put("created", r.created)
            arr.put(o)
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun find(context: Context, id: Long): Reminder? = all(context).firstOrNull { it.id == id }

    @Synchronized
    fun add(context: Context, parsed: ParsedReminder): Reminder {
        val p = prefs(context)
        val id = p.getLong("next_id", 1L)
        p.edit().putLong("next_id", id + 1).apply()
        val r = Reminder(id, parsed.text, parsed.time?.toMillis(), parsed.repeat, false, null, System.currentTimeMillis())
        save(context, all(context) + r)
        ReminderAlarms.schedule(context, r)
        return r
    }

    @Synchronized
    fun update(context: Context, id: Long, change: (Reminder) -> Reminder?): Reminder? {
        var result: Reminder? = null
        val list = all(context).mapNotNull { r ->
            if (r.id == id) change(r).also { result = it } else r
        }
        save(context, list)
        return result
    }

    fun setDone(context: Context, id: Long, done: Boolean) {
        val r = update(context, id) { it.copy(done = done, doneAt = if (done) System.currentTimeMillis() else null) }
        if (done) {
            ReminderAlarms.cancel(context, id)
            ReminderNotifier.cancel(context, id)
        } else if (r != null) {
            ReminderAlarms.schedule(context, r)
        }
    }

    /** Changes a task from the edit sheet and moves its alarm. */
    fun edit(context: Context, id: Long, text: String, time: LocalDateTime?, repeat: ReminderRepeat): Reminder? {
        ReminderAlarms.cancel(context, id)
        ReminderNotifier.cancel(context, id)
        val r = update(context, id) {
            it.copy(text = text, time = time?.toMillis(), repeat = if (time == null) ReminderRepeat.NONE else repeat)
        }
        if (r != null && !r.done) ReminderAlarms.schedule(context, r)
        return r
    }

    /** Rings again in a few minutes. A repeating task gets a one-off copy so its schedule stays the same. */
    fun snooze(context: Context, id: Long, minutes: Long = 10) {
        val r = find(context, id) ?: return
        ReminderNotifier.cancel(context, id)
        val at = LocalDateTime.now().plusMinutes(minutes).withSecond(0).withNano(0)
        if (r.repeat == ReminderRepeat.NONE) {
            update(context, id) { it.copy(time = at.toMillis(), done = false, doneAt = null) }?.let { ReminderAlarms.schedule(context, it) }
        } else {
            add(context, ParsedReminder(r.text, at, ReminderRepeat.NONE))
        }
    }

    fun delete(context: Context, id: Long) {
        update(context, id) { null }
        ReminderAlarms.cancel(context, id)
        ReminderNotifier.cancel(context, id)
    }

    fun clearDone(context: Context): Int {
        val list = all(context)
        val left = list.filterNot { it.done }
        save(context, left)
        return list.size - left.size
    }

    /** For repeating reminders: move to the next day or week after it rings. */
    fun advance(context: Context, id: Long): Reminder? = update(context, id) { r ->
        val next = r.local()?.let { OrbitBrain.nextRepeat(it, r.repeat, LocalDateTime.now()) }
        if (next != null) r.copy(time = next.toMillis()) else r
    }

    /** Leaflet order: open items by time (notes last), then today's ticked items. */
    fun leaflet(context: Context): List<Reminder> {
        val items = all(context)
        val open = items.filter { !it.done }.sortedWith(compareBy<Reminder> { it.time == null }.thenBy { it.time ?: Long.MAX_VALUE })
        val done = items.filter { it.done }.sortedByDescending { it.doneAt ?: 0L }
        return open + done
    }
}

object ReminderAlarms {
    fun schedule(context: Context, r: Reminder) {
        val at = r.time ?: return
        if (r.done || at <= System.currentTimeMillis()) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pending(context, r.id)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                // Alarm-clock alarms are exact, wake the phone from deep sleep and show the alarm icon
                val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
                val show = open?.let {
                    PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                }
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pending(context, id))
    }

    fun rescheduleAll(context: Context) {
        val now = LocalDateTime.now()
        for (r in ReminderStore.all(context)) {
            if (r.done || r.time == null) continue
            var item = r
            if (r.repeat != ReminderRepeat.NONE && r.time <= System.currentTimeMillis()) {
                item = ReminderStore.update(context, r.id) {
                    val next = it.local()?.let { t -> OrbitBrain.nextRepeat(t, it.repeat, now) }
                    if (next != null) it.copy(time = next.toMillis()) else it
                } ?: r
            }
            schedule(context, item)
        }
    }

    private fun pending(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .setData(Uri.parse("orbit://reminder/$id"))
            .putExtra(ReminderReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

object ReminderNotifier {
    private const val CHANNEL = "orbit_reminders"
    private const val ALARM_CHANNEL = "orbit_task_alarms"

    private fun ensureAlarmChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(ALARM_CHANNEL) == null) {
            val ch = NotificationChannel(ALARM_CHANNEL, "Orbit task alarms", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Full-screen alarm when a task is due and the phone is locked"
            ch.setSound(null, null) // the alarm screen plays the ringtone itself
            ch.enableVibration(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
    }

    /** True when Android lets Orbit open the alarm screen over the lock screen. */
    fun canRing(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.canUseFullScreenIntent()
    }

    /** Rings: opens the full-screen alarm (Android shows it when the phone is locked or asleep). */
    fun showAlarm(context: Context, r: Reminder, name: String) {
        ensureAlarmChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val screen = PendingIntent.getActivity(
            context, r.id.toInt() * 4 + 3, TaskAlarmActivity.intent(context, r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (name.isBlank()) "Task due" else "$name, it's time"
        val builder = Notification.Builder(context, ALARM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(r.text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_lock_idle_alarm), "Snooze",
                    actionIntent(context, r.id, ReminderReceiver.ACTION_SNOOZE, r.id.toInt() * 4 + 2)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.checkbox_on_background), "Stop",
                    actionIntent(context, r.id, ReminderReceiver.ACTION_DONE, r.id.toInt() * 4 + 1)
                ).build()
            )
        try {
            nm.notify(notificationId(r.id), builder.build())
        } catch (e: SecurityException) {
            show(context, r, name)
        }
    }

    private fun actionIntent(context: Context, id: Long, act: String, code: Int): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java)
            .setAction(act)
            .setData(Uri.parse("orbit://reminder/$id/$act"))
            .putExtra(ReminderReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Orbit reminders", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Reminders you set with the Orbit assistant"
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)
        }
    }

    fun show(context: Context, r: Reminder, name: String) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentPi = open?.let {
            PendingIntent.getActivity(context, r.id.toInt(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        fun action(act: String, code: Int): PendingIntent {
            val i = Intent(context, ReminderReceiver::class.java)
                .setAction(act)
                .setData(Uri.parse("orbit://reminder/${r.id}/$act"))
                .putExtra(ReminderReceiver.EXTRA_ID, r.id)
            return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val title = if (name.isBlank()) "Reminder" else "$name, it's time"
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(r.text)
            .setStyle(Notification.BigTextStyle().bigText(r.text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setWhen(r.time ?: System.currentTimeMillis())
            .setShowWhen(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.checkbox_on_background), "Done",
                    action(ReminderReceiver.ACTION_DONE, r.id.toInt() * 4 + 1)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_lock_idle_alarm), "Snooze 10 min",
                    action(ReminderReceiver.ACTION_SNOOZE, r.id.toInt() * 4 + 2)
                ).build()
            )
        contentPi?.let { builder.setContentIntent(it) }
        try {
            nm.notify(notificationId(r.id), builder.build())
        } catch (e: SecurityException) {
            // Notifications not allowed; the reminder still shows on the leaflet
        }
    }

    fun cancel(context: Context, id: Long) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(id))
    }

    private fun notificationId(id: Long) = 70000 + id.toInt()
}

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "com.orbit.launcher.REMINDER_FIRE"
        const val ACTION_DONE = "com.orbit.launcher.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.orbit.launcher.REMINDER_SNOOZE"
        const val EXTRA_ID = "id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        when (intent.action) {
            ACTION_FIRE -> {
                val r = ReminderStore.find(context, id) ?: return
                if (r.done) return
                val power = context.getSystemService(PowerManager::class.java)
                val keyguard = context.getSystemService(KeyguardManager::class.java)
                val away = power?.isInteractive == false || keyguard?.isKeyguardLocked == true
                if (away && ReminderNotifier.canRing(context)) {
                    ReminderNotifier.showAlarm(context, r, OrbitProfile.name(context))
                } else {
                    ReminderNotifier.show(context, r, OrbitProfile.name(context))
                }
                if (r.repeat != ReminderRepeat.NONE) {
                    ReminderStore.advance(context, id)?.let { ReminderAlarms.schedule(context, it) }
                }
            }
            ACTION_DONE -> {
                val r = ReminderStore.find(context, id)
                if (r != null && r.repeat == ReminderRepeat.NONE) ReminderStore.setDone(context, id, true)
                ReminderNotifier.cancel(context, id)
                TaskAlarmActivity.close(id)
            }
            ACTION_SNOOZE -> {
                ReminderStore.snooze(context, id)
                TaskAlarmActivity.close(id)
            }
            else -> {
                ReminderAlarms.rescheduleAll(context) // boot, app update, clock or time zone change
                // After a restart or update, never leave the buttons hidden without Orbit's gestures
                if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) OrbitNav.safetyCheck(context)
            }
        }
    }
}

/** How times are written and spoken. */
object ReminderText {
    fun clock(context: Context, t: LocalDateTime): String =
        if (DateFormat.is24HourFormat(context)) t.format(DateTimeFormatter.ofPattern("HH:mm"))
        else t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.UK)).lowercase(Locale.UK)

    /** Short label for the leaflet: "17:00", "Tmrw 08:00", "Fri 09:00", "25 Dec". */
    fun label(context: Context, t: LocalDateTime, now: LocalDateTime): String {
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        return when {
            days == 0L -> clock(context, t)
            days == 1L -> "Tmrw " + clock(context, t)
            days in 2..6 -> t.format(DateTimeFormatter.ofPattern("EEE", Locale.UK)) + " " + clock(context, t)
            else -> t.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))
        }
    }

    /** For speech: "5 pm", "8:30 am". */
    fun spokenClock(t: LocalDateTime): String =
        (if (t.minute == 0) t.format(DateTimeFormatter.ofPattern("h a", Locale.UK))
        else t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.UK))).lowercase(Locale.UK)

    /** For speech: "at 5 pm", "tomorrow at 8:30 am", "on Friday at 9 am", "on 25 December at 9 am". */
    fun spoken(t: LocalDateTime, now: LocalDateTime): String {
        val at = "at " + spokenClock(t)
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        return when {
            days == 0L -> at
            days == 1L -> "tomorrow $at"
            days in 2..6 -> "on " + t.format(DateTimeFormatter.ofPattern("EEEE", Locale.UK)) + " " + at
            else -> "on " + t.format(DateTimeFormatter.ofPattern("d MMMM", Locale.UK)) + " " + at
        }
    }
}
