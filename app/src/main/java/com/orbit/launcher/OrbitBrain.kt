package com.orbit.launcher

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

// Orbit hero, brain: understands what you say to the avatar. Plain Kotlin, works offline.

enum class ReminderRepeat { NONE, DAILY, WEEKLY }

/** A reminder understood from speech or typing. time == null means a note with no alarm. */
data class ParsedReminder(val text: String, val time: LocalDateTime?, val repeat: ReminderRepeat)

sealed class HeroIntent {
    data class SetName(val name: String) : HeroIntent()
    data class AddReminder(val reminder: ParsedReminder) : HeroIntent()
    object ListReminders : HeroIntent()
    object ClearDone : HeroIntent()
    object TellTime : HeroIntent()
    object TellDate : HeroIntent()
    object Hello : HeroIntent()
    object Thanks : HeroIntent()
    object Help : HeroIntent()
    data class Voice(val on: Boolean) : HeroIntent()
    data class Other(val raw: String) : HeroIntent()
    object Empty : HeroIntent()
}

object OrbitBrain {

    private val IC = RegexOption.IGNORE_CASE

    // ---------------- Greetings ----------------

    fun partOfDay(hour: Int): String = when (hour) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        else -> "evening"
    }

    fun greeting(hour: Int, name: String): String {
        val base = "Good ${partOfDay(hour)}"
        return if (name.isBlank()) base else "$base, $name"
    }

    // ---------------- Understanding commands ----------------

    private val LEAD = Regex("^(?:(?:hey|hi|ok|okay)\\s+)?(?:orbit\\b[\\s,]*)?(?:(?:please|can you|could you|would you)\\s+)*", IC)
    private val NAME = Regex("^(?:my name is|my name's|call me|you can call me)\\s+(.+)$", IC)
    private val REMIND = Regex("^(?:remind me|set (?:a |an |me a )?reminder|add (?:a )?reminder|create (?:a )?reminder|new reminder|make a reminder|reminder|don'?t let me forget)\\b", IC)
    private val TIME_Q = Regex("\\bwhat'?s the time\\b|\\bwhat time is it\\b|^(?:the )?time(?: now)?$|\\bcurrent time\\b|\\btell me the time\\b", IC)
    private val DATE_Q = Regex("\\bwhat'?s the date\\b|\\bwhat is the date\\b|\\bwhat date\\b|\\bwhat day is (?:it|today)\\b|\\btoday'?s date\\b|^(?:the )?date$", IC)
    private val LIST_Q = Regex(
        "\\b(?:schedule|reminders?|agenda|leaflet|to-?do list)\\b|^what do i have\\b|\\bwhat'?s (?:on )?(?:for )?today\\b|\\bwhat'?s next\\b|\\bmy plans?\\b",
        IC
    )
    private val CLEAR_Q = Regex("^(?:clear|delete|remove)\\b.*\\b(?:done|completed|finished|ticked)\\b", IC)
    private val MUTE = Regex("^(?:be quiet|quiet|mute|shut up|stop talking|silent(?: mode)?|voice off|don'?t (?:talk|speak))\\b", IC)
    private val UNMUTE = Regex("^(?:unmute|voice on|talk to me|speak(?: up)?|sound on)\\b", IC)
    private val HELLO = Regex("^(?:hello|hi|hey|yo|howdy|good (?:morning|afternoon|evening|day))(?: there)?(?: orbit)?$", IC)
    private val THANKS = Regex("^(?:thanks|thank you|cheers|nice one|great|awesome|perfect)\\b", IC)
    private val HELP = Regex("^(?:help|what can you do|what do you do|commands)\\b", IC)

    fun interpret(raw: String, now: LocalDateTime): HeroIntent {
        val text = raw.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?')
        if (text.isEmpty()) return HeroIntent.Empty
        val s = LEAD.replace(text, "").trim()
        if (s.isEmpty()) return HeroIntent.Hello

        NAME.matchEntire(s)?.let { m ->
            val name = cleanName(m.groupValues[1])
            if (name.isNotEmpty()) return HeroIntent.SetName(name)
        }
        if (REMIND.containsMatchIn(s)) return HeroIntent.AddReminder(parseReminder(s, now))
        if (TIME_Q.containsMatchIn(s)) return HeroIntent.TellTime
        if (DATE_Q.containsMatchIn(s)) return HeroIntent.TellDate
        if (CLEAR_Q.containsMatchIn(s)) return HeroIntent.ClearDone
        if (LIST_Q.containsMatchIn(s)) return HeroIntent.ListReminders
        if (MUTE.containsMatchIn(s)) return HeroIntent.Voice(false)
        if (UNMUTE.containsMatchIn(s)) return HeroIntent.Voice(true)
        if (HELLO.matches(s)) return HeroIntent.Hello
        if (THANKS.containsMatchIn(s)) return HeroIntent.Thanks
        if (HELP.containsMatchIn(s)) return HeroIntent.Help
        return HeroIntent.Other(s)
    }

    /** "lorac  mapiravana please" -> "Lorac Mapiravana". Keeps at most three words. */
    fun cleanName(raw: String): String =
        raw.replace(Regex("\\b(?:please|thanks|thank you)\\b", IC), " ")
            .replace(Regex("[^\\p{L}\\s'-]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(3)
            .joinToString(" ") { w -> w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) } }

    // ---------------- Understanding reminders ----------------

    private const val MONTHS =
        "january|february|march|april|may|june|july|august|september|october|november|december|" +
            "jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec"
    private const val DAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
    private const val SHORT_DAYS = "mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun"
    private const val NUM_WORDS = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
    private const val AMPM = "(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?)(?![a-z])"

    private val PREFIX = Regex(
        "^(?:(?:hey|hi|ok|okay)\\s+)?(?:orbit\\b[\\s,]*)?(?:(?:please|can you|could you|would you)\\s+)*" +
            "(?:remind me|set (?:a |an |me a )?reminder|add (?:a )?reminder|create (?:a )?reminder|new reminder|" +
            "make a reminder|reminder|don'?t let me forget)(?:\\s+(?:to|about|that|for|of))?(?=\\s|$)",
        IC
    )
    private val REPEAT_DAILY = Regex("\\b(?:every\\s*day|daily|each day|every (morning|afternoon|evening|night))\\b", IC)
    private val REPEAT_WEEKLY = Regex("\\bevery\\s+($DAYS)s?\\b", IC)
    private val RELATIVE = Regex(
        "\\b(?:in|after)\\s+(an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|" +
            "forty[- ]five|forty|fifty|half an?|a couple of|a few|\\d+(?:\\.\\d+)?)\\s*" +
            "(minutes?|mins?|hours?|hrs?|days?|weeks?)\\b" +
            "(?:\\s+(?:and\\s+)?(?:a\\s+)?(half|\\d+\\s*(?:minutes?|mins?)))?",
        IC
    )
    private val DATE_DAY_MONTH = Regex("\\b(?:on\\s+)?(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+of)?\\s+($MONTHS)\\b(?:\\s+(\\d{4}))?", IC)
    private val DATE_MONTH_DAY = Regex("\\b(?:on\\s+)?($MONTHS)\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?:,?\\s+(\\d{4}))?", IC)
    private val DATE_SLASH = Regex("\\b(?:on\\s+)?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b", IC)
    private val DATE_ORDINAL = Regex("\\b(?:on\\s+)?the\\s+(\\d{1,2})(?:st|nd|rd|th)\\b", IC)
    private val DAY_AFTER = Regex("\\b(?:the\\s+)?day after tomorrow\\b", IC)
    private val TOMORROW = Regex("\\btomorrow(?:\\s+(morning|afternoon|evening|night))?\\b", IC)
    private val TODAY = Regex("\\btoday\\b", IC)
    private val TONIGHT = Regex("\\btonight\\b", IC)
    private val THIS_POD = Regex("\\bthis\\s+(morning|afternoon|evening)\\b", IC)
    private val WEEKDAY = Regex("\\b(?:on\\s+)?(next\\s+|this\\s+|coming\\s+)?($DAYS)\\b(?:\\s+(morning|afternoon|evening|night))?", IC)
    private val WEEKDAY_SHORT = Regex("\\bon\\s+(next\\s+)?($SHORT_DAYS)\\b", IC)
    private val T_HALF = Regex("\\b(?:at\\s+)?(half past|quarter past|quarter to)\\s+(\\d{1,2}|$NUM_WORDS)\\b(?:\\s*$AMPM)?", IC)
    private val T_COLON = Regex("\\b(?:(?:at|by)\\s+|@\\s*)?(\\d{1,2}):(\\d{2})(?:\\s*$AMPM)?", IC)
    private val T_DOT = Regex("\\b(?:at|by)\\s+(\\d{1,2})\\.(\\d{2})(?:\\s*$AMPM)?", IC)
    private val T_AMPM = Regex("\\b(?:(?:at|by)\\s+|@\\s*)?(\\d{1,2})\\s*$AMPM", IC)
    private val T_OCLOCK = Regex("\\b(?:(?:at|by)\\s+)?(\\d{1,2}|$NUM_WORDS)\\s*o'?\\s?clock\\b", IC)
    private val T_MILITARY = Regex("\\b(?:at|by)\\s+([01]\\d|2[0-3])([0-5]\\d)\\b", IC)
    private val T_AT = Regex("\\b(?:at|by|@)\\s*(\\d{1,2}|$NUM_WORDS)\\b(?!\\s*(?:minutes?|mins?|hours?|hrs?|days?|%|/|:))", IC)
    private val T_WORD = Regex("\\b(?:at\\s+)?(noon|midday|midnight)\\b", IC)
    private val POD = Regex("\\b(?:in the|at|this)\\s+(morning|afternoon|evening|night)\\b", IC)

    private val NUMBERS = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0,
        "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0,
        "twelve" to 12.0, "fifteen" to 15.0, "twenty" to 20.0, "thirty" to 30.0, "forty" to 40.0,
        "forty five" to 45.0, "forty-five" to 45.0, "fifty" to 50.0, "half an" to 0.5, "half a" to 0.5,
        "a couple of" to 2.0, "a few" to 3.0
    )

    private fun podDefault(pod: String): LocalTime = when (pod.lowercase(Locale.ROOT)) {
        "morning" -> LocalTime.of(8, 0)
        "afternoon" -> LocalTime.of(14, 0)
        "evening" -> LocalTime.of(18, 0)
        else -> LocalTime.of(20, 0) // night
    }

    private fun num(s: String): Int? = s.toIntOrNull() ?: NUMBERS[s.lowercase(Locale.ROOT)]?.toInt()

    private fun month(s: String): Int {
        val k = s.lowercase(Locale.ROOT).take(3)
        return listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(k) + 1
    }

    private fun weekday(s: String): DayOfWeek {
        val k = s.lowercase(Locale.ROOT).take(3)
        return DayOfWeek.values()[listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun").indexOf(k)]
    }

    private fun ampm(s: String?): Char? = s?.trim()?.lowercase(Locale.ROOT)?.firstOrNull()

    fun parseReminder(raw: String, now: LocalDateTime): ParsedReminder {
        var w = " " + raw.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?') + " "
        w = " " + PREFIX.replace(w.trim(), "") + " "

        fun take(r: Regex): MatchResult? {
            val m = r.find(w) ?: return null
            w = w.substring(0, m.range.first) + " " + w.substring(m.range.last + 1)
            return m
        }

        val today = now.toLocalDate()
        var repeat = ReminderRepeat.NONE
        var weeklyDay: DayOfWeek? = null
        var date: LocalDate? = null
        var explicitDate = false
        var pod: String? = null
        var hour: Int? = null
        var minute = 0
        var mark: Char? = null
        var nextWeek = false

        // Repeats
        take(REPEAT_WEEKLY)?.let { repeat = ReminderRepeat.WEEKLY; weeklyDay = weekday(it.groupValues[1]) }
        if (repeat == ReminderRepeat.NONE) take(REPEAT_DAILY)?.let {
            repeat = ReminderRepeat.DAILY
            if (it.groupValues[1].isNotEmpty()) pod = it.groupValues[1]
        }

        // "in 10 minutes", "in an hour and a half"
        take(RELATIVE)?.let { m ->
            val amount = NUMBERS[m.groupValues[1].lowercase(Locale.ROOT).replace("-", " ")]
                ?: m.groupValues[1].toDoubleOrNull() ?: 1.0
            val unit = m.groupValues[2].lowercase(Locale.ROOT)
            var minutes = when {
                unit.startsWith("m") -> amount
                unit.startsWith("h") -> amount * 60
                unit.startsWith("d") -> amount * 60 * 24
                else -> amount * 60 * 24 * 7
            }
            val extra = m.groupValues[3].lowercase(Locale.ROOT)
            if (extra == "half") {
                minutes += when {
                    unit.startsWith("h") -> 30.0
                    unit.startsWith("d") -> 720.0
                    unit.startsWith("m") -> 0.5
                    else -> 3.5 * 1440
                }
            } else if (extra.isNotEmpty()) {
                minutes += extra.filter { it.isDigit() }.toDoubleOrNull() ?: 0.0
            }
            val at = now.withSecond(0).withNano(0).plusSeconds((minutes * 60).toLong())
            return ParsedReminder(cleanTask(w), at, repeat)
        }

        // Dates
        take(DATE_DAY_MONTH)?.let { m ->
            date = safeDate(m.groupValues[3].toIntOrNull(), month(m.groupValues[2]), m.groupValues[1].toInt(), today)
            explicitDate = true
        }
        if (date == null) take(DATE_MONTH_DAY)?.let { m ->
            date = safeDate(m.groupValues[3].toIntOrNull(), month(m.groupValues[1]), m.groupValues[2].toInt(), today)
            explicitDate = true
        }
        if (date == null) take(DATE_SLASH)?.let { m ->
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            date = safeDate(y, m.groupValues[2].toInt(), m.groupValues[1].toInt(), today)
            explicitDate = true
        }
        if (date == null) take(DAY_AFTER)?.let { date = today.plusDays(2) }
        if (date == null) take(TOMORROW)?.let { m ->
            date = today.plusDays(1)
            if (m.groupValues[1].isNotEmpty()) pod = m.groupValues[1]
        }
        if (date == null) take(TONIGHT)?.let { date = today; pod = "night" }
        if (date == null) take(THIS_POD)?.let { m -> date = today; pod = m.groupValues[1] }
        if (date == null) take(TODAY)?.let { date = today }
        var wantDay: DayOfWeek? = null
        if (date == null && repeat == ReminderRepeat.NONE) {
            val m = take(WEEKDAY) ?: take(WEEKDAY_SHORT)
            if (m != null) {
                wantDay = weekday(m.groupValues[2])
                nextWeek = m.groupValues[1].trim().equals("next", ignoreCase = true)
                if (m.groupValues.size > 3 && m.groupValues[3].isNotEmpty()) pod = m.groupValues[3]
            }
        }
        if (date == null && wantDay == null) take(DATE_ORDINAL)?.let { m ->
            val d = m.groupValues[1].toInt()
            var cand = safeDate(today.year, today.monthValue, d, null)
            if (cand == null || cand.isBefore(today)) {
                val nm = today.plusMonths(1)
                cand = safeDate(nm.year, nm.monthValue, d, null)
            }
            date = cand
            explicitDate = cand != null
        }

        // Times
        take(T_HALF)?.let { m ->
            val h = num(m.groupValues[2]) ?: 0
            when (m.groupValues[1].lowercase(Locale.ROOT)) {
                "half past" -> { hour = h; minute = 30 }
                "quarter past" -> { hour = h; minute = 15 }
                else -> { hour = if (h == 1) 12 else h - 1; minute = 45 }
            }
            mark = ampm(m.groupValues.getOrNull(3))
        }
        if (hour == null) take(T_COLON)?.let { m -> hour = m.groupValues[1].toInt(); minute = m.groupValues[2].toInt(); mark = ampm(m.groupValues[3]) }
        if (hour == null) take(T_DOT)?.let { m -> hour = m.groupValues[1].toInt(); minute = m.groupValues[2].toInt(); mark = ampm(m.groupValues[3]) }
        if (hour == null) take(T_AMPM)?.let { m -> hour = m.groupValues[1].toInt(); mark = ampm(m.groupValues[2]) }
        if (hour == null) take(T_MILITARY)?.let { m -> hour = m.groupValues[1].toInt(); minute = m.groupValues[2].toInt() }
        if (hour == null) take(T_OCLOCK)?.let { m -> hour = num(m.groupValues[1]) }
        if (hour == null) take(T_WORD)?.let { m ->
            if (m.groupValues[1].equals("midnight", true)) {
                hour = 0; mark = 'x'
                if (date == null) date = today.plusDays(1)
            } else {
                hour = 12; mark = 'x'
            }
        }
        if (hour == null) take(T_AT)?.let { m -> hour = num(m.groupValues[1]) }
        take(POD)?.let { m -> if (pod == null) pod = m.groupValues[1] }

        val task = cleanTask(w)

        // Work out the clock time
        var time: LocalTime? = null
        var ambiguous = false
        hour?.let { h0 ->
            val h = h0.coerceIn(0, 23)
            val m = minute.coerceIn(0, 59)
            time = when {
                mark == 'x' -> LocalTime.of(h, m)
                mark == 'a' -> LocalTime.of(h % 12, m)
                mark == 'p' -> LocalTime.of(h % 12 + 12, m)
                h == 0 || h > 12 -> LocalTime.of(h, m)
                pod != null && pod!!.lowercase(Locale.ROOT) != "morning" -> LocalTime.of(if (h == 12) 12 else h + 12, m)
                pod != null -> LocalTime.of(h % 12, m)
                h == 12 -> LocalTime.of(12, m)
                else -> {
                    ambiguous = true
                    if (h in 7..11) LocalTime.of(h, m) else LocalTime.of(h + 12, m)
                }
            }
        }
        if (time == null && pod != null) time = podDefault(pod!!)

        // Repeating reminders
        if (repeat == ReminderRepeat.DAILY) {
            val t = time ?: LocalTime.of(8, 0)
            var at = LocalDateTime.of(today, t)
            if (!at.isAfter(now)) at = at.plusDays(1)
            return ParsedReminder(task, at, repeat)
        }
        if (repeat == ReminderRepeat.WEEKLY) {
            val t = time ?: LocalTime.of(9, 0)
            val ahead = (weeklyDay!!.value - today.dayOfWeek.value + 7) % 7
            var at = LocalDateTime.of(today.plusDays(ahead.toLong()), t)
            if (!at.isAfter(now)) at = at.plusDays(7)
            return ParsedReminder(task, at, repeat)
        }

        // Weekday
        wantDay?.let { d ->
            var ahead = (d.value - today.dayOfWeek.value + 7) % 7
            if (ahead == 0) {
                val t = time ?: LocalTime.of(9, 0)
                if (nextWeek || !LocalDateTime.of(today, t).isAfter(now)) ahead = 7
            }
            date = today.plusDays(ahead.toLong())
        }

        if (date == null && time == null) return ParsedReminder(task, null, ReminderRepeat.NONE)

        if (date == null) {
            var at = LocalDateTime.of(today, time!!)
            if (!at.isAfter(now) && ambiguous) {
                val other = LocalDateTime.of(today, time!!.plusHours(12))
                at = if (other.isAfter(now) && time!!.hour < 12) other else at
            }
            if (!at.isAfter(now)) at = at.plusDays(1)
            return ParsedReminder(task, at, ReminderRepeat.NONE)
        }

        var t = time ?: LocalTime.of(9, 0)
        if (date == today && !LocalDateTime.of(today, t).isAfter(now) && ambiguous && t.hour < 12) t = t.plusHours(12)
        var at = LocalDateTime.of(date!!, t)
        if (!explicitDate && date == today && !at.isAfter(now)) at = at.plusDays(1)
        return ParsedReminder(task, at, ReminderRepeat.NONE)
    }

    /** Next time a repeating reminder should ring, strictly after now. */
    fun nextRepeat(time: LocalDateTime, repeat: ReminderRepeat, now: LocalDateTime): LocalDateTime? {
        val step = when (repeat) {
            ReminderRepeat.DAILY -> 1L
            ReminderRepeat.WEEKLY -> 7L
            ReminderRepeat.NONE -> return null
        }
        var t = time
        while (!t.isAfter(now)) t = t.plusDays(step)
        return t
    }

    private fun safeDate(year: Int?, month: Int, day: Int, today: LocalDate?): LocalDate? {
        if (month !in 1..12) return null
        val y = year ?: today?.year ?: return null
        val d = try { LocalDate.of(y, month, day) } catch (e: Exception) { return null }
        return if (year == null && today != null && d.isBefore(today)) d.plusYears(1) else d
    }

    private fun cleanTask(w: String): String {
        var t = w.replace(Regex("\\s+"), " ").trim().trim(',', '.', '-', ' ')
        repeat(2) {
            t = t.replace(Regex("^(?:to|that|about|for|of|on|at|by|and|then|i need to|i have to|i must|i should)\\s+", IC), "").trim()
            t = t.replace(Regex("\\s+(?:on|at|by|in|for|to|and|the|this|next|please)$", IC), "").trim()
            t = t.trim(',', '.', '-', ' ')
        }
        if (t.isEmpty()) return "Reminder"
        return t.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}
