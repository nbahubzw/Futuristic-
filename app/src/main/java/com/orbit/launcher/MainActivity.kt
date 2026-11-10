package com.orbit.launcher

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.MediaStore
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// ---------- Palette ----------
private val Cyan = Color(0xFF00E5FF)
private val Violet = Color(0xFF7C4DFF)
private val Ink = Color(0xFF060A12)
private val TextHi = Color(0xFFE8F1FF)
private val TextLo = Color(0xFF8193AD)
private val Danger = Color(0xFFFF5370)
private val Mono = FontFamily.Monospace

// ---------- Activity ----------
class MainActivity : ComponentActivity() {
    private var resetSignal by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Cyan,
                    surface = Color(0xFF0D1420),
                    surfaceContainer = Color(0xFF0D1420),
                    onSurface = TextHi
                ),
                shapes = Shapes(extraSmall = RoundedCornerShape(14.dp))
            ) {
                Launcher(resetSignal)
            }
        }
    }

    // Home pressed while already on the launcher
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        resetSignal++
    }

    // Left the launcher (opened an app, screen off)
    override fun onStop() {
        super.onStop()
        resetSignal++
    }
}

// ---------- Data ----------
data class AppEntry(
    val key: String,
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val icon: ImageBitmap
)

class AppActions(
    val launch: (AppEntry) -> Unit,
    val togglePin: (AppEntry) -> Unit,
    val info: (AppEntry) -> Unit,
    val uninstall: (AppEntry) -> Unit,
    val isPinned: (AppEntry) -> Boolean
)

// ---------- Root ----------
@Composable
fun Launcher(resetSignal: Int) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val prefs = remember { context.getSharedPreferences("orbit", Context.MODE_PRIVATE) }

    val reload = remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var dock by remember {
        mutableStateOf(prefs.getString("dock", null)?.split(";")?.filter { it.isNotBlank() })
    }
    var drawerOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(reload.intValue) {
        apps = withContext(Dispatchers.Default) { loadApps(context) }
        loaded = true
        if (dock == null) {
            val defaults = defaultDock(context, apps)
            dock = defaults
            prefs.edit().putString("dock", defaults.joinToString(";")).apply()
        }
    }

    // Refresh when apps are installed, removed or updated
    DisposableEffect(Unit) {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val callback = object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) { reload.intValue++ }
            override fun onPackageRemoved(packageName: String, user: UserHandle) { reload.intValue++ }
            override fun onPackageChanged(packageName: String, user: UserHandle) { reload.intValue++ }
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { reload.intValue++ }
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) { reload.intValue++ }
        }
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { launcherApps.unregisterCallback(callback) }
    }

    LaunchedEffect(resetSignal) {
        drawerOpen = false
        query = ""
        focus.clearFocus()
    }

    fun saveDock(list: List<String>) {
        dock = list
        prefs.edit().putString("dock", list.joinToString(";")).apply()
    }

    val byKey = remember(apps) { apps.associateBy { it.key } }
    val dockApps = dock.orEmpty().mapNotNull { byKey[it] }

    val actions = AppActions(
        launch = { launchApp(context, it) },
        togglePin = { app ->
            val current = dock.orEmpty()
            when {
                app.key in current -> saveDock(current - app.key)
                current.size >= 5 -> Toast.makeText(context, "Dock is full — remove an app first", Toast.LENGTH_SHORT).show()
                else -> saveDock(current + app.key)
            }
        },
        info = { openAppInfo(context, it) },
        uninstall = { uninstallApp(context, it) },
        isPinned = { it.key in dock.orEmpty() }
    )

    val closeDrawer = {
        drawerOpen = false
        query = ""
        focus.clearFocus()
    }

    BackHandler { if (drawerOpen) closeDrawer() }

    val blurRadius by animateDpAsState(if (drawerOpen) 18.dp else 0.dp, tween(350), label = "blur")

    Box(Modifier.fillMaxSize()) {
        Backdrop()
        HomeScreen(
            dockApps = dockApps,
            showDockHint = loaded,
            actions = actions,
            onOpenDrawer = { drawerOpen = true },
            modifier = Modifier.blur(blurRadius)
        )
        AnimatedVisibility(
            visible = drawerOpen,
            enter = fadeIn(tween(220)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 5 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 5 }
        ) {
            AppDrawer(
                apps = apps,
                query = query,
                onQuery = { query = it },
                actions = actions,
                onClose = closeDrawer
            )
        }
    }
}

// ---------- Background ----------
@Composable
fun Backdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                listOf(Ink.copy(alpha = 0.60f), Ink.copy(alpha = 0.35f), Ink.copy(alpha = 0.85f))
            )
        )
        val step = 32.dp.toPx()
        val line = Color.White.copy(alpha = 0.03f)
        var x = step
        while (x < size.width) {
            drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f); x += step
        }
        var y = step
        while (y < size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += step
        }
    }
}

// ---------- Home ----------
@Composable
fun HomeScreen(
    dockApps: List<AppEntry>,
    showDockHint: Boolean,
    actions: AppActions,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val openDrawer by rememberUpdatedState(onOpenDrawer)

    Column(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -72.dp.toPx()) openDrawer() },
                    onVerticalDrag = { change, dy -> change.consume(); total += dy }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    openWallpaperPicker(context)
                })
            }
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        ClockCluster()
        Spacer(Modifier.weight(1.4f))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { openDrawer() }
                .padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Open app drawer", tint = Cyan.copy(alpha = 0.8f))
            Text("APPS", color = TextLo, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 4.sp)
        }
        Spacer(Modifier.height(12.dp))
        Dock(dockApps, showDockHint, actions)
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
fun ClockCluster() {
    val context = LocalContext.current
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val battery by produceState<Pair<Int, Boolean>?>(null) {
        while (true) {
            value = readBattery(context)
            delay(30_000L)
        }
    }
    val is24h = DateFormat.is24HourFormat(context)
    val locale = Locale.getDefault()
    val time = now.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm", locale))
    val amPm = if (is24h) "" else now.format(DateTimeFormatter.ofPattern("a", locale)).uppercase(locale)
    val date = now.format(DateTimeFormatter.ofPattern("EEE · d MMM", locale)).uppercase(locale)

    Box(Modifier.fillMaxWidth(0.84f).aspectRatio(1f), contentAlignment = Alignment.Center) {
        ChronoRing(second = now.second, modifier = Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date, color = Cyan.copy(alpha = 0.9f), fontFamily = Mono, fontSize = 12.sp, letterSpacing = 3.sp)
            Row {
                Text(
                    time, color = TextHi, fontSize = 72.sp, fontWeight = FontWeight.Thin,
                    letterSpacing = (-2).sp, maxLines = 1, softWrap = false,
                    modifier = Modifier.alignByBaseline()
                )
                if (amPm.isNotEmpty()) {
                    Text(
                        amPm, color = TextLo, fontFamily = Mono, fontSize = 13.sp,
                        modifier = Modifier.alignByBaseline().padding(start = 6.dp)
                    )
                }
            }
            battery?.let { (level, charging) -> BatteryReadout(level, charging) }
        }
    }
}

@Composable
fun ChronoRing(second: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = center
        val ringR = size.minDimension / 2f - 10.dp.toPx()
        val tickOuter = ringR - 12.dp.toPx()

        // Ambient glow
        drawCircle(
            Brush.radialGradient(listOf(Cyan.copy(alpha = 0.12f), Color.Transparent), c, ringR * 1.2f),
            ringR * 1.2f, c
        )

        // 60 ticks, lit up to the current second
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90.0)
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
            val major = i % 5 == 0
            val lit = i <= second
            val len = if (major) 9.dp.toPx() else 4.dp.toPx()
            drawLine(
                color = when {
                    lit && major -> Cyan
                    lit -> Cyan.copy(alpha = 0.45f)
                    major -> Color.White.copy(alpha = 0.28f)
                    else -> Color.White.copy(alpha = 0.10f)
                },
                start = c + dir * (tickOuter - len),
                end = c + dir * tickOuter,
                strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Track
        drawCircle(Color.White.copy(alpha = 0.07f), ringR, c, style = Stroke(1.5.dp.toPx()))

        // Seconds comet
        if (second > 0) {
            val sweep = second * 6f
            val frac = second / 60f
            val trail = Brush.sweepGradient(
                0f to Violet.copy(alpha = 0f),
                frac * 0.6f to Violet.copy(alpha = 0.7f),
                frac to Cyan,
                center = c
            )
            val topLeft = Offset(c.x - ringR, c.y - ringR)
            val arcSize = Size(ringR * 2, ringR * 2)
            rotate(-90f, c) {
                drawArc(trail, 0f, sweep, false, topLeft, arcSize, alpha = 0.25f, style = Stroke(10.dp.toPx()))
                drawArc(trail, 0f, sweep, false, topLeft, arcSize, style = Stroke(2.5.dp.toPx()))
            }
            val ha = Math.toRadians(sweep - 90.0)
            val head = c + Offset(cos(ha).toFloat(), sin(ha).toFloat()) * ringR
            drawCircle(Cyan.copy(alpha = 0.3f), 8.dp.toPx(), head)
            drawCircle(Color.White, 3.dp.toPx(), head)
        }
    }
}

@Composable
fun BatteryReadout(level: Int, charging: Boolean) {
    val color = when {
        charging -> Cyan
        level <= 15 -> Danger
        else -> TextHi.copy(alpha = 0.75f)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        repeat(10) { i ->
            Box(
                Modifier
                    .padding(horizontal = 1.dp)
                    .size(width = 4.dp, height = 10.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i < (level + 5) / 10) color else Color.White.copy(alpha = 0.12f))
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (charging) "$level% CHG" else "$level%",
            color = TextLo, fontFamily = Mono, fontSize = 11.sp
        )
    }
}

@Composable
fun Dock(apps: List<AppEntry>, showHint: Boolean, actions: AppActions) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(
                1.dp,
                Brush.horizontalGradient(
                    listOf(Cyan.copy(alpha = 0.5f), Color.White.copy(alpha = 0.06f), Violet.copy(alpha = 0.5f))
                ),
                shape
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (apps.isEmpty()) {
            if (showHint) Text("Long-press any app to pin it here", color = TextLo, fontFamily = Mono, fontSize = 11.sp)
        } else {
            apps.forEach { AppTile(it, actions, showLabel = false) }
        }
    }
}

// ---------- App tile + long-press menu ----------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTile(app: AppEntry, actions: AppActions, showLabel: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .then(if (showLabel) Modifier.fillMaxWidth() else Modifier)
                .clip(RoundedCornerShape(18.dp))
                .combinedClickable(
                    onClick = { actions.launch(app) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                )
                .padding(horizontal = 6.dp, vertical = if (showLabel) 10.dp else 4.dp)
        ) {
            Image(bitmap = app.icon, contentDescription = app.label, modifier = Modifier.size(50.dp))
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                Text(
                    app.label,
                    color = TextHi.copy(alpha = 0.88f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (actions.isPinned(app)) "Remove from dock" else "Pin to dock") },
                onClick = { menuOpen = false; actions.togglePin(app) }
            )
            DropdownMenuItem(text = { Text("App info") }, onClick = { menuOpen = false; actions.info(app) })
            DropdownMenuItem(text = { Text("Uninstall", color = Danger) }, onClick = { menuOpen = false; actions.uninstall(app) })
        }
    }
}

// ---------- App drawer ----------
@Composable
fun AppDrawer(
    apps: List<AppEntry>,
    query: String,
    onQuery: (String) -> Unit,
    actions: AppActions,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val close by rememberUpdatedState(onClose)
    val q = query.trim()
    val results = remember(apps, q) {
        if (q.isEmpty()) apps
        else apps.filter { it.label.contains(q, ignoreCase = true) }
            .sortedBy { !it.label.startsWith(q, ignoreCase = true) }
    }

    // Pull down at the top of the list to close
    val pullToClose = remember {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    pulled = if (available.y > 0f) pulled + available.y else 0f
                    if (pulled > 180f) { pulled = 0f; close() }
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                return Velocity.Zero
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.82f), Ink.copy(alpha = 0.95f))))
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > 64.dp.toPx()) close() },
                    onVerticalDrag = { change, dy -> change.consume(); total += dy }
                )
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f))
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Cyan))
            Spacer(Modifier.width(10.dp))
            Text("APPLICATIONS", color = TextHi, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            Text(
                results.size.toString().padStart(3, '0'),
                color = Cyan.copy(alpha = 0.8f), fontFamily = Mono, fontSize = 12.sp
            )
        }
        SearchField(query, onQuery, onGo = { results.firstOrNull()?.let(actions.launch) })
        Spacer(Modifier.height(8.dp))
        when {
            results.isNotEmpty() -> LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize().nestedScroll(pullToClose),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp)
            ) {
                items(results, key = { it.key }) { app -> AppTile(app, actions, showLabel = true) }
            }
            q.isNotEmpty() -> Column(
                Modifier.fillMaxWidth().padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("NO MATCH", color = TextLo, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 3.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Search the web for “$q”",
                    color = Cyan,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { webSearch(context, q) }
                        .padding(12.dp)
                )
            }
        }
    }
}

@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, onGo: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (query.isEmpty()) Color.White.copy(alpha = 0.10f) else Cyan.copy(alpha = 0.6f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Search, contentDescription = null,
            tint = if (query.isEmpty()) TextLo else Cyan, modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(color = TextHi, fontSize = 16.sp),
            cursorBrush = SolidColor(Cyan),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search apps", color = TextLo, fontSize = 16.sp)
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Filled.Close, contentDescription = "Clear", tint = TextLo,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { onQuery("") }
            )
        }
    }
}

// ---------- System helpers ----------
fun loadApps(context: Context): List<AppEntry> {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val iconPx = (56 * context.resources.displayMetrics.density).toInt()
    return launcherApps.getActivityList(null, Process.myUserHandle())
        .filter { it.componentName.packageName != context.packageName }
        .mapNotNull { info ->
            runCatching {
                AppEntry(
                    key = info.componentName.flattenToString(),
                    label = info.label.toString(),
                    packageName = info.componentName.packageName,
                    component = info.componentName,
                    icon = info.getIcon(0).toBitmap(iconPx, iconPx).asImageBitmap()
                )
            }.getOrNull()
        }
        .sortedBy { it.label.lowercase() }
}

fun defaultDock(context: Context, apps: List<AppEntry>): List<String> {
    val pm = context.packageManager
    val intents = listOf(
        Intent(Intent.ACTION_DIAL),
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://")),
        Intent(MediaStore.ACTION_IMAGE_CAPTURE)
    )
    return intents.mapNotNull { intent ->
        val pkg = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        apps.firstOrNull { it.packageName == pkg }?.key
    }.distinct()
}

fun launchApp(context: Context, app: AppEntry) {
    try {
        context.getSystemService(LauncherApps::class.java)
            .startMainActivity(app.component, Process.myUserHandle(), null, null)
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
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
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Set wallpaper"))
    }
}

fun webSearch(context: Context, q: String) {
    val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
    runCatching { context.startActivity(search) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
            )
        }
    }
}

fun readBattery(context: Context): Pair<Int, Boolean>? {
    val bm = context.getSystemService(BatteryManager::class.java) ?: return null
    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return if (level in 0..100) level to bm.isCharging else null
}
