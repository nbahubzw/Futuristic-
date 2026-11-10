package com.orbit.launcher

import android.content.Intent
import android.provider.AlarmClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import java.time.format.TextStyle as DateStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private fun Offset.snap() = IntOffset(x.roundToInt(), y.roundToInt())

// Tap without the Android ripple
@Composable
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick
    )

// ---------- App icon ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIcon(
    app: AppEntry,
    place: Place,
    onOpen: (AppEntry, Rect) -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 60.dp,
    showLabel: Boolean = true,
    badge: Int = 0,
    editing: Boolean = false,
    hidden: Boolean = false,
    onMenu: ((MenuTarget) -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    val haptics = LocalHapticFeedback.current
    val bounds = remember { BoundsRef() }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed && !editing) 1f else 0f,
        animationSpec = tween(if (pressed) 80 else 220),
        label = "press"
    )
    val longPress: (() -> Unit)? = if (editing || onMenu == null) null else ({
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onMenu?.invoke(MenuTarget(app, bounds.rect, place))
    })

    Column(
        modifier.wiggle(editing, app.key.hashCode()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(iconSize + 14.dp)) {
            Image(
                bitmap = app.icon,
                contentDescription = app.label,
                colorFilter = if (press > 0f) {
                    ColorFilter.tint(Color.Black.copy(alpha = 0.28f * press), BlendMode.SrcAtop)
                } else null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(iconSize)
                    .onGloballyPositioned { bounds.rect = it.boundsInWindow() }
                    .graphicsLayer { alpha = if (hidden) 0f else 1f }
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onLongClick = longPress,
                        onClick = { if (!editing) onOpen(app, bounds.rect) }
                    )
            )
            if (badge > 0 && !editing) CountBadge(badge, Modifier.align(Alignment.TopEnd))
            if (editing && onRemove != null) MinusBadge(Modifier.align(Alignment.TopStart), onRemove)
        }
        if (showLabel) {
            Text(
                app.label,
                style = IconLabelStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(iconSize + 20.dp)
            )
        }
    }
}

@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(IosRed)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun MinusBadge(modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (p.dark) Color(0xF03A3A3C) else Color(0xF0D1D1D6))
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(width = 10.dp, height = 2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (p.dark) Color.White else Color.Black)
        )
    }
}

// ---------- Home page with drag-to-rearrange ----------

@Composable
fun HomePage(
    pageIndex: Int,
    keys: List<String>,
    model: LauncherModel,
    showWidgets: Boolean,
    editing: Boolean,
    badges: Map<String, Int>,
    hiddenKey: String?,
    onOpen: (AppEntry, Rect) -> Unit,
    onMenu: (MenuTarget) -> Unit,
    onRemove: (AppEntry, Place) -> Unit,
    onEditChange: (Boolean) -> Unit,
    onDragging: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val isEditing by rememberUpdatedState(editing)
    val setEditing by rememberUpdatedState(onEditChange)
    var order by remember(keys) { mutableStateOf(keys) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    val slotOffset = if (showWidgets) 8 else 0

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        if (!isEditing) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            setEditing(true)
                        }
                    },
                    onTap = { if (isEditing) setEditing(false) }
                )
            }
    ) {
        val cellWpx = constraints.maxWidth / COLUMNS.toFloat()
        val cellHpx = constraints.maxHeight / ROWS.toFloat()
        val cellW = maxWidth / COLUMNS
        val cellH = maxHeight / ROWS
        val iconSize = minOf(60.dp, cellH - 30.dp).coerceAtLeast(40.dp)
        fun slot(i: Int) = Offset((i % COLUMNS) * cellWpx, (i / COLUMNS) * cellHpx)

        if (showWidgets) {
            val widgetSize = minOf(cellW + iconSize, cellH * 2 - 26.dp)
            WidgetSlot("Clock", editing, widgetSize, Modifier.offset { slot(0).snap() }.size(cellW * 2, cellH * 2)) {
                ClockWidget(it)
            }
            WidgetSlot("Calendar", editing, widgetSize, Modifier.offset { slot(2).snap() }.size(cellW * 2, cellH * 2)) {
                CalendarWidget(it)
            }
        }

        order.forEachIndexed { index, k ->
            val app = model.byKey[k]
            if (app != null) {
                key(k) {
                    val target = slot(index + slotOffset)
                    val anim = remember { Animatable(target, Offset.VectorConverter) }
                    val dragged = dragKey == k
                    LaunchedEffect(target, dragged) {
                        if (!dragged) {
                            anim.animateTo(target, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
                        }
                    }
                    AppIcon(
                        app = app,
                        place = Place.Home,
                        onOpen = onOpen,
                        iconSize = iconSize,
                        badge = badges[app.packageName] ?: 0,
                        editing = editing,
                        hidden = hiddenKey == k,
                        onMenu = onMenu,
                        onRemove = { onRemove(app, Place.Home) },
                        modifier = Modifier
                            .offset { (if (dragged) dragPos else anim.value).snap() }
                            .zIndex(if (dragged) 1f else 0f)
                            .size(cellW, cellH)
                            .graphicsLayer {
                                val s = if (dragged) 1.1f else 1f
                                scaleX = s
                                scaleY = s
                            }
                            .pointerInput(editing) {
                                if (!editing) return@pointerInput
                                val finish = {
                                    scope.launch {
                                        anim.snapTo(dragPos)
                                        dragKey = null
                                        onDragging(false)
                                        model.reorderPage(pageIndex, order)
                                    }
                                }
                                detectDragGestures(
                                    onDragStart = {
                                        dragPos = anim.value
                                        dragKey = k
                                        onDragging(true)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragPos += amount
                                        val col = ((dragPos.x + cellWpx / 2) / cellWpx).toInt().coerceIn(0, COLUMNS - 1)
                                        val row = ((dragPos.y + cellHpx / 2) / cellHpx).toInt().coerceIn(0, ROWS - 1)
                                        val newIndex = (row * COLUMNS + col - slotOffset).coerceIn(0, order.size - 1)
                                        val current = order.indexOf(k)
                                        if (current >= 0 && newIndex != current) {
                                            order = order.toMutableList().apply {
                                                removeAt(current)
                                                add(newIndex, k)
                                            }
                                        }
                                    },
                                    onDragEnd = { finish() },
                                    onDragCancel = { finish() }
                                )
                            }
                    )
                }
            }
        }
    }
}

@Composable
fun WidgetSlot(
    label: String,
    editing: Boolean,
    size: Dp,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit
) {
    Column(
        modifier.wiggle(editing, label.hashCode()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        content(Modifier.size(size))
        Spacer(Modifier.height(6.dp))
        Text(label, style = IconLabelStyle)
    }
}

// ---------- Widgets ----------

@Composable
fun ClockWidget(modifier: Modifier) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val now by rememberNow(1000L)
    val measurer = rememberTextMeasurer()
    val face = if (p.dark) Color.Black else Color.White
    val ink = if (p.dark) Color.White else Color.Black

    Canvas(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(p.widget)
            .tap { openIntent(context, Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
    ) {
        val c = center
        val r = size.minDimension / 2f - 8.dp.toPx()
        drawCircle(face, r, c)

        for (i in 0 until 60) {
            if (i % 5 == 0) continue
            val a = Math.toRadians(i * 6.0)
            val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
            drawLine(p.onWidgetDim.copy(alpha = 0.5f), c + dir * (r * 0.90f), c + dir * (r * 0.95f), 1.dp.toPx())
        }

        val numberStyle = TextStyle(color = ink, fontSize = (r / 5.2f).toSp(), fontWeight = FontWeight.Medium)
        for (h in 1..12) {
            val a = Math.toRadians(h * 30.0)
            val pos = c + Offset(sin(a).toFloat(), -cos(a).toFloat()) * (r * 0.74f)
            val layout = measurer.measure(h.toString(), numberStyle)
            drawText(layout, topLeft = pos - Offset(layout.size.width / 2f, layout.size.height / 2f))
        }

        fun hand(degrees: Float, length: Float, width: Float, color: Color, tail: Float = 0f) {
            val a = Math.toRadians(degrees.toDouble())
            val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
            drawLine(color, c - dir * tail, c + dir * length, width, StrokeCap.Round)
        }

        val hours = (now.hour % 12) + now.minute / 60f
        val minutes = now.minute + now.second / 60f
        hand(hours * 30f, r * 0.48f, 4.5.dp.toPx(), ink)
        hand(minutes * 6f, r * 0.72f, 3.dp.toPx(), ink)
        drawCircle(ink, 4.dp.toPx(), c)
        hand(now.second * 6f, r * 0.82f, 1.2.dp.toPx(), IosOrange, tail = r * 0.15f)
        drawCircle(IosOrange, 2.5.dp.toPx(), c)
        drawCircle(face, 1.dp.toPx(), c)
    }
}

@Composable
fun CalendarWidget(modifier: Modifier) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val now by rememberNow(60_000L)
    val today = now.toLocalDate()
    val locale = Locale.getDefault()
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    val lead = (today.withDayOfMonth(1).dayOfWeek.value - firstDay.value + 7) % 7
    val length = today.lengthOfMonth()
    val rows = (lead + length + 6) / 7

    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(p.widget)
            .tap {
                openIntent(context, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
            }
            .padding(start = 9.dp, end = 9.dp, top = 12.dp, bottom = 8.dp)
    ) {
        Text(
            today.month.getDisplayName(DateStyle.FULL, locale).uppercase(locale),
            color = IosRed,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 3.dp)
        )
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                Text(
                    firstDay.plus(i.toLong()).getDisplayName(DateStyle.NARROW, locale),
                    color = p.onWidgetDim,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (col in 0 until 7) {
                    val day = r * 7 + col - lead + 1
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (day in 1..length) {
                            val isToday = day == today.dayOfMonth
                            if (isToday) Box(Modifier.size(16.dp).clip(CircleShape).background(IosRed))
                            Text(
                                day.toString(),
                                color = if (isToday) Color.White else p.onWidget,
                                fontSize = 9.5.sp,
                                lineHeight = 11.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- Dock and page indicator ----------

@Composable
fun Dock(
    model: LauncherModel,
    editing: Boolean,
    badges: Map<String, Int>,
    hiddenKey: String?,
    onOpen: (AppEntry, Rect) -> Unit,
    onMenu: (MenuTarget) -> Unit,
    onRemove: (AppEntry, Place) -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(34.dp)
    Row(
        modifier
            .padding(horizontal = 10.dp)
            .fillMaxWidth()
            .height(96.dp)
            .clip(shape)
            .background(p.glass)
            .border(0.5.dp, p.glassBorder, shape)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        model.dockApps.forEach { app ->
            key(app.key) {
                AppIcon(
                    app = app,
                    place = Place.Dock,
                    onOpen = onOpen,
                    showLabel = false,
                    badge = badges[app.packageName] ?: 0,
                    editing = editing,
                    hidden = hiddenKey == app.key,
                    onMenu = onMenu,
                    onRemove = { onRemove(app, Place.Dock) }
                )
            }
        }
    }
}

@Composable
fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    showDots: Boolean,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalPalette.current
    val dots by animateFloatAsState(if (showDots) 1f else 0f, tween(220), label = "dots")
    Box(modifier.height(30.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .graphicsLayer { alpha = 1f - dots }
                .clip(RoundedCornerShape(15.dp))
                .background(p.glass)
                .tap(enabled = !showDots, onClick = onSearch)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Search", color = Color.White, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
        }
        Row(
            Modifier.graphicsLayer { alpha = dots },
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            repeat(pageCount) { i ->
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (i == currentPage) 1f else 0.4f))
                )
            }
        }
    }
}
