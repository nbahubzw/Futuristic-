package com.orbit.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppLibrary(
    model: LauncherModel,
    active: Boolean,
    badges: Map<String, Int>,
    hiddenKey: String?,
    onOpen: (AppEntry, Rect) -> Unit,
    onMenu: (MenuTarget) -> Unit,
    onOpenGroup: (AppGroup) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val searching = focused || query.isNotEmpty()
    val groups = remember(model.apps, model.recent, model.home, model.dock) { model.groups() }
    val matches = remember(model.apps, query) {
        val q = query.trim()
        if (q.isEmpty()) model.apps else model.apps.filter { it.label.contains(q, ignoreCase = true) }
    }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    fun cancel() {
        query = ""
        focusManager.clearFocus()
    }

    LaunchedEffect(active) { if (!active && searching) cancel() }
    BackHandler(enabled = active && searching) { cancel() }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 12.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IosSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = "App Library",
                modifier = Modifier.weight(1f),
                onFocusChange = { focused = it },
                onGo = { matches.firstOrNull()?.let { onOpen(it, Rect.Zero) } }
            )
            AnimatedVisibility(visible = searching) {
                Text(
                    "Cancel",
                    color = Color.White,
                    fontSize = 17.sp,
                    modifier = Modifier.padding(start = 12.dp).tap { cancel() }
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        if (searching) {
            LibraryList(matches, navBottom, onOpen, onMenu)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = navBottom + 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(groups.chunked(2), key = { it.first().title }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { group ->
                            LibraryFolder(group, badges, hiddenKey, onOpen, onMenu, onOpenGroup, Modifier.weight(1f))
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryFolder(
    group: AppGroup,
    badges: Map<String, Int>,
    hiddenKey: String?,
    onOpen: (AppEntry, Rect) -> Unit,
    onMenu: (MenuTarget) -> Unit,
    onOpenGroup: (AppGroup) -> Unit,
    modifier: Modifier
) {
    val p = LocalPalette.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(26.dp))
                .background(p.glass)
                .padding(6.dp)
        ) {
            val gap = 2.dp
            val cell = (maxWidth - gap) / 2
            val apps = group.apps
            val overflow = apps.size > 4
            val big = if (overflow) apps.take(3) else apps.take(4)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                for (r in 0 until 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (c in 0 until 2) {
                            val i = r * 2 + c
                            Box(Modifier.size(cell), contentAlignment = Alignment.Center) {
                                if (i < big.size) {
                                    AppIcon(
                                        app = big[i],
                                        place = Place.Library,
                                        onOpen = onOpen,
                                        iconSize = cell - 14.dp,
                                        showLabel = false,
                                        badge = badges[big[i].packageName] ?: 0,
                                        hidden = hiddenKey == big[i].key,
                                        onMenu = onMenu
                                    )
                                } else if (i == 3 && overflow) {
                                    MiniCluster(apps.drop(3).take(4), cell - 14.dp) { onOpenGroup(group) }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(group.title, style = IconLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MiniCluster(apps: List<AppEntry>, size: Dp, onClick: () -> Unit) {
    val gap = size * 0.08f
    val mini = (size - gap) / 2
    Column(
        Modifier.size(size).tap(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        apps.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                pair.forEach { Image(bitmap = it.icon, contentDescription = null, modifier = Modifier.size(mini)) }
            }
        }
    }
}

@Composable
fun LibraryList(
    apps: List<AppEntry>,
    bottom: Dp,
    onOpen: (AppEntry, Rect) -> Unit,
    onMenu: (MenuTarget) -> Unit
) {
    val sections = remember(apps) {
        apps.groupBy { app ->
            val first = app.label.firstOrNull()?.uppercaseChar()
            if (first != null && first.isLetter()) first.toString() else "#"
        }.toSortedMap(compareBy<String> { if (it == "#") "\uFFFF" else it })
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottom + 24.dp)
    ) {
        sections.forEach { (letter, list) ->
            item(key = "section-$letter") {
                Text(
                    letter,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
                )
            }
            items(list, key = { it.key }) { app -> LibraryRow(app, onOpen, onMenu) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryRow(app: AppEntry, onOpen: (AppEntry, Rect) -> Unit, onMenu: (MenuTarget) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val bounds = remember { BoundsRef() }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMenu(MenuTarget(app, bounds.rect, Place.Library))
                    },
                    onClick = { onOpen(app, bounds.rect) }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                bitmap = app.icon,
                contentDescription = app.label,
                modifier = Modifier.size(40.dp).onGloballyPositioned { bounds.rect = it.boundsInWindow() }
            )
            Spacer(Modifier.width(14.dp))
            Text(app.label, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .padding(start = 54.dp)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Color.White.copy(alpha = 0.15f))
        )
    }
}
