package com.orbit.launcher

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// ---------- Long-press menu ----------

data class MenuAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

@Composable
fun ContextMenu(
    target: MenuTarget,
    model: LauncherModel,
    onDismiss: () -> Unit,
    onOpen: (AppEntry, Rect) -> Unit,
    onEdit: () -> Unit,
    onRemoveRequest: (AppEntry, Place) -> Unit
) {
    val context = LocalContext.current
    val p = LocalPalette.current
    val density = LocalDensity.current
    val app = target.app
    val b = target.bounds

    var shortcuts by remember(app.key) { mutableStateOf<List<Pair<ShortcutInfo, ImageBitmap?>>>(emptyList()) }
    LaunchedEffect(app.key) {
        shortcuts = withContext(Dispatchers.IO) {
            loadShortcuts(context, app).map { it to shortcutIcon(context, it) }
        }
    }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 420f)) }

    fun act(block: () -> Unit) {
        onDismiss()
        block()
    }

    val actions = buildList<MenuAction> {
        when (target.place) {
            Place.Home -> {
                add(MenuAction("Edit Home Screen", Icons.Filled.Edit) { act(onEdit) })
                if (model.dock.size < DOCK_SIZE) {
                    add(MenuAction("Add to Dock", Icons.Filled.KeyboardArrowDown) { act { model.moveToDock(app.key) } })
                }
                add(MenuAction("App Info", Icons.Filled.Info) { act { openAppInfo(context, app) } })
                add(MenuAction("Remove App", Icons.Filled.Delete, destructive = true) {
                    act { onRemoveRequest(app, Place.Home) }
                })
            }
            Place.Dock -> {
                add(MenuAction("Edit Home Screen", Icons.Filled.Edit) { act(onEdit) })
                add(MenuAction("Remove from Dock", Icons.Filled.KeyboardArrowUp) { act { model.removeFromDock(app.key) } })
                add(MenuAction("App Info", Icons.Filled.Info) { act { openAppInfo(context, app) } })
                add(MenuAction("Delete App", Icons.Filled.Delete, destructive = true) { act { uninstallApp(context, app) } })
            }
            Place.Library -> {
                if (!model.isOnHome(app.key) && !model.isInDock(app.key)) {
                    add(MenuAction("Add to Home Screen", Icons.Filled.Home) { act { model.addToHome(app.key) } })
                }
                add(MenuAction("App Info", Icons.Filled.Info) { act { openAppInfo(context, app) } })
                add(MenuAction("Delete App", Icons.Filled.Delete, destructive = true) { act { uninstallApp(context, app) } })
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().tap { onDismiss() }) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val below = b.center.y < screenH * 0.55f
        val alignLeft = b.center.x < screenW / 2f

        // The icon lifts above the blurred home screen
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            modifier = Modifier
                .offset { IntOffset(b.left.roundToInt(), b.top.roundToInt()) }
                .size(with(density) { b.width.toDp() }, with(density) { b.height.toDp() })
                .graphicsLayer {
                    val s = 1f + 0.07f * appear.value
                    scaleX = s
                    scaleY = s
                    shadowElevation = 20.dp.toPx() * appear.value.coerceIn(0f, 1f)
                    shape = RoundedCornerShape(23)
                }
                .tap { act { onOpen(app, b) } }
        )

        Layout(
            content = {
                Column(
                    Modifier
                        .width(250.dp)
                        .graphicsLayer {
                            val s = 0.6f + 0.4f * appear.value
                            scaleX = s
                            scaleY = s
                            alpha = appear.value.coerceIn(0f, 1f)
                            transformOrigin = TransformOrigin(if (alignLeft) 0f else 1f, if (below) 0f else 1f)
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.menu)
                        .tap { }
                ) {
                    shortcuts.forEachIndexed { i, (info, icon) ->
                        if (i > 0) Hairline()
                        MenuRow(info.shortLabel?.toString().orEmpty(), p.onCard, onClick = { act { startShortcut(context, info) } }) {
                            if (icon != null) {
                                Image(icon, contentDescription = null, modifier = Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)))
                            }
                        }
                    }
                    if (shortcuts.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth().height(8.dp).background(p.separator))
                    }
                    actions.forEachIndexed { i, a ->
                        if (i > 0) Hairline()
                        val color = if (a.destructive) IosRed else p.onCard
                        MenuRow(a.label, color, onClick = a.onClick) {
                            Icon(a.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        ) { measurables, cs ->
            val menu = measurables.first().measure(cs.copy(minWidth = 0, minHeight = 0))
            val margin = 12.dp.roundToPx()
            val topLimit = 48.dp.roundToPx()
            val gap = 12.dp.roundToPx()
            val rawY = if (below) b.bottom.roundToInt() + gap else b.top.roundToInt() - gap - menu.height
            val y = rawY.coerceIn(topLimit, maxOf(topLimit, cs.maxHeight - menu.height - margin))
            val rawX = if (alignLeft) b.left.roundToInt() else b.right.roundToInt() - menu.width
            val x = rawX.coerceIn(margin, maxOf(margin, cs.maxWidth - menu.width - margin))
            layout(cs.maxWidth, cs.maxHeight) { menu.place(x, y) }
        }
    }
}

@Composable
fun MenuRow(label: String, color: Color, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(if (pressed) p.separator else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = color, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
    }
}

// ---------- Spotlight ----------

@Composable
fun Spotlight(
    model: LauncherModel,
    badges: Map<String, Int>,
    onOpen: (AppEntry, Rect) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val p = LocalPalette.current
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { focus.requestFocus() }
    }
    val q = query.trim()
    val results = remember(model.apps, q) {
        if (q.isEmpty()) emptyList()
        else model.apps
            .filter { it.label.contains(q, ignoreCase = true) }
            .sortedWith(compareBy<AppEntry> { !it.label.startsWith(q, ignoreCase = true) }.thenBy { it.label.lowercase() })
    }
    val suggestions = remember(model.apps, model.recent) { model.suggestions() }
    val pull = rememberPullDismiss(up = true, onDismiss = onDismiss)
    val store = model.byPackage["com.android.vending"]

    Column(
        Modifier
            .fillMaxSize()
            .tap { onDismiss() }
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IosSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search",
                modifier = Modifier.weight(1f),
                focusRequester = focus,
                onGo = {
                    val top = results.firstOrNull()
                    if (top != null) onOpen(top, Rect.Zero) else if (q.isNotEmpty()) webSearch(context, q)
                }
            )
            Text(
                "Cancel",
                color = Color.White,
                fontSize = 17.sp,
                modifier = Modifier.padding(start = 12.dp).tap { onDismiss() }
            )
        }

        LazyColumn(
            Modifier.fillMaxSize().nestedScroll(pull),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)
        ) {
            if (q.isEmpty()) {
                if (suggestions.isNotEmpty()) {
                    item { SectionTitle("Suggestions") }
                    item { GlassPanel { IconGrid(suggestions, badges, onOpen) } }
                }
            } else {
                if (results.isNotEmpty()) {
                    item { SectionTitle("Top Hit") }
                    item { TopHit(results.first(), onOpen) }
                }
                if (results.size > 1) {
                    item { SectionTitle("Apps") }
                    item { GlassPanel { IconGrid(results.drop(1).take(8), badges, onOpen) } }
                }
                item { SectionTitle("Search") }
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(p.card)
                    ) {
                        ActionRow("Search the web for “$q”", onClick = { webSearch(context, q) }) { SearchGlyph() }
                        if (store != null) {
                            Box(Modifier.padding(start = 60.dp)) { Hairline() }
                            ActionRow("Search ${store.label}", onClick = {
                                openIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q))))
                            }) {
                                Image(bitmap = store.icon, contentDescription = null, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 8.dp)
    )
}

@Composable
fun GlassPanel(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(p.glass)
            .tap { }
            .padding(vertical = 12.dp, horizontal = 4.dp)
    ) { content() }
}

@Composable
fun IconGrid(apps: List<AppEntry>, badges: Map<String, Int>, onOpen: (AppEntry, Rect) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        apps.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { app ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AppIcon(app = app, place = Place.Library, onOpen = onOpen, iconSize = 56.dp, badge = badges[app.packageName] ?: 0)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun TopHit(app: AppEntry, onOpen: (AppEntry, Rect) -> Unit) {
    val p = LocalPalette.current
    val bounds = remember { BoundsRef() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.card)
            .tap { onOpen(app, bounds.rect) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp).onGloballyPositioned { bounds.rect = it.boundsInWindow() }
        )
        Spacer(Modifier.width(14.dp))
        Text(
            app.label,
            color = p.onCard,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ActionRow(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .tap(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(14.dp))
        Text(label, color = p.onCard, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SearchGlyph() {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(IosBlue),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

// ---------- App Library folder ----------

@Composable
fun FolderSheet(
    group: AppGroup,
    badges: Map<String, Int>,
    hiddenKey: String?,
    onOpen: (AppEntry, Rect) -> Unit,
    onMenu: (MenuTarget) -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .tap { onDismiss() }
            .statusBarsPadding()
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(64.dp))
        Text(group.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(p.glass)
                .tap { },
            contentPadding = PaddingValues(vertical = 20.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            gridItems(group.apps, key = { it.key }) { app ->
                Box(contentAlignment = Alignment.Center) {
                    AppIcon(
                        app = app,
                        place = Place.Library,
                        onOpen = onOpen,
                        iconSize = 54.dp,
                        badge = badges[app.packageName] ?: 0,
                        hidden = hiddenKey == app.key,
                        onMenu = onMenu
                    )
                }
            }
        }
    }
}

// ---------- Remove alert ----------

@Composable
fun RemoveAlert(
    app: AppEntry,
    place: Place,
    onDelete: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit
) {
    val p = LocalPalette.current
    val fromDock = place == Place.Dock
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            .tap { onCancel() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(270.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(p.menu)
                .tap { }
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Remove “${app.label}”?",
                    color = p.onCard,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (fromDock) "It will move back to your Home Screen." else "It will stay in your App Library.",
                    color = p.onCard,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
            Hairline()
            AlertButton("Delete App", IosRed, onClick = onDelete)
            Hairline()
            AlertButton(if (fromDock) "Remove from Dock" else "Remove from Home Screen", IosBlue, onClick = onRemove)
            Hairline()
            AlertButton("Cancel", IosBlue, bold = true, onClick = onCancel)
        }
    }
}

@Composable
fun AlertButton(text: String, color: Color, bold: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (pressed) p.separator else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = color, fontSize = 17.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}
