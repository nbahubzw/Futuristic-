package com.orbit.launcher

import android.app.Activity
import android.app.DownloadManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File
import java.util.concurrent.Executors

// Orbit's offline brain: a small language model that runs on the phone, so Orbit can answer
// without data. The model runs in its own background process ("orbit:brain"), so it never
// slows down or crashes the home screen, and it's unloaded a few minutes after the last question.

object OrbitOffline {

    class Model(val title: String, val detail: String, val file: String, val url: String, val sizeMb: Int)

    /** Downloads straight into Orbit, no account needed. */
    val QUICK = Model(
        "Qwen 2.5",
        "Small and quick, no account needed",
        "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        547
    )

    /** Smarter, but Google asks you to accept its licence on Hugging Face first, so it's downloaded in the browser. */
    const val GEMMA_PAGE = "https://huggingface.co/litert-community/Gemma3-1B-IT"
    const val GEMMA_FILE = "gemma3-1b-it-int4.task"

    private const val PREFS = "orbit_offline"
    private const val MIN_BYTES = 50L * 1024 * 1024
    private const val IDLE_MS = 3 * 60 * 1000L
    private const val TIMEOUT_MS = 150_000L

    private fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dir(c: Context): File = (c.applicationContext.getExternalFilesDir("models") ?: File(c.applicationContext.filesDir, "models")).apply { mkdirs() }

    // ---------------- The installed model ----------------

    fun modelPath(c: Context): String? {
        val p = prefs(c).getString("path", null) ?: return null
        val f = File(p)
        return if (f.isFile && f.length() >= MIN_BYTES) p else null
    }

    fun modelName(c: Context): String {
        val p = modelPath(c) ?: return ""
        val n = File(p).name.lowercase()
        return when {
            n.startsWith("gemma3") || n.startsWith("gemma-3") -> "Gemma 3"
            n.startsWith("qwen") -> "Qwen 2.5"
            else -> File(p).nameWithoutExtension
        }
    }

    fun ready(c: Context): Boolean = modelPath(c) != null && downloadId(c) == -1L

    internal fun setModel(c: Context, file: File) {
        prefs(c).edit().putString("path", file.absolutePath).apply()
        release()
    }

    /** Deletes every model file and forgets the model. */
    fun delete(c: Context) {
        cancelDownload(c)
        release()
        dir(c).listFiles()?.forEach { it.delete() }
        prefs(c).edit().remove("path").apply()
    }

    fun sizeText(c: Context): String {
        val p = modelPath(c) ?: return ""
        return "${File(p).length() / (1024 * 1024)} MB"
    }

    // ---------------- Downloading ----------------

    class Progress(val percent: Int, val failed: Boolean)

    private fun downloadId(c: Context) = prefs(c).getLong("download", -1L)

    fun startDownload(c: Context, model: Model): Boolean {
        val app = c.applicationContext
        cancelDownload(app)
        File(dir(app), model.file).delete()
        return try {
            val dm = app.getSystemService(DownloadManager::class.java) ?: return false
            val req = DownloadManager.Request(Uri.parse(model.url))
                .setTitle("Orbit offline brain")
                .setDescription("${model.title}, ${model.sizeMb} MB")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalFilesDir(app, "models", model.file)
            val id = dm.enqueue(req)
            prefs(app).edit().putLong("download", id).putString("download_file", model.file).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun cancelDownload(c: Context) {
        val id = downloadId(c)
        if (id == -1L) return
        try {
            c.getSystemService(DownloadManager::class.java)?.remove(id)
        } catch (e: Exception) {
        }
        prefs(c).edit().remove("download").remove("download_file").apply()
    }

    /** Null when nothing is downloading. Also finishes the job once the download completes. */
    fun progress(c: Context): Progress? {
        val app = c.applicationContext
        val id = downloadId(app)
        if (id == -1L) return null
        val dm = app.getSystemService(DownloadManager::class.java) ?: return null
        try {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
                if (!cur.moveToFirst()) {
                    prefs(app).edit().remove("download").apply()
                    return null
                }
                val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val local = cur.getString(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        val file = local?.let { Uri.parse(it).path }?.let { File(it) }
                            ?: File(dir(app), prefs(app).getString("download_file", "") ?: "")
                        prefs(app).edit().remove("download").remove("download_file").apply()
                        if (file.isFile && file.length() >= MIN_BYTES) setModel(app, file)
                        return null
                    }
                    DownloadManager.STATUS_FAILED -> {
                        prefs(app).edit().remove("download").remove("download_file").apply()
                        try { dm.remove(id) } catch (e: Exception) { }
                        return Progress(0, true)
                    }
                    else -> return Progress(if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 99) else 0, false)
                }
            }
        } catch (e: Exception) {
        }
        return null
    }

    // ---------------- Asking (talks to the brain process) ----------------

    private val main = Handler(Looper.getMainLooper())
    private var messenger: Messenger? = null
    private var bound = false
    private var nextId = 1
    private val pending = HashMap<Int, (String?, String?) -> Unit>()
    private val waiting = ArrayList<Message>()
    private var appContext: Context? = null

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != OrbitBrainService.MSG_REPLY) return
            val cb = pending.remove(msg.arg1) ?: return
            val data = msg.data
            cb(data?.getString("text"), data?.getString("error"))
            scheduleIdle()
        }
    })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val m = Messenger(service)
            messenger = m
            for (msg in waiting) send(m, msg)
            waiting.clear()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The brain process stopped (usually the phone ran short of memory, or the model file is broken)
            messenger = null
            failAll("stopped")
            release()
        }

        override fun onBindingDied(name: ComponentName?) {
            messenger = null
            failAll("stopped")
            release()
        }
    }

    private val idle = Runnable {
        if (pending.isEmpty()) release() else scheduleIdle()
    }

    private fun scheduleIdle() {
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_MS)
    }

    private fun failAll(error: String) {
        val cbs = pending.values.toList()
        pending.clear()
        waiting.clear()
        for (cb in cbs) cb(null, error)
    }

    private fun send(m: Messenger, msg: Message) {
        try {
            m.send(msg)
        } catch (e: RemoteException) {
            pending.remove(msg.arg1)?.invoke(null, "stopped")
        }
    }

    /** Callback gets (answer, null) or (null, error). Always on the main thread. */
    fun ask(c: Context, prompt: String, cb: (String?, String?) -> Unit) {
        val app = c.applicationContext
        appContext = app
        val path = modelPath(app)
        if (path == null) {
            cb(null, "missing")
            return
        }
        val id = nextId++
        pending[id] = cb
        val msg = Message.obtain(null, OrbitBrainService.MSG_ASK, id, 0).apply {
            data = Bundle().apply {
                putString("prompt", prompt)
                putString("path", path)
            }
            replyTo = incoming
        }
        if (!bound) {
            bound = try {
                app.bindService(Intent(app, OrbitBrainService::class.java), conn, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                false
            }
            if (!bound) {
                pending.remove(id)
                cb(null, "start")
                return
            }
        }
        val m = messenger
        if (m != null) send(m, msg) else waiting.add(msg)
        main.postDelayed({ pending.remove(id)?.invoke(null, "timeout") }, TIMEOUT_MS)
        scheduleIdle()
    }

    /** Unloads the model and frees its memory. */
    fun release() {
        main.removeCallbacks(idle)
        val app = appContext
        if (bound && app != null) {
            try {
                app.unbindService(conn)
            } catch (e: Exception) {
            }
        }
        bound = false
        messenger = null
    }

    fun problem(error: String?): String = when (error) {
        "missing" -> "My offline brain isn't downloaded yet. You can get it in Orbit Settings, under Intelligence."
        "timeout" -> "That took too long for my offline brain. Try a shorter question."
        "stopped" -> "My offline brain stopped. The phone may be low on memory, or the model file may not suit this phone. Try again, or choose another model in Settings."
        else -> "My offline brain couldn't answer that. Try again, or re-download it in Orbit Settings."
    }
}

/** Runs the on-device model in its own process. */
class OrbitBrainService : Service() {

    companion object {
        const val MSG_ASK = 1
        const val MSG_REPLY = 2
    }

    private var engine: LlmInference? = null
    private var enginePath: String? = null
    private val worker = Executors.newSingleThreadExecutor()

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != MSG_ASK) return
            val reply = msg.replyTo ?: return
            val id = msg.arg1
            val prompt = msg.data?.getString("prompt").orEmpty()
            val path = msg.data?.getString("path").orEmpty()
            worker.execute {
                val out = Bundle()
                try {
                    out.putString("text", generate(path, prompt))
                } catch (t: Throwable) {
                    out.putString("error", t.message ?: t.javaClass.simpleName)
                }
                val m = Message.obtain(null, MSG_REPLY, id, 0)
                m.data = out
                try {
                    reply.send(m)
                } catch (e: RemoteException) {
                }
            }
        }
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        worker.execute {
            try {
                engine?.close()
            } catch (e: Exception) {
            }
            engine = null
        }
        worker.shutdown()
        super.onDestroy()
    }

    private fun generate(path: String, prompt: String): String {
        var e = engine
        if (e == null || enginePath != path) {
            try {
                e?.close()
            } catch (x: Exception) {
            }
            engine = null
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(1024)
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()
            e = LlmInference.createFromOptions(this, options)
            engine = e
            enginePath = path
        }
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTemperature(0.7f)
            .build()
        val session = LlmInferenceSession.createFromOptions(e, sessionOptions)
        try {
            session.addQueryChunk(prompt)
            return session.generateResponse()
        } finally {
            try {
                session.close()
            } catch (x: Exception) {
            }
        }
    }
}

/** Lets you pick a model file you downloaded in the browser (for example Gemma 3) and copies it into Orbit. */
class ModelImportActivity : Activity() {

    private var status: TextView? = null
    @Volatile private var copying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(0x66000000))
        val c = IosColors.of(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(c.sheetCell, dp(18).toFloat())
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        card.addView(label("Offline Brain", 17f, c.label, IosType.semibold))
        val s = label("Choose the model file you downloaded", 14f, c.secondary, lines = 3).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        status = s
        card.addView(s)
        val root = FrameLayout(this)
        root.addView(card, FrameLayout.LayoutParams(dp(280), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        if (savedInstanceState == null) {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(pick, 41)
            } catch (e: Exception) {
                Toast.makeText(this, "No file picker found", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != 41 || resultCode != RESULT_OK || uri == null) {
            finish()
            return
        }
        val name = displayName(uri) ?: "model.task"
        val lower = name.lowercase()
        if (!lower.endsWith(".task") && !lower.endsWith(".litertlm")) {
            Toast.makeText(this, "That isn't a model file. Choose a file ending in .task", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val app = applicationContext
        val target = File(OrbitOffline.dir(app), name)
        status?.text = "Copying $name"
        copying = true
        Thread {
            var ok = false
            try {
                val total = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var copied = 0L
                        var shown = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (total > 0) {
                                val pct = ((copied * 100) / total).toInt()
                                if (pct != shown) {
                                    shown = pct
                                    runOnUiThread { status?.text = "Copying $name\n$pct%" }
                                }
                            }
                        }
                    }
                }
                ok = target.length() > 50L * 1024 * 1024
                if (ok) {
                    // Keep only the new model
                    OrbitOffline.dir(app).listFiles()?.forEach { if (it.absolutePath != target.absolutePath) it.delete() }
                    OrbitOffline.setModel(app, target)
                } else {
                    target.delete()
                }
            } catch (e: Exception) {
                target.delete()
            }
            copying = false
            runOnUiThread {
                Toast.makeText(app, if (ok) "Offline brain ready" else "That file couldn't be used", Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (copying) return // finish copying first
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
