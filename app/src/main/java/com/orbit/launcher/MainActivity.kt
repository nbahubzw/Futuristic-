package com.orbit.launcher

import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val model by lazy { LauncherModel(applicationContext) }
    private var stopSignal by mutableIntStateOf(0)
    private var homeSignal by mutableIntStateOf(0)
    private var visible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette
            CompositionLocalProvider(LocalPalette provides palette) {
                MaterialTheme(
                    colorScheme = if (palette.dark) darkColorScheme(primary = IosBlue) else lightColorScheme(primary = IosBlue)
                ) {
                    LauncherRoot(model, stopSignal, homeSignal, ::setWallpaperBlur)
                }
            }
        }
    }

    // Real wallpaper blur on Android 12+ (falls back to a dim on older phones)
    private fun setWallpaperBlur(radius: Int) {
        if (Build.VERSION.SDK_INT >= 31) runCatching { window.setBackgroundBlurRadius(radius) }
    }

    override fun onResume() {
        super.onResume()
        visible = true
    }

    override fun onStop() {
        super.onStop()
        visible = false
        stopSignal++
    }

    // Home pressed while already on the home screen
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (visible) homeSignal++
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherRoot(model: LauncherModel, stopSignal: Int, homeSignal: Int, onWallpaperBlur: (Int) -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    val reload = remember { mutableIntStateOf(0) }
    LaunchedEffect(reload.intValue) { model.load() }
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

    var editing by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var spotlight by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<AppGroup?>(null) }
    var removing by remember { mutableStateOf<Pair<AppEntry, Place>?>(null) }

    val pages = model.pages
    val pagerState = rememberPagerState(pageCount = { model.pages.size + 1 })

    fun closeAll(): Boolean {
        val anyOpen = menu != null || spotlight || notifications || folder != null || removing != null || editing
        val assistantOpen = OrbitAssistant.dismiss()
        val tasksOpen = OrbitTasks.dismiss()
        val settingsOpen = OrbitSettings.dismiss()
        val chatOpen = OrbitChat.dismiss()
        menu = null
        spotlight = false
        notifications = false
        folder = null
        removing = null
        editing = false
        focusManager.clearFocus()
        return anyOpen || assistantOpen || tasksOpen || settingsOpen || chatOpen
    }

    LaunchedEffect(stopSignal) { if (stopSignal > 0) closeAll() }
    LaunchedEffect(homeSignal) {
        if (homeSignal > 0 && !closeAll() && pagerState.currentPage != 0) pagerState.animateScrollToPage(0)
    }

    BackHandler {
        when {
            removing != null -> removing = null
            menu != null -> menu = null
            folder != null -> folder = null
            spotlight -> {
                spotlight = false
                focusManager.clearFocus()
            }
            notifications -> notifications = false
            editing -> editing = false
            pagerState.currentPage != 0 -> scope.launch { pagerState.animateScrollToPage(0) }
            else -> {}
        }
    }

    val notificationItems = NotificationStore.items
    val badges = remember(notificationItems) {
        notificationItems.filter { !it.ongoing }.groupingBy { it.pkg }.eachCount()
    }

    val open: (AppEntry, Rect) -> Unit = { app, bounds ->
        model.recordLaunch(app.key)
        launchApp(context, app, bounds)
    }

    // 0 on the home pages, 1 on the App Library
    val libraryProgress by remember {
        derivedStateOf {
            val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
            (position - (pagerState.pageCount - 2)).coerceIn(0f, 1f)
        }
    }
    val overlayOpen = spotlight || notifications || folder != null || menu != null
    val overlay by animateFloatAsState(if (overlayOpen) 1f else 0f, tween(260), label = "overlay")
    LaunchedEffect(Unit) {
        snapshotFlow { (maxOf(overlay, libraryProgress) * 90f).roundToInt() }
            .collect { onWallpaperBlur(it) }
    }

    var showDots by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) {
            showDots = true
        } else {
            delay(900)
            showDots = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        // ----- Home layer -----
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 1f - 0.05f * overlay
                    scaleX = s
                    scaleY = s
                }
                .blur((22f * overlay).dp)
                .pointerInput(editing) {
                    if (editing) return@pointerInput
                    var startY = 0f
                    var total = 0f
                    var fired = false
                    detectVerticalDragGestures(
                        onDragStart = {
                            startY = it.y
                            total = 0f
                            fired = false
                        },
                        onVerticalDrag = { _, dy ->
                            total += dy
                            if (!fired && total > 56.dp.toPx() && pagerState.currentPage < model.pages.size) {
                                fired = true
                                // Pull from the very top for notifications, anywhere else for your tasks
                                if (startY < size.height * 0.22f) notifications = true else OrbitTasks.show(context)
                            }
                            // Swipe up also opens your tasks (search stays on the Search button)
                            if (!fired && total < -56.dp.toPx() && pagerState.currentPage < model.pages.size) {
                                fired = true
                                OrbitTasks.show(context)
                            }
                        }
                    )
                }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !dragging
            ) { page ->
                val keys = model.pages.getOrNull(page)
                if (keys != null) {
                    HomePage(
                        pageIndex = page,
                        keys = keys,
                        model = model,
                        showWidgets = page == 0,
                        editing = editing,
                        badges = badges,
                        hiddenKey = menu?.takeIf { it.place == Place.Home }?.app?.key,
                        onOpen = open,
                        onMenu = { menu = it },
                        onRemove = { app, place -> removing = app to place },
                        onEditChange = { editing = it },
                        onDragging = { dragging = it },
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(top = 44.dp)
                            .navigationBarsPadding()
                            .padding(bottom = 146.dp)
                    )
                } else {
                    AppLibrary(
                        model = model,
                        active = pagerState.currentPage == page,
                        badges = badges,
                        hiddenKey = menu?.takeIf { it.place == Place.Library }?.app?.key,
                        onOpen = open,
                        onMenu = { menu = it },
                        onOpenGroup = { folder = it }
                    )
                }
            }

            AnimatedVisibility(
                visible = editing,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Pill("Wallpaper") { openWallpaperPicker(context) }
                    Pill("Done", bold = true) { editing = false }
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = libraryProgress * 180.dp.toPx()
                        alpha = 1f - libraryProgress
                    }
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PageIndicator(
                    pageCount = pages.size,
                    currentPage = pagerState.currentPage,
                    showDots = showDots,
                    onSearch = { spotlight = true }
                )
                Spacer(Modifier.height(10.dp))
                Dock(
                    model = model,
                    editing = editing,
                    badges = badges,
                    hiddenKey = menu?.takeIf { it.place == Place.Dock }?.app?.key,
                    onOpen = open,
                    onMenu = { menu = it },
                    onRemove = { app, place -> removing = app to place }
                )
            }
        }

        // ----- Dim behind overlays -----
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (0.22f * overlay + 0.12f * libraryProgress).coerceIn(0f, 1f) }
                .background(Color.Black)
        )

        // ----- Overlays -----
        AnimatedVisibility(
            visible = notifications,
            enter = slideInVertically(tween(320)) { -it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(260)) { -it } + fadeOut(tween(200))
        ) {
            NotificationCenter(model = model, onDismiss = { notifications = false })
        }

        AnimatedVisibility(
            visible = spotlight,
            enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { -it / 12 },
            exit = fadeOut(tween(160))
        ) {
            Spotlight(
                model = model,
                badges = badges,
                onOpen = open,
                onDismiss = {
                    spotlight = false
                    focusManager.clearFocus()
                }
            )
        }

        val lastFolder = rememberLast(folder)
        AnimatedVisibility(
            visible = folder != null,
            enter = fadeIn(tween(200)) + scaleIn(tween(260), initialScale = 0.92f),
            exit = fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.92f)
        ) {
            lastFolder?.let { group ->
                FolderSheet(
                    group = group,
                    badges = badges,
                    hiddenKey = menu?.takeIf { m -> m.place == Place.Library }?.app?.key,
                    onOpen = open,
                    onMenu = { m -> menu = m },
                    onDismiss = { folder = null }
                )
            }
        }

        val lastMenu = rememberLast(menu)
        AnimatedVisibility(visible = menu != null, enter = fadeIn(tween(100)), exit = fadeOut(tween(160))) {
            lastMenu?.let { target ->
                ContextMenu(
                    target = target,
                    model = model,
                    onDismiss = { menu = null },
                    onOpen = open,
                    onEdit = { editing = true },
                    onRemoveRequest = { app, place -> removing = app to place }
                )
            }
        }

        val lastRemoving = rememberLast(removing)
        AnimatedVisibility(visible = removing != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            lastRemoving?.let { (app, place) ->
                RemoveAlert(
                    app = app,
                    place = place,
                    onDelete = {
                        removing = null
                        uninstallApp(context, app)
                    },
                    onRemove = {
                        removing = null
                        if (place == Place.Dock) model.removeFromDock(app.key) else model.removeFromHome(app.key)
                    },
                    onCancel = { removing = null }
                )
            }
        }
    }
}
