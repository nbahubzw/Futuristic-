package com.orbit.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Orbit task alarm: rings full screen over the lock screen when a task is due and the phone is asleep.

class TaskAlarmActivity : Activity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val RING_FOR_MS = 3 * 60 * 1000L
        private var current: WeakReference<TaskAlarmActivity>? = null

        fun intent(context: Context, id: Long): Intent =
            Intent(context, TaskAlarmActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

        /** Stops the alarm screen if it is showing this task (used by the notification buttons). */
        fun close(id: Long) {
            current?.get()?.let { if (it.reminderId == id) it.finishHandled() }
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var volume = 0.15f
    private var handled = false
    private var reminderId = -1L
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var taskTitle: TextView
    private lateinit var subtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 31) window.setBackgroundBlurRadius(dp(40))
        setContentView(build())
        reminderId = intent.getLongExtra(EXTRA_ID, -1L)
        bind()
        startRinging()
        ui.postDelayed(tick, 1000)
        ui.postDelayed({ if (!isFinishing) finish() }, RING_FOR_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reminderId = intent.getLongExtra(EXTRA_ID, reminderId)
        bind()
    }

    override fun onDestroy() {
        stopRinging()
        ui.removeCallbacksAndMessages(null)
        if (current?.get() === this) current = null
        if (!handled) {
            // Not answered: leave a normal notification so it isn't missed
            ReminderNotifier.cancel(this, reminderId)
            ReminderStore.find(this, reminderId)?.let { if (!it.done) ReminderNotifier.show(this, it, OrbitProfile.name(this)) }
        }
        super.onDestroy()
    }

    private fun finishHandled() {
        handled = true
        ReminderNotifier.cancel(this, reminderId)
        finish()
    }

    // ---------------- Screen ----------------

    private fun build(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(if (Build.VERSION.SDK_INT >= 31) 0x8C000000.toInt() else 0xE6000000.toInt())

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(72), dp(28), dp(40))
        }
        root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        column.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(dp(28), dp(56) + insets.systemWindowInsetTop, dp(28), dp(28) + insets.systemWindowInsetBottom)
            insets
        }

        val tag = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tag.addView(Glyph(this, Glyph.Kind.BELL, 0xCCFFFFFF.toInt()), LinearLayout.LayoutParams(dp(20), dp(20)))
        tag.addView(label("Reminder", 17f, 0xCCFFFFFF.toInt(), IosType.semibold).apply { setPadding(dp(6), 0, 0, 0) })
        column.addView(tag)

        clock = label("", 96f, Color.WHITE, IosType.thin).apply { setShadowLayer(12f, 0f, 2f, 0x40000000) }
        column.addView(clock, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        date = label("", 20f, 0xE6FFFFFF.toInt(), IosType.medium)
        column.addView(date, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        column.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(0x2EFFFFFF, dp(26).toFloat())
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }
        taskTitle = label("", 26f, Color.WHITE, IosType.semibold, lines = 3).apply { gravity = Gravity.CENTER }
        subtitle = label("", 16f, 0xB3FFFFFF.toInt(), IosType.regular, lines = 2).apply { gravity = Gravity.CENTER }
        card.addView(taskTitle)
        card.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        column.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val snooze = label("Snooze", 20f, Color.WHITE, IosType.semibold).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFFFF9F0A.toInt(), dp(28).toFloat())
            minWidth = dp(200)
            pressable {
                ReminderStore.snooze(this@TaskAlarmActivity, reminderId)
                finishHandled()
            }
        }
        column.addView(snooze, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(56)).apply { topMargin = dp(36) })

        val stop = label("Stop", 22f, Color.WHITE, IosType.semibold).apply {
            gravity = Gravity.CENTER
            background = rounded(0x3DFFFFFF, dp(32).toFloat())
            pressable {
                val r = ReminderStore.find(this@TaskAlarmActivity, reminderId)
                if (r != null && r.repeat == ReminderRepeat.NONE) ReminderStore.setDone(this@TaskAlarmActivity, reminderId, true)
                finishHandled()
            }
        }
        column.addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(18) })
        return root
    }

    private fun bind() {
        val now = LocalDateTime.now()
        clock.text = now.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"))
        date.text = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK))
        val r = ReminderStore.find(this, reminderId)
        val name = OrbitProfile.name(this)
        taskTitle.text = r?.text ?: "Reminder"
        subtitle.text = buildString {
            append(if (name.isBlank()) "It's time" else "$name, it's time")
            when (r?.repeat) {
                ReminderRepeat.DAILY -> append(" · repeats every day")
                ReminderRepeat.WEEKDAYS -> append(" · repeats every weekday")
                ReminderRepeat.WEEKLY -> append(" · repeats every week")
                ReminderRepeat.MONTHLY -> append(" · repeats every month")
                else -> {}
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            bind()
            ui.postDelayed(this, 1000)
        }
    }

    // ---------------- Sound and vibration ----------------

    private fun startRinging() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@TaskAlarmActivity, uri)
                isLooping = true
                setVolume(volume, volume)
                prepare()
                start()
            }
            ui.postDelayed(ramp, 600)
        } catch (e: Exception) {
            player = null
        }

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 400, 500, 1400), 0))
        } catch (e: Exception) {
            // Some phones block vibration in silent modes
        }
    }

    /** Fades the ringtone in over about ten seconds, like a gentle wake-up alarm. */
    private val ramp = object : Runnable {
        override fun run() {
            val p = player ?: return
            volume = (volume + 0.05f).coerceAtMost(1f)
            try { p.setVolume(volume, volume) } catch (e: Exception) { return }
            if (volume < 1f) ui.postDelayed(this, 600)
        }
    }

    private fun stopRinging() {
        ui.removeCallbacks(ramp)
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        player?.release()
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
