package com.orbit.launcher

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

// Orbit's brain for everything that isn't a built-in command.
// Online: Google Gemini (with Google Search for current things like news and weather).
// Offline: a small model that runs on the phone itself (see OrbitOffline.kt).
// Online is used when there's a key and a connection; otherwise the offline brain answers.

object OrbitAI {

    enum class Source { ONLINE, OFFLINE, LOCAL }

    /** private = contains your messages: shown in the chat, never sent to the AI. */
    class Line(val user: Boolean, val text: String, val source: Source?, val at: Long = System.currentTimeMillis(), val private: Boolean = false)

    class Reply(val text: String, val source: Source)

    private const val PREFS = "orbit_ai"
    private const val MAX_LINES = 80
    private const val MEMORY_MS = 20 * 60 * 1000L // follow-up questions remember the last 20 minutes

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    // ---------------- Conversation (main thread) ----------------

    private val lines = ArrayList<Line>()
    private val listeners = ArrayList<() -> Unit>()

    var thinking = false
        private set

    fun transcript(): List<Line> = lines.toList()

    fun addListener(l: () -> Unit) {
        if (l !in listeners) listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun changed() {
        for (l in listeners.toList()) l()
    }

    fun record(user: Boolean, text: String, source: Source? = null, private: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        lines.add(Line(user, t, source, private = private))
        while (lines.size > MAX_LINES) lines.removeAt(0)
        changed()
    }

    fun setThinking(on: Boolean) {
        if (thinking == on) return
        thinking = on
        changed()
    }

    fun clearConversation() {
        lines.clear()
        changed()
    }

    // ---------------- Settings ----------------

    private fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun geminiKey(c: Context): String = prefs(c).getString("gemini_key", "") ?: ""

    fun setGeminiKey(c: Context, key: String) {
        prefs(c).edit().putString("gemini_key", key.trim()).remove("gemini_model").apply()
    }

    internal fun rememberedModel(c: Context): String? = prefs(c).getString("gemini_model", null)

    internal fun rememberModel(c: Context, model: String) = prefs(c).edit().putString("gemini_model", model).apply()

    fun onlineReady(c: Context) = geminiKey(c).isNotBlank()

    fun available(c: Context) = onlineReady(c) || OrbitOffline.ready(c)

    fun hasInternet(c: Context): Boolean = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        true
    }

    /** One line for Settings and the chat header. */
    fun status(c: Context): String {
        val online = onlineReady(c)
        val offline = OrbitOffline.ready(c)
        return when {
            online && offline -> "Gemini online, offline brain ready"
            online -> "Gemini online"
            offline -> "Offline brain"
            else -> "Not set up"
        }
    }

    // ---------------- Asking ----------------

    /** Answers anything. The reply always arrives on the main thread. */
    fun ask(context: Context, question: String, onReply: (Reply) -> Unit) {
        val app = context.applicationContext
        val history = recentHistory(question)
        val key = geminiKey(app)

        fun offlineOr(fallback: String) {
            if (!OrbitOffline.ready(app)) {
                onReply(Reply(fallback, Source.LOCAL))
                return
            }
            OrbitOffline.ask(app, offlinePrompt(app, history, question)) { text, error ->
                val clean = text?.let { tidy(it) }.orEmpty()
                if (clean.isNotEmpty()) onReply(Reply(clean, Source.OFFLINE))
                else onReply(Reply(OrbitOffline.problem(error), Source.LOCAL))
            }
        }

        if (key.isBlank()) {
            offlineOr("I can answer questions like that once my AI is set up. Open Orbit Settings and look under Intelligence.")
            return
        }
        if (!hasInternet(app)) {
            offlineOr("I'm offline right now. Turn on mobile data, or download my offline brain in Orbit Settings so I can answer without data.")
            return
        }
        val system = systemPrompt(app)
        io.execute {
            var answer: String? = null
            var failure: GeminiClient.Failure? = null
            try {
                answer = GeminiClient.ask(app, key, system, history, question)
            } catch (f: GeminiClient.Failure) {
                failure = f
            } catch (e: Exception) {
                failure = GeminiClient.Failure(-1, e.message ?: e.javaClass.simpleName)
            }
            main.post {
                val clean = answer?.let { tidy(it) }.orEmpty()
                if (clean.isNotEmpty()) onReply(Reply(clean, Source.ONLINE))
                else offlineOr(onlineProblem(failure))
            }
        }
    }

    /** Checks a key straight away, so Settings can say whether it works. */
    fun testKey(context: Context, key: String, onDone: (Boolean, String) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val result = try {
                GeminiClient.ask(app, key, "Reply with one short friendly sentence.", emptyList(), "Say hello.", allowSearch = false)
                true to "Your key works."
            } catch (f: GeminiClient.Failure) {
                false to onlineProblem(f)
            } catch (e: Exception) {
                false to "I couldn't reach Google just now. Check your data and try again."
            }
            main.post { onDone(result.first, result.second) }
        }
    }

    private fun onlineProblem(f: GeminiClient.Failure?): String {
        val msg = f?.message.orEmpty().lowercase(Locale.UK)
        return when {
            f == null -> "I couldn't get an answer just now. Please try again."
            f.code == 429 -> "I've used up the free online questions for now. Try again later, or download my offline brain in Orbit Settings."
            f.code == 401 || f.code == 403 || "api key" in msg || "api_key" in msg ->
                "Your Gemini key isn't working. Check it in Orbit Settings, under Intelligence."
            f.code == GeminiClient.BLOCKED -> "I can't help with that one."
            f.code == -1 -> "I couldn't reach the internet just now. Please try again."
            f.code >= 500 -> "Gemini is busy at the moment. Please try again in a minute."
            else -> "Gemini couldn't answer that (error ${f.code}). Please try again."
        }
    }

    private fun recentHistory(question: String): List<Line> {
        val now = System.currentTimeMillis()
        val recent = lines.filter { now - it.at < MEMORY_MS && !it.private }.toMutableList()
        // The question itself is already in the transcript; it's sent separately
        if (recent.isNotEmpty() && recent.last().user && recent.last().text == question.trim()) recent.removeAt(recent.size - 1)
        return recent.takeLast(12)
    }

    // ---------------- Prompts ----------------

    private fun who(c: Context) = OrbitProfile.name(c).ifBlank { "the user" }

    private fun systemPrompt(c: Context): String {
        val now = LocalDateTime.now()
        val name = who(c)
        val zone = ZoneId.systemDefault().id
        val sb = StringBuilder()
        sb.append("You are Orbit, the voice assistant built into $name's Android home screen. ")
        sb.append("It is ").append(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.UK)))
        sb.append(" (time zone $zone). ")
        if (name != "the user") sb.append("The user's name is $name. ")
        sb.append("\n\nHow to answer:\n")
        sb.append("Write in British English, warm and natural, like a clever friend.\n")
        sb.append("Keep it short: one to three sentences, unless they ask for detail, steps, a list or a longer piece of writing.\n")
        sb.append("Your reply may be read aloud, so use plain text only: no markdown, no asterisks, no hash signs, no emojis and no links. Number steps as 1, 2, 3 on separate lines when steps are needed.\n")
        sb.append("Never use em dashes or en dashes. Use commas or full stops instead.\n")
        sb.append("Use Google Search for anything current, such as news, weather, prices, sport or opening hours.\n")
        sb.append("You cannot press buttons on the phone or see messages yourself. If they want an action, tell them in a few words what to say to Orbit, for example: open WhatsApp, call Mum, read my messages, message Mum that I'm on my way, play Jah Prayzah on Spotify, pause, next song, or remind me at 5 to buy bread.\n")
        val tasks = taskContext(c, now)
        if (tasks.isNotEmpty()) sb.append("\nTheir open tasks, in case they ask:\n").append(tasks)
        return sb.toString()
    }

    private fun taskContext(c: Context, now: LocalDateTime): String = try {
        ReminderStore.all(c).filter { !it.done }
            .sortedBy { it.time ?: Long.MAX_VALUE }
            .take(8)
            .joinToString("\n") { r ->
                val t = r.local()
                if (t == null) r.text else r.text + " (" + ReminderText.spoken(t, now) + ")"
            }
    } catch (e: Exception) {
        ""
    }

    private fun offlinePrompt(c: Context, history: List<Line>, question: String): String {
        val now = LocalDateTime.now()
        val name = OrbitProfile.name(c).ifBlank { "User" }
        val sb = StringBuilder()
        sb.append("You are Orbit, a friendly assistant on $name's phone. ")
        sb.append("It is ").append(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.UK))).append(". ")
        sb.append("Answer in British English in one to three short sentences of plain text.\n\n")
        // Small models have little room, so keep only the last few lines
        for (l in history.takeLast(4)) {
            sb.append(if (l.user) name else "Orbit").append(": ").append(l.text.take(300)).append('\n')
        }
        sb.append(name).append(": ").append(question.take(600))
        return sb.toString()
    }

    /** Turns model output into clean, speakable text. */
    internal fun tidy(raw: String): String {
        var t = raw.replace("\r", "")
        t = t.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        t = t.replace(Regex("(?m)^\\s*[*\\u2022-]\\s+"), "")
        t = t.replace("**", "").replace("__", "").replace("`", "").replace("*", "")
        t = t.replace(Regex("(?<=\\d)\\s*[\\u2013\\u2014]\\s*(?=\\d)"), " to ")
        t = t.replace(Regex("\\s*[\\u2014\\u2013]\\s*"), ", ")
        t = t.replace(Regex("^(?:Orbit|Assistant|Model)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\n{3,}"), "\n\n")
        return t.trim()
    }
}

/** Talks to the Gemini API over HTTPS. No extra libraries needed. */
object GeminiClient {

    class Failure(val code: Int, message: String) : Exception(message)

    const val BLOCKED = 299

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    // Tried in order; the first that works is remembered
    private val MODELS = listOf(
        "gemini-3.5-flash", "gemini-flash-latest", "gemini-3.5-flash-lite", "gemini-flash-lite-latest", "gemini-2.5-flash"
    )

    fun ask(
        c: Context,
        key: String,
        system: String,
        history: List<OrbitAI.Line>,
        question: String,
        allowSearch: Boolean = true
    ): String {
        val order = (listOfNotNull(OrbitAI.rememberedModel(c)) + MODELS).distinct()
        var last: Failure? = null
        for (model in order) {
            var search = allowSearch
            while (true) {
                try {
                    val text = call(key, model, body(system, history, question, model, search))
                    OrbitAI.rememberModel(c, model)
                    return text
                } catch (f: Failure) {
                    last = f
                    val msg = f.message.orEmpty().lowercase(Locale.UK)
                    when {
                        f.code == 404 -> break // model not available: try the next one
                        f.code == 429 && "limit: 0" in msg -> break // not on the free plan: try the next one
                        f.code == 400 && ("api key" in msg || "api_key" in msg) -> throw f
                        search && f.code in setOf(400, 403, 429) -> search = false // try again without Google Search
                        f.code == 400 && ("model" in msg || "thinking" in msg) -> break
                        else -> throw f
                    }
                }
            }
        }
        throw last ?: Failure(-1, "No Gemini model answered")
    }

    private fun body(system: String, history: List<OrbitAI.Line>, question: String, model: String, search: Boolean): String {
        val turns = ArrayList<Pair<String, StringBuilder>>()
        fun add(role: String, text: String) {
            val lastTurn = turns.lastOrNull()
            if (lastTurn != null && lastTurn.first == role) lastTurn.second.append("\n").append(text)
            else turns.add(role to StringBuilder(text))
        }
        for (l in history) {
            if (turns.isEmpty() && !l.user) continue // must start with the user
            add(if (l.user) "user" else "model", l.text)
        }
        add("user", question)
        val contents = JSONArray()
        for ((role, text) in turns) {
            contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text.toString()))))
        }
        val root = JSONObject()
        root.put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        root.put("contents", contents)
        if (search) root.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        if (model.startsWith("gemini-3")) {
            root.put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
        }
        return root.toString()
    }

    private fun call(key: String, model: String, json: String): String {
        val conn = (URL(BASE + model + ":generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = read(if (code in 200..299) conn.inputStream else conn.errorStream)
            if (code !in 200..299) {
                val message = try {
                    JSONObject(text).optJSONObject("error")?.optString("message").orEmpty()
                } catch (e: Exception) {
                    ""
                }
                throw Failure(code, message.ifBlank { "HTTP $code" })
            }
            return parse(text)
        } catch (f: Failure) {
            throw f
        } catch (e: Exception) {
            throw Failure(-1, e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun read(stream: InputStream?): String = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

    private fun parse(text: String): String {
        val root = JSONObject(text)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) throw Failure(BLOCKED, "No answer")
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: throw Failure(BLOCKED, "No answer")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text", ""))
        }
        val out = sb.toString().trim()
        if (out.isEmpty()) throw Failure(BLOCKED, "Empty answer")
        return out
    }
}
