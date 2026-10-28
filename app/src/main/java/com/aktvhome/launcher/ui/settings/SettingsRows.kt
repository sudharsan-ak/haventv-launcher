package com.aktvhome.launcher.ui.settings

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.havenSetting
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.tvClickable

internal data class SettingSearchTarget(val settingId: String, val focus: FocusRequester)

internal val LocalSettingSearchTarget = compositionLocalOf<SettingSearchTarget?> { null }

@Composable
internal fun Modifier.settingSearchTarget(focusTitle: String): Modifier {
    val target = LocalSettingSearchTarget.current ?: return this
    return if (havenSetting(target.settingId)?.focusTitle == focusTitle) focusRequester(target.focus) else this
}

internal data class SettingsPickerOption(
    val value: String,
    val label: String = value,
    val note: String = "",
    val leading: (@Composable () -> Unit)? = null,
)

internal data class SettingsPickerRequest(
    val token: Int,
    val title: String,
    val options: List<SettingsPickerOption>,
    val selected: Set<String>,
    val multi: Boolean,
    val returnFocus: FocusRequester,
    val onChoose: (String) -> Unit,
)

internal class SettingsPickerController {
    private var nextToken = 0
    internal var request by mutableStateOf<SettingsPickerRequest?>(null)
        private set

    fun open(
        title: String,
        options: List<SettingsPickerOption>,
        selected: Set<String>,
        multi: Boolean,
        returnFocus: FocusRequester,
        onChoose: (String) -> Unit,
    ) {
        request = SettingsPickerRequest(++nextToken, title, options, selected, multi, returnFocus, onChoose)
    }

    fun toggle(value: String) {
        val current = request ?: return
        current.onChoose(value)
        request = if (current.multi) {
            current.copy(selected = if (value in current.selected) current.selected - value else current.selected + value)
        } else {
            null
        }
        if (!current.multi) runCatching { current.returnFocus.requestFocus() }
    }

    fun close() {
        val current = request ?: return
        request = null
        runCatching { current.returnFocus.requestFocus() }
    }
}

private val LocalSettingsPicker = compositionLocalOf<SettingsPickerController?> { null }

/** Hosts the right-side picker over the current settings content. */
@Composable
internal fun SettingsPickerHost(
    colors: ThemeColors,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val controller = remember { SettingsPickerController() }
    val active = controller.request
    var presented by remember { mutableStateOf<SettingsPickerRequest?>(null) }
    LaunchedEffect(active) { active?.let { presented = it } }
    CompositionLocalProvider(LocalSettingsPicker provides controller) {
        Box(modifier) {
            content()
            presented?.let { request ->
                SettingsPicker(request, active != null, controller, colors)
            }
        }
    }
}

@Composable
private fun SettingsPicker(
    request: SettingsPickerRequest,
    visible: Boolean,
    controller: SettingsPickerController,
    colors: ThemeColors,
) {
    val focusers = remember(request.token) { request.options.map { FocusRequester() } }
    val doneFocus = remember(request.token) { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) controller.close()
                true
            },
    ) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.16f)))
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            val shape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp)
            Column(
                Modifier
                    .width(340.dp)
                    .fillMaxHeight()
                    .glassPanel(colors, shape, alpha = 0.94f)
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Eyebrow("Choose", colors)
                Text(request.title, color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    request.options.forEachIndexed { index, option ->
                        val currentFocus = focusers[index]
                        PickerOptionRow(
                            option = option,
                            selected = option.value in request.selected,
                            showSaved = !request.multi,
                            colors = colors,
                            modifier = Modifier
                                .focusRequester(currentFocus)
                                .focusProperties {
                                    up = focusers.getOrNull(index - 1) ?: currentFocus
                                    down = focusers.getOrNull(index + 1)
                                        ?: if (request.multi) doneFocus else currentFocus
                                    left = currentFocus
                                    right = currentFocus
                                },
                        ) { controller.toggle(option.value) }
                    }
                }
                if (request.multi) {
                    Pill(
                        "Done",
                        colors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(doneFocus)
                            .focusProperties {
                                up = focusers.lastOrNull() ?: doneFocus
                                down = doneFocus
                                left = doneFocus
                                right = doneFocus
                            },
                    ) { controller.close() }
                }
            }
        }
    }
    LaunchedEffect(request.token, visible) {
        if (!visible) return@LaunchedEffect
        val selected = request.options.indexOfFirst { it.value in request.selected }.takeIf { it >= 0 } ?: 0
        runCatching { (focusers.getOrNull(selected) ?: doneFocus).requestFocus() }
    }
}

@Composable
private fun PickerOptionRow(
    option: SettingsPickerOption,
    selected: Boolean,
    showSaved: Boolean,
    colors: ThemeColors,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val ink = if (selected) colors.pillInk else colors.ink
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.pill else colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        option.leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(option.label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (option.note.isNotBlank()) {
                Text(option.note, color = ink.copy(alpha = 0.68f), fontSize = 10.sp)
            }
        }
        if (selected) {
            if (showSaved) {
                Text("Saved", color = ink.copy(alpha = 0.72f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Rounded.Check, "Selected", tint = ink, modifier = Modifier.width(18.dp))
        }
    }
}

@Composable
internal fun SettingsToggleRow(
    title: String,
    note: String,
    checked: Boolean,
    colors: ThemeColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onToggle: () -> Unit,
) {
    SettingsInteractiveRow(title, note, colors, modifier, enabled, onToggle, leading = leading) {
        Switch(checked, colors)
    }
}

@Composable
internal fun SettingsChoiceRow(
    title: String,
    note: String,
    current: String,
    options: List<SettingsPickerOption>,
    selected: String,
    colors: ThemeColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onChoose: (String) -> Unit,
) {
    val picker = LocalSettingsPicker.current
    val returnFocus = remember { FocusRequester() }
    SettingsInteractiveRow(
        title, note, colors, modifier.focusRequester(returnFocus), enabled,
        onClick = {
            picker?.open(title, options, setOf(selected), false, returnFocus, onChoose)
        },
    ) {
        Text(current, color = colors.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("›", color = colors.inkSoft, fontSize = 18.sp)
    }
}

@Composable
internal fun SettingsMultiChoiceRow(
    title: String,
    note: String,
    current: String,
    options: List<SettingsPickerOption>,
    selected: Set<String>,
    colors: ThemeColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onToggle: (String) -> Unit,
) {
    val picker = LocalSettingsPicker.current
    val returnFocus = remember { FocusRequester() }
    SettingsInteractiveRow(
        title, note, colors, modifier.focusRequester(returnFocus), enabled,
        onClick = {
            picker?.open(title, options, selected, true, returnFocus, onToggle)
        },
    ) {
        Text(current, color = colors.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("›", color = colors.inkSoft, fontSize = 18.sp)
    }
}

@Composable
internal fun SettingsActionRow(
    title: String,
    note: String,
    action: String,
    colors: ThemeColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SettingsInteractiveRow(title, note, colors, modifier, enabled, onClick) {
        Text(action, color = colors.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("→", color = colors.inkSoft, fontSize = 15.sp)
    }
}

@Composable
private fun SettingsInteractiveRow(
    title: String,
    note: String,
    colors: ThemeColors,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .settingSearchTarget(title)
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.42f)
            .focusProperties { canFocus = enabled }
            .clip(shape)
            .background(if (focused) colors.glass else colors.glass.copy(alpha = colors.glass.alpha * 0.78f))
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable { if (enabled) onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (note.isNotBlank()) Text(note, color = colors.inkSoft, fontSize = 10.sp)
        }
        trailing()
    }
}

@Composable
internal fun SettingsStatusRow(
    title: String,
    value: String,
    colors: ThemeColors,
    modifier: Modifier = Modifier,
    traversable: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.glass.copy(alpha = colors.glass.alpha * 0.62f))
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .then(
                if (traversable) Modifier.onFocusChanged { focused = it.isFocused }.focusable()
                else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = colors.inkSoft, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(130.dp))
        Text(value, color = colors.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}
