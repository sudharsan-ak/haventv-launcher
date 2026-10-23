package com.aktvhome.launcher.ui

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aktvhome.launcher.data.AppEntry
import com.aktvhome.launcher.ui.theme.ThemeColors
import java.time.LocalTime
import java.time.format.DateTimeFormatter

fun clockText(now: LocalTime, h24: Boolean): String =
    now.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a"))

/**
 * Handles left/right/OK/back while [pkg] is in move mode. Returns true when the key was used.
 * Placed before [tvClickable] so it wins while moving.
 */
fun Modifier.moveKeys(state: HavenState, pkg: String, visible: List<String>): Modifier =
    onPreviewKeyEvent { e ->
        if (state.moving != pkg) return@onPreviewKeyEvent false
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.nativeKeyEvent.keyCode in MOVE_KEYS
        when (e.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { state.layoutStore.shiftApp(pkg, -1, visible); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { state.layoutStore.shiftApp(pkg, +1, visible); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> { state.moving = null; true }
            // Up/down are swallowed so focus can't wander off mid-move.
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> false
        }
    }

private val MOVE_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
)

/** 16:9 app card: the app's own TV banner, or its icon on a tinted glass tile. */
@Composable
fun AppCard(
    app: AppEntry,
    colors: ThemeColors,
    width: Dp,
    moving: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "cardScale")
    val shape = RoundedCornerShape(14.dp)
    val ring = when {
        moving -> colors.accent
        focused -> Color.White
        else -> colors.edge
    }
    Box(
        modifier
            .width(width)
            .height(width * 9f / 16f)
            .scale(scale)
            .shadow(if (focused) 18.dp else 0.dp, shape, ambientColor = app.color, spotColor = app.color)
            .clip(shape)
            .background(app.color.copy(alpha = 0.35f))
            .border(if (focused || moving) 3.dp else 1.dp, ring, shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvClickable(onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (app.banner != null) {
            Image(app.banner, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(app.icon, app.label, Modifier.size(width * 0.26f))
                Spacer(Modifier.height(4.dp))
                Text(app.label, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (moving) {
            Text(
                "◀  move  ▶", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.55f))
                    .fillMaxWidth().padding(vertical = 2.dp),
            )
        }
    }
}

/** Round glass gear at the top right that opens quick settings. */
@Composable
fun GearButton(colors: ThemeColors, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (focused) colors.pill else colors.glass)
            .border(1.dp, if (focused) colors.accent else colors.edge, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Settings, "Settings", tint = if (focused) colors.pillInk else colors.ink, modifier = Modifier.size(22.dp))
    }
}

/** A collection's icon on a small glass square. */
@Composable
fun IconBadge(icon: String, colors: ThemeColors, size: Dp = 30.dp, tint: Color = colors.ink) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(colors.glass)
            .border(1.dp, colors.edge, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(CollectionIcons.of(icon), null, tint = tint, modifier = Modifier.size(size * 0.62f))
    }
}

/** 16:9 glass card for one HDMI input, sized like an app card. */
@Composable
fun InputCard(
    label: String,
    colors: ThemeColors,
    width: Dp,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "inputScale")
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .width(width)
            .height(width * 9f / 16f)
            .scale(scale)
            .clip(shape)
            .background(if (focused) colors.pill else colors.glass)
            .border(if (focused) 3.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvClickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        val ink = if (focused) colors.pillInk else colors.ink
        Icon(CollectionIcons.of("hdmi"), null, tint = ink, modifier = Modifier.size(30.dp))
        Text(label, color = ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A focusable text pill used for nav, chips and dialog buttons. */
@Composable
fun Pill(
    text: String,
    colors: ThemeColors,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    icon: ImageVector? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> colors.pill
        selected -> colors.pill.copy(alpha = 0.22f)
        else -> colors.glass
    }
    val ink = if (focused) colors.pillInk else colors.ink
    Row(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (selected) colors.accent else colors.edge, RoundedCornerShape(50))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.size(18.dp))
        Text(text, color = ink, fontSize = 14.sp, fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
fun GlassDialog(colors: ThemeColors, title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.surface.copy(alpha = 0.97f))
                .border(1.dp, colors.edge, RoundedCornerShape(22.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** Menu shown when OK is held on an app. */
@Composable
fun AppMenuDialog(
    app: AppEntry,
    colors: ThemeColors,
    onMove: () -> Unit,
    onMoveTo: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    GlassDialog(colors, app.label, onDismiss) {
        Pill("Move", colors, modifier = Modifier.fillMaxWidth(), focusRequester = first, onClick = onMove)
        Pill("Move to collection…", colors, modifier = Modifier.fillMaxWidth(), onClick = onMoveTo)
        Pill("Hide", colors, modifier = Modifier.fillMaxWidth(), onClick = onHide)
        Pill("Cancel", colors, modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
fun CollectionPickerDialog(
    colors: ThemeColors,
    names: List<Pair<String, String>>,
    currentId: String?,
    onPick: (String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    GlassDialog(colors, "Move to", onDismiss) {
        Column(Modifier.height(280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEachIndexed { i, (id, name) ->
                Pill(
                    name, colors, selected = id == currentId, modifier = Modifier.fillMaxWidth(),
                    focusRequester = if (i == 0) first else null, onClick = { onPick(id) },
                )
            }
            Pill("+ New collection", colors, modifier = Modifier.fillMaxWidth(), onClick = onNew)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
fun TextInputDialog(
    colors: ThemeColors,
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    maxLength: Int = 40,
) {
    var value by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    GlassDialog(colors, title, onDismiss) {
        BasicTextField(
            value = value,
            onValueChange = { value = it.take(maxLength) },
            singleLine = true,
            textStyle = TextStyle(color = colors.ink, fontSize = 18.sp),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.glass)
                .border(2.dp, colors.accent, RoundedCornerShape(12.dp))
                .padding(12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Save", colors, onClick = { if (value.isNotBlank()) onSave(value) })
            Pill("Cancel", colors, onClick = onDismiss)
        }
    }
    LaunchedEffect(Unit) {
        runCatching { field.requestFocus() }
        keyboard?.show()
    }
}
