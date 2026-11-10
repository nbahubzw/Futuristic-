package com.orbit.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Process
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

const val COLUMNS = 4
const val ROWS = 6
const val FIRST_PAGE = 16
const val PAGE_SIZE = 24
const val DOCK_SIZE = 4

data class AppEntry(
    val key: String,
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val icon: ImageBitmap,
    val category: Int,
    val installed: Long,
    val isSystem: Boolean
)

data class AppGroup(val title: String, val apps: List<AppEntry>)

enum class Place { Home, Dock, Library }

data class MenuTarget(val app: AppEntry, val bounds: Rect, val place: Place)

class BoundsRef { var rect: Rect = Rect.Zero }

class LauncherModel(private val context: Context) {
    private val prefs = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var byKey by mutableStateOf<Map<String, AppEntry>>(emptyMap())
        private set
    var byPackage by mutableStateOf<Map<String, AppEntry>>(emptyMap())
        private set
    var home by mutableStateOf(read("home"))
        private set
    var dock by mutableStateOf(read("dock"))
        private set
    var recent by mutableStateOf(read("recent"))
        private set

    val dockApps: List<AppEntry>
        get() = dock.mapNotNull { byKey[it] }

    val pages: List<List<String>>
        get() {
            val keys = home.filter { it in byKey && it !in dock }
            return listOf(keys.take(FIRST_PAGE)) + keys.drop(FIRST_PAGE).chunked(PAGE_SIZE)
        }

    fun isOnHome(key: String) = key in home
    fun isInDock(key: String) = key in dock

    suspend fun load() {
        val loaded = withContext(Dispatchers.Default) { loadApps(context) }
        val keys = loaded.map { it.key }
        if (!prefs.contains("known")) {
            val newDock = defaultDock(loaded)
            dock = newDock
            write("dock", newDock)
            home = essentials(loaded, newDock.toSet())
            write("home", home)
        } else {
            val known = read("known").toSet()
            val added = keys.filter { it !in known && it !in home && it !in dock }
            if (added.isNotEmpty()) {
                home = home + added
                write("home", home)
            }
        }
        write("known", keys)
        apps = loaded
        byKey = loaded.associateBy { it.key }
        byPackage = loaded.associateBy { it.packageName }
    }

    fun removeFromHome(key: String) {
        home = home - key
        write("home", home)
    }

    fun addToHome(key: String) {
        if (key in home || key in dock) return
        home = home + key
        write("home", home)
    }

    fun moveToDock(key: String) {
        if (dock.size >= DOCK_SIZE || key in dock) return
        dock = dock + key
        home = home - key
        write("dock", dock)
        write("home", home)
    }

    fun removeFromDock(key: String) {
        dock = dock - key
        write("dock", dock)
        if (key !in home) {
            home = home + key
            write("home", home)
        }
    }

    fun reorderPage(pageIndex: Int, newKeys: List<String>) {
        val current = pages.toMutableList()
        if (pageIndex !in current.indices) return
        current[pageIndex] = newKeys
        home = current.flatten()
        write("home", home)
    }

    fun recordLaunch(key: String) {
        recent = (listOf(key) + (recent - key)).take(12)
        write("recent", recent)
    }

    fun suggestions(): List<AppEntry> =
        (recent + dock + home).distinct().mapNotNull { byKey[it] }.take(8)

    fun groups(): List<AppGroup> {
        val result = mutableListOf<AppGroup>()
        val suggested = suggestions()
        if (suggested.isNotEmpty()) result += AppGroup("Suggestions", suggested)
        if (apps.isNotEmpty()) {
            result += AppGroup("Recently Added", apps.sortedByDescending { it.installed }.take(8))
        }
        val byCategory = apps.groupBy { categoryOf(it) }
        listOf(
            "Social", "Entertainment", "Creativity", "Productivity & Finance",
            "Information & Reading", "Travel", "Games", "Utilities", "Other"
        ).forEach { name -> byCategory[name]?.let { result += AppGroup(name, it) } }
        return result
    }

    private fun categoryOf(app: AppEntry): String = when (app.category) {
        ApplicationInfo.CATEGORY_SOCIAL -> "Social"
        ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> "Entertainment"
        ApplicationInfo.CATEGORY_IMAGE -> "Creativity"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity & Finance"
        ApplicationInfo.CATEGORY_NEWS -> "Information & Reading"
        ApplicationInfo.CATEGORY_MAPS -> "Travel"
        ApplicationInfo.CATEGORY_GAME -> "Games"
        else -> if (app.isSystem) "Utilities" else "Other"
    }

    private fun read(name: String): List<String> =
        prefs.getString(name, null).orEmpty().split(";").filter { it.isNotBlank() }

    private fun write(name: String, list: List<String>) {
        prefs.edit().putString(name, list.joinToString(";")).apply()
    }

    private fun resolvePackage(intent: Intent): String? = runCatching {
        context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    }.getOrNull()

    private fun defaultDock(apps: List<AppEntry>): List<String> {
        val byPkg = apps.associateBy { it.packageName }
        return listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        ).mapNotNull { resolvePackage(it) }
            .distinct()
            .mapNotNull { byPkg[it]?.key }
            .take(DOCK_SIZE)
    }

    // First-run home screen: the everyday apps, Settings last
    private fun essentials(apps: List<AppEntry>, exclude: Set<String>): List<String> {
        val byPkg = apps.associateBy { it.packageName }
        fun byCategory(category: String) =
            resolvePackage(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category))
        val candidates = listOf(
            byCategory(Intent.CATEGORY_APP_CALENDAR),
            "com.google.android.apps.photos",
            byCategory(Intent.CATEGORY_APP_GALLERY),
            resolvePackage(Intent(AlarmClock.ACTION_SHOW_ALARMS)),
            byCategory(Intent.CATEGORY_APP_MAPS),
            byCategory(Intent.CATEGORY_APP_WEATHER),
            "com.google.android.gm",
            byCategory(Intent.CATEGORY_APP_EMAIL),
            "com.google.android.keep",
            "com.google.android.youtube",
            "com.android.vending",
            "com.whatsapp",
            "com.instagram.android",
            "com.spotify.music",
            byCategory(Intent.CATEGORY_APP_MUSIC),
            byCategory(Intent.CATEGORY_APP_FILES),
            byCategory(Intent.CATEGORY_APP_CALCULATOR),
            byCategory(Intent.CATEGORY_APP_CONTACTS)
        )
        val settings = resolvePackage(Intent(Settings.ACTION_SETTINGS))?.let { byPkg[it]?.key }
        val picked = candidates.filterNotNull()
            .distinct()
            .mapNotNull { byPkg[it]?.key }
            .filter { it !in exclude && it != settings }
            .take(FIRST_PAGE - 1)
        return picked + listOfNotNull(settings?.takeIf { it !in exclude })
    }
}

// ---------- App loading and iOS-style icons ----------

fun loadApps(context: Context): List<AppEntry> {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val px = (60 * context.resources.displayMetrics.density).roundToInt()
    return launcherApps.getActivityList(null, Process.myUserHandle())
        .filter { it.componentName.packageName != context.packageName }
        .mapNotNull { info ->
            runCatching {
                val appInfo = info.applicationInfo
                AppEntry(
                    key = info.componentName.flattenToString(),
                    label = info.label.toString(),
                    packageName = info.componentName.packageName,
                    component = info.componentName,
                    icon = renderIcon(info.getIcon(0), px).asImageBitmap(),
                    category = appInfo.category,
                    installed = info.firstInstallTime,
                    isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }.getOrNull()
        }
        .sortedBy { it.label.lowercase() }
}

// Draws the icon full-bleed, then cuts it into a smooth squircle
fun renderIcon(drawable: Drawable, px: Int): Bitmap {
    val full = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(full)
    if (drawable is AdaptiveIconDrawable) {
        val inset = px / 4
        val bounds = android.graphics.Rect(-inset, -inset, px + inset, px + inset)
        drawable.background?.let { it.bounds = bounds; it.draw(canvas) }
        drawable.foreground?.let { it.bounds = bounds; it.draw(canvas) }
    } else {
        canvas.drawColor(android.graphics.Color.WHITE)
        val pad = (px * 0.14f).roundToInt()
        drawable.setBounds(pad, pad, px - pad, px - pad)
        drawable.draw(canvas)
    }
    val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val outCanvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    outCanvas.drawPath(squirclePath(px.toFloat()), paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    outCanvas.drawBitmap(full, 0f, 0f, paint)
    full.recycle()
    return out
}

fun squirclePath(size: Float): Path {
    val path = Path()
    val r = size / 2f
    val steps = 120
    for (i in 0..steps) {
        val t = 2.0 * PI * i / steps
        val c = cos(t)
        val s = sin(t)
        val x = r + r * sign(c) * abs(c).pow(0.4)
        val y = r + r * sign(s) * abs(s).pow(0.4)
        if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
    }
    path.close()
    return path
}

// ---------- System actions ----------

fun launchApp(context: Context, app: AppEntry, bounds: Rect?) {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val source = bounds?.takeIf { it.width > 0f && it.height > 0f }?.let {
        android.graphics.Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())
    }
    val view = (context as? Activity)?.window?.decorView
    val options = if (source != null && view != null) {
        ActivityOptions.makeScaleUpAnimation(view, source.left, source.top, source.width(), source.height()).toBundle()
    } else null
    try {
        launcherApps.startMainActivity(app.component, Process.myUserHandle(), source, options)
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn’t open ${app.label}", Toast.LENGTH_SHORT).show()
    }
}

fun openAppInfo(context: Context, app: AppEntry) {
    runCatching {
        context.getSystemService(LauncherApps::class.java)
            .startAppDetailsActivity(app.component, Process.myUserHandle(), null, null)
    }
}

fun uninstallApp(context: Context, app: AppEntry) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null)))
    }
}

fun openWallpaperPicker(context: Context) {
    runCatching {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
    }
}

fun openIntent(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun webSearch(context: Context, query: String) {
    val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
    if (runCatching { context.startActivity(search) }.isFailure) {
        openIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
    }
}

// App shortcuts (only available while Orbit is your default home app)
fun loadShortcuts(context: Context, app: AppEntry): List<ShortcutInfo> {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    return runCatching {
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(app.packageName)
            .setActivity(app.component)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
            )
        launcherApps.getShortcuts(query, Process.myUserHandle()).orEmpty().sortedBy { it.rank }.take(4)
    }.getOrDefault(emptyList())
}

fun shortcutIcon(context: Context, info: ShortcutInfo): ImageBitmap? = runCatching {
    context.getSystemService(LauncherApps::class.java)
        .getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)
        ?.toBitmap(96, 96)
        ?.asImageBitmap()
}.getOrNull()

fun startShortcut(context: Context, info: ShortcutInfo) {
    runCatching { context.getSystemService(LauncherApps::class.java).startShortcut(info, null, null) }
}
