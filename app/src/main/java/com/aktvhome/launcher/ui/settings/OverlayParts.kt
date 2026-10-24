package com.aktvhome.launcher.ui.settings

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.aktvhome.launcher.ui.CollectionIcons
import com.aktvhome.launcher.ui.GlassDialog
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.tvClickable

/**
 * Full-screen, see-through window for the settings overlays. The home screen stays live
 * behind it under a light tint ([dim]), so changes show up there as they're made. [blur] only
 * works on TVs that blur across windows (not this one); the home blurs itself instead (see
 * MainActivity). An overlay stacked on another uses [STACKED_DIM] and [STACKED_GLASS].
 */
@Composable
fun HavenOverlay(dim: Float, onDismiss: () -> Unit, blur: Int = 20, content: @Composable BoxScope.() -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(dim)
            window?.setWindowAnimations(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window?.let {
                    it.setBackgroundBlurRadius(blur)
                    it.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    it.attributes = it.attributes.apply { blurBehindRadius = blur }
                }
            }
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** Dim behind an overlay opened over another, so the one underneath doesn't compete with its text. */
const val STACKED_DIM = 0.55f

/** Glass opacity for an overlay opened over another. */
const val STACKED_GLASS = 0.85f

/** Translucent panel treatment shared by Quick Settings and Personalize. */
fun Modifier.glassPanel(
    colors: ThemeColors,
    shape: RoundedCornerShape,
    alpha: Float = 0.74f,
): Modifier {
    val neutralGlass = if (colors.ink.luminance() > 0.5f) Color(0xFF101419) else Color.White
    return this
        .clip(shape)
        .background(neutralGlass.copy(alpha = alpha))
        .background(
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.12f),
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.08f),
                ),
            ),
        )
        .border(1.dp, colors.ink.copy(alpha = 0.20f), shape)
}

/** Small spaced capitals used for eyebrows and breadcrumbs. */
@Composable
fun Eyebrow(text: String, colors: ThemeColors) {
    Text(text.uppercase(), color = colors.inkSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
}

/**
 * A glass card that can be focused and chosen. Used for choices, toggles and actions alike.
 * Focus paints it solid; [selected] gets an accent ring.
 */
@Composable
fun GlassCard(
    colors: ThemeColors,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: () -> Unit,
    content: @Composable (ink: Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(shape)
            .background(if (focused) colors.pill else colors.glass)
            .border(if (selected || focused) 2.dp else 1.dp, if (selected || focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(contentPadding),
    ) {
        content(if (focused) colors.pillInk else colors.ink)
    }
}

/** A choice card: icon, title, one line of explanation, and a check when chosen. */
@Composable
fun ChoiceCard(
    title: String,
    subtitle: String,
    icon: ImageVector?,
    chosen: Boolean,
    colors: ThemeColors,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    height: Dp = 128.dp,
    onClick: () -> Unit,
) {
    GlassCard(colors, modifier.height(height), selected = chosen, focusRequester = focusRequester, onClick = onClick) { ink ->
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.size(24.dp))
                Box(Modifier.weight(1f))
                if (chosen) Text("✓", color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Column {
                Text(title, color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(subtitle, color = ink.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A plain explanatory strip under a page's cards. */
@Composable
fun InfoStrip(title: String, body: String, colors: ThemeColors) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.glass.copy(alpha = colors.glass.alpha * 0.6f))
            .border(1.dp, colors.edge, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(body, color = colors.inkSoft, fontSize = 11.sp)
    }
}

/** Round close button for an overlay's corner. */
@Composable
fun CloseButton(colors: ThemeColors, modifier: Modifier = Modifier, onClick: () -> Unit) =
    RoundIconButton(Icons.Rounded.Close, "Close", colors, modifier, onClick)

/** Round back button, the close button's twin, for pages inside an overlay. */
@Composable
fun BackButton(colors: ThemeColors, modifier: Modifier = Modifier, onClick: () -> Unit) =
    RoundIconButton(Icons.Rounded.ChevronLeft, "Back", colors, modifier, onClick)

@Composable
private fun RoundIconButton(icon: ImageVector, description: String, colors: ThemeColors, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (focused) colors.pill else colors.glass)
            .border(1.dp, colors.edge, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (focused) colors.pillInk else colors.ink, modifier = Modifier.size(20.dp))
    }
}

/** Grid of every collection icon; the current one is ringed. */
@Composable
fun IconPickerDialog(colors: ThemeColors, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    GlassDialog(colors, "Choose an icon", onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CollectionIcons.all.chunked(6).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { c, (key, vector) ->
                        var focused by remember { mutableStateOf(false) }
                        val shape = RoundedCornerShape(12.dp)
                        Box(
                            Modifier
                                .then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier)
                                .size(44.dp)
                                .clip(shape)
                                .background(if (focused) colors.pill else colors.glass)
                                .border(if (key == current) 2.dp else 1.dp, if (key == current) colors.accent else colors.edge, shape)
                                .onFocusChanged { focused = it.isFocused }
                                .tvClickable { onPick(key) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(vector, key, tint = if (focused) colors.pillInk else colors.ink, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
