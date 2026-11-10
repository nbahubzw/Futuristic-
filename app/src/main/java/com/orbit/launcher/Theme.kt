package com.orbit.launcher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime

val IosBlue = Color(0xFF0A84FF)
val IosRed = Color(0xFFFF3B30)
val IosOrange = Color(0xFFFF9500)

data class Palette(
    val dark: Boolean,
    val widget: Color,
    val onWidget: Color,
    val onWidgetDim: Color,
    val card: Color,
    val onCard: Color,
    val onCardDim: Color,
    val menu: Color,
    val separator: Color,
    val glass: Color,
    val glassBorder: Color
)

val LightPalette = Palette(
    dark = false,
    widget = Color(0xFFFFFFFF),
    onWidget = Color(0xFF000000),
    onWidgetDim = Color(0xFF8E8E93),
    card = Color(0xE6FFFFFF),
    onCard = Color(0xFF000000),
    onCardDim = Color(0xFF6C6C70),
    menu = Color(0xF5F2F2F7),
    separator = Color(0x1F3C3C43),
    glass = Color(0x47FFFFFF),
    glassBorder = Color(0x33FFFFFF)
)

val DarkPalette = Palette(
    dark = true,
    widget = Color(0xFF1C1C1E),
    onWidget = Color(0xFFFFFFFF),
    onWidgetDim = Color(0xFF98989F),
    card = Color(0xE01C1C1E),
    onCard = Color(0xFFFFFFFF),
    onCardDim = Color(0xFF98989F),
    menu = Color(0xF52C2C2E),
    separator = Color(0x29FFFFFF),
    glass = Color(0x38FFFFFF),
    glassBorder = Color(0x26FFFFFF)
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

val IconLabelStyle = TextStyle(
    color = Color.White,
    fontSize = 12.sp,
    shadow = Shadow(color = Color(0x66000000), offset = Offset(0f, 1f), blurRadius = 4f)
)

@Composable
fun rememberNow(periodMs: Long): State<LocalDateTime> = produceState(LocalDateTime.now()) {
    while (true) {
        value = LocalDateTime.now()
        delay(periodMs - System.currentTimeMillis() % periodMs)
    }
}

@Composable
fun Modifier.wiggle(enabled: Boolean, seed: Int): Modifier {
    if (!enabled) return this
    val transition = rememberInfiniteTransition(label = "wiggle")
    val angle = transition.animateFloat(
        initialValue = -1.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 130, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset((seed and 0x7fffffff) % 130)
        ),
        label = "angle"
    )
    return this.graphicsLayer { rotationZ = angle.value }
}

@Composable
fun IosSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocusChange: (Boolean) -> Unit = {},
    onGo: () -> Unit = {}
) {
    val p = LocalPalette.current
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(p.card)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = p.onCardDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = p.onCard, fontSize = 17.sp),
            cursorBrush = SolidColor(IosBlue),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = Modifier
                .weight(1f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { onFocusChange(it.isFocused) },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(placeholder, color = p.onCardDim, fontSize = 17.sp)
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(p.onCardDim.copy(alpha = 0.6f))
                    .clickable { onQueryChange("") },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Clear", tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
fun Pill(text: String, bold: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(p.menu)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = p.onCard,
            fontSize = 15.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
fun Hairline() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(LocalPalette.current.separator))
}

// Closes a sheet when the user keeps pulling past the end of a list
@Composable
fun rememberPullDismiss(up: Boolean, onDismiss: () -> Unit): NestedScrollConnection {
    val dismiss by rememberUpdatedState(onDismiss)
    return remember(up) {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    val amount = if (up) -available.y else available.y
                    pulled = if (amount > 0f) pulled + amount else 0f
                    if (pulled > 160f) {
                        pulled = 0f
                        dismiss()
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                return Velocity.Zero
            }
        }
    }
}

class Ref<T>(var value: T?)

// Keeps showing the last non-null value while an exit animation plays
@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val ref = remember { Ref<T>(null) }
    if (value != null) ref.value = value
    return ref.value
}
