package com.orbit.launcher

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent

// Orbit and music: "play Jah Prayzah", "play Dorime on YouTube", "pause", "next song", "turn it up".
// Songs play in your music app (Spotify, YouTube Music, Audiomack, Boomplay and others); YouTube is the fallback.

object OrbitMedia {

    class Player(val name: String, val pkg: String)

    // In order of preference when you don't name an app
    private val PLAYERS = listOf(
        Player("Spotify", "com.spotify.music"),
        Player("YouTube Music", "com.google.android.apps.youtube.music"),
        Player("Audiomack", "com.audiomack"),
        Player("Boomplay", "com.afmobi.boomplayer"),
        Player("Apple Music", "com.apple.android.music"),
        Player("Deezer", "deezer.android.app"),
        Player("SoundCloud", "com.soundcloud.android"),
        Player("YouTube", "com.google.android.youtube")
    )

    private const val YOUTUBE = "com.google.android.youtube"

    /** The app named in "on Spotify", if any. */
    fun playerFor(word: String?): Player? {
        if (word.isNullOrBlank()) return null
        val w = word.lowercase().replace(Regex("[^a-z ]"), "").trim()
        return PLAYERS.firstOrNull { it.name.lowercase() == w } ?: PLAYERS.firstOrNull { w.startsWith(it.name.lowercase()) }
    }

    private fun installed(c: Context, pkg: String) = try {
        c.packageManager.getLaunchIntentForPackage(pkg) != null
    } catch (e: Exception) {
        false
    }

    /** Your music app: the first installed one that can play a song by name. */
    fun preferred(c: Context): Player? {
        val canSearch = try {
            c.packageManager.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        PLAYERS.firstOrNull { it.pkg != YOUTUBE && it.pkg in canSearch && installed(c, it.pkg) }?.let { return it }
        PLAYERS.firstOrNull { it.pkg != YOUTUBE && installed(c, it.pkg) }?.let { return it }
        return if (installed(c, YOUTUBE)) PLAYERS.last() else null
    }

    /** Plays a song, artist or playlist. Returns what Orbit should say. */
    fun play(c: Context, query: String, appWord: String?, who: String): String {
        val asked = playerFor(appWord)
        if (asked != null && !installed(c, asked.pkg)) return "${asked.name} isn't installed$who."
        val player = asked ?: preferred(c) ?: return openYouTubeSearch(c, query, who)
        if (player.pkg == YOUTUBE) return openYouTubeSearch(c, query, who)
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(player.pkg)
            putExtra(SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            c.startActivity(intent)
            "Playing $query on ${player.name}."
        } catch (e: Exception) {
            // This app can't play by name: open its search instead
            try {
                c.startActivity(Intent(Intent.ACTION_SEARCH).setPackage(player.pkg).putExtra(SearchManager.QUERY, query)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Here's $query on ${player.name}."
            } catch (x: Exception) {
                openYouTubeSearch(c, query, who)
            }
        }
    }

    private fun openYouTubeSearch(c: Context, query: String, who: String): String {
        if (installed(c, YOUTUBE)) {
            try {
                c.startActivity(Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE).putExtra("query", query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Here's $query on YouTube."
            } catch (e: Exception) {
            }
        }
        return try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Here's $query on YouTube."
        } catch (e: Exception) {
            "I couldn't find a music app to play that$who."
        }
    }

    /** "Play some music": carry on with what was playing, or open your music app. */
    fun resume(c: Context, appWord: String?, who: String): String {
        val asked = playerFor(appWord)
        if (asked != null) {
            if (!installed(c, asked.pkg)) return "${asked.name} isn't installed$who."
            launch(c, asked.pkg)
            Handler(Looper.getMainLooper()).postDelayed({ key(c, KeyEvent.KEYCODE_MEDIA_PLAY) }, 1500)
            return "Opening ${asked.name}."
        }
        key(c, KeyEvent.KEYCODE_MEDIA_PLAY)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isPlaying(c)) preferred(c)?.let { launch(c, it.pkg) }
        }, 1200)
        return "Playing your music."
    }

    private fun launch(c: Context, pkg: String) {
        try {
            c.packageManager.getLaunchIntentForPackage(pkg)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } catch (e: Exception) {
        }
    }

    fun isPlaying(c: Context): Boolean = try {
        c.getSystemService(AudioManager::class.java)?.isMusicActive == true
    } catch (e: Exception) {
        false
    }

    /** Presses a media button (pause, next, previous) for whatever is playing. */
    fun key(c: Context, code: Int) {
        val am = c.getSystemService(AudioManager::class.java) ?: return
        val now = SystemClock.uptimeMillis()
        try {
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        } catch (e: Exception) {
        }
    }

    /** Runs a music control and returns what Orbit should say (empty for silence). */
    fun control(c: Context, action: String, who: String): String {
        val am = c.getSystemService(AudioManager::class.java)
        return when (action) {
            "pause" -> {
                key(c, KeyEvent.KEYCODE_MEDIA_PAUSE)
                ""
            }
            "resume" -> {
                key(c, KeyEvent.KEYCODE_MEDIA_PLAY)
                ""
            }
            "next" -> {
                key(c, KeyEvent.KEYCODE_MEDIA_NEXT)
                ""
            }
            "previous" -> {
                key(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                ""
            }
            "louder" -> {
                am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                "Turned it up$who."
            }
            "quieter" -> {
                am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                "Turned it down$who."
            }
            "mute" -> {
                am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                ""
            }
            "unmute" -> {
                am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                "Sound's back on$who."
            }
            else -> ""
        }
    }
}
