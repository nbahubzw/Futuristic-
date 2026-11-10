package com.orbit.launcher

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

data class NotifItem(
    val key: String,
    val pkg: String,
    val title: String,
    val text: String,
    val time: Long,
    val intent: PendingIntent?,
    val clearable: Boolean,
    val autoCancel: Boolean,
    val ongoing: Boolean
)

object NotificationStore {
    var items by mutableStateOf<List<NotifItem>>(emptyList())
}

class NotificationService : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        refresh()
    }

    override fun onListenerDisconnected() {
        instance = null
        NotificationStore.items = emptyList()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        NotificationStore.items = active
            .filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
            .mapNotNull { it.toItem() }
            .sortedByDescending { it.time }
    }

    companion object {
        var instance: NotificationService? = null
            private set
    }
}

private fun StatusBarNotification.toItem(): NotifItem? {
    val n = notification
    val extras = n.extras
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
    val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
    if (title.isEmpty() && text.isEmpty()) return null
    return NotifItem(
        key = key,
        pkg = packageName,
        title = title,
        text = text,
        time = postTime,
        intent = n.contentIntent,
        clearable = isClearable,
        autoCancel = (n.flags and Notification.FLAG_AUTO_CANCEL) != 0,
        ongoing = isOngoing
    )
}

fun isListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

fun openListenerSettings(context: Context) {
    val component = ComponentName(context, NotificationService::class.java).flattenToString()
    val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
    val opened = Build.VERSION.SDK_INT >= 30 && runCatching { context.startActivity(detail) }.isSuccess
    if (!opened) runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
}

fun openNotification(context: Context, item: NotifItem) {
    val pending = item.intent
    if (pending != null) {
        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
        runCatching { pending.send(context, 0, null, null, null, null, options) }
    } else {
        context.packageManager.getLaunchIntentForPackage(item.pkg)?.let { openIntent(context, it) }
    }
    if (item.autoCancel) NotificationService.instance?.cancelNotification(item.key)
}

fun relativeTime(time: Long): String {
    val minutes = (System.currentTimeMillis() - time).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        minutes < 48 * 60 -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
    }
}

// ---------- Notification Center ----------

@Composable
fun NotificationCenter(model: LauncherModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val p = LocalPalette.current
    val notifications = NotificationStore.items
    var granted by remember { mutableStateOf(isListenerEnabled(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            granted = isListenerEnabled(context)
            delay(1500)
        }
    }
    val now by rememberNow(1000L)
    val locale = Locale.getDefault()
    val dismiss by rememberUpdatedState(onDismiss)
    val pull = rememberPullDismiss(up = true, onDismiss = onDismiss)

    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { change, dy ->
                        total += dy
                        if (total < -60.dp.toPx()) {
                            total = 0f
                            change.consume()
                            dismiss()
                        }
                    }
                )
            }
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(36.dp))
        Text(
            now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale)),
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            now.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", locale)),
            color = Color.White,
            fontSize = 92.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-2).sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(28.dp))

        if (!granted) {
            AccessCard { openListenerSettings(context) }
        } else if (notifications.isEmpty()) {
            Text(
                "No Older Notifications",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Notifications",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(p.glass)
                        .clickable { NotificationService.instance?.cancelAllNotifications() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear all", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
            LazyColumn(
                Modifier.fillMaxSize().nestedScroll(pull),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(notifications, key = { it.key }) { n ->
                    NotificationCard(
                        item = n,
                        app = model.byPackage[n.pkg],
                        onOpen = {
                            onDismiss()
                            openNotification(context, n)
                        },
                        onSwipedAway = { NotificationService.instance?.cancelNotification(n.key) }
                    )
                }
            }
        }
    }
}

@Composable
fun NotificationCard(item: NotifItem, app: AppEntry?, onOpen: () -> Unit, onSwipedAway: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(1f) }

    Row(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
                translationX = offset.value
                alpha = 1f - (abs(offset.value) / width).coerceIn(0f, 1f) * 0.7f
            }
            .clip(RoundedCornerShape(22.dp))
            .background(p.card)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> scope.launch { offset.snapTo(offset.value + delta) } },
                onDragStopped = { velocity ->
                    val far = abs(offset.value) > width * 0.33f || abs(velocity) > 1800f
                    if (item.clearable && far) {
                        val direction = if (offset.value + velocity * 0.05f >= 0f) 1f else -1f
                        offset.animateTo(direction * width, tween(160))
                        onSwipedAway()
                    } else {
                        offset.animateTo(0f, spring(dampingRatio = 0.8f))
                    }
                }
            )
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (app != null) {
            Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(38.dp))
        } else {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(p.onCardDim.copy(alpha = 0.3f)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title.ifEmpty { app?.label.orEmpty() },
                    color = p.onCard,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(relativeTime(item.time), color = p.onCardDim, fontSize = 13.sp)
            }
            if (item.text.isNotEmpty()) {
                Text(
                    item.text,
                    color = p.onCard,
                    fontSize = 15.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
fun AccessCard(onEnable: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(p.card)
            .padding(18.dp)
    ) {
        Text("See your notifications here", color = p.onCard, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Allow notification access so Orbit can show, open and clear them.",
            color = p.onCardDim,
            fontSize = 15.sp
        )
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(IosBlue)
                .clickable(onClick = onEnable),
            contentAlignment = Alignment.Center
        ) {
            Text("Turn On", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
