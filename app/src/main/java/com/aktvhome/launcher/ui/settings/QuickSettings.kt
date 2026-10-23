package com.aktvhome.launcher.ui.settings

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.aktvhome.launcher.data.SonyControl
import com.aktvhome.launcher.HomeOverrideService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.EnergySavingsLeaf
import com.aktvhome.launcher.data.SleepTimer
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.DnsChoice
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Overlay
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.tvClickable

private inline fun <reified E : Enum<E>> E.next(): E = enumValues<E>().let { it[(ordinal + 1) % it.size] }

/**
 * The small card under the gear. Haven tiles step to the next value on OK, and the home screen
 * behind changes straight away. TV tiles open a page right here (‹ Back returns), read from and
 * changed through the TV's control service; Screen opens Sony's own page, since the control
 * service doesn't offer it.
 */
@Composable
fun QuickSettings(state: HavenState, colors: ThemeColors) {
    val store = state.settingsStore
    val s = store.settings
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    val close = { state.overlay = Overlay.NONE }
    val open = { intent: Intent -> openSystemPage(context, intent) }
    val tv = remember { TvSettings(state.sony) }
    var page by remember { mutableStateOf<TvPage?>(null) }
    // The tile each page was opened from, so Back lands on it again.
    val tileFocus = remember { TvPage.entries.associateWith { FocusRequester() } }
    val pageFirst = remember { FocusRequester() }
    val focusPending = remember { booleanArrayOf(false) }
    var lastPage by remember { mutableStateOf<TvPage?>(null) }
    var sleepEnds by remember { mutableStateOf(SleepTimer.endsAt(context)) }

    fun show(next: TvPage?) {
        if (next != null) lastPage = next
        if (next == null) sleepEnds = SleepTimer.endsAt(context)
        tv.problem = null
        focusPending[0] = true
        page = next
    }

    LaunchedEffect(Unit) { tv.load() }
    // A page opened before the TV answered gets its focus once the settings arrive.
    LaunchedEffect(tv.loaded) { if (tv.loaded && page != null) runCatching { pageFirst.requestFocus() } }

    HavenOverlay(dim = 0.12f, onDismiss = close) {
        val panelShape = RoundedCornerShape(26.dp)
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 60.dp, end = 40.dp)
                .width(460.dp)
                .glassPanel(colors, panelShape, alpha = 0.66f)
                .onPreviewKeyEvent { e ->
                    // Back on a page returns to the tiles instead of closing Quick settings.
                    if (page == null || e.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyUp) show(null)
                    true
                }
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val current = page
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (current != null) BarButton("‹ Back", colors, Modifier.width(96.dp)) { show(null) }
                Column(Modifier.weight(1f)) {
                    Eyebrow(if (current == null) "Haven · Preferences" else "Quick settings · TV", colors)
                    Text(current?.title ?: "Quick settings", color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                CloseButton(colors, onClick = close)
            }

            if (current != null) {
                TvPageBody(current, state, tv, colors, pageFirst)
            } else {
                val dns by rememberDnsView(state)
                val dnsOn = dns.active?.host != null || dns.active == DnsChoice.CUSTOM
                TileRow {
                    QuickTile("Theme", s.theme.label, Icons.Rounded.Palette, colors, first) {
                        store.update { it.copy(theme = it.theme.next()) }
                    }
                    QuickTile("Day & night", s.dayMode.label, Icons.Rounded.BrightnessAuto, colors) {
                        store.update { it.copy(dayMode = it.dayMode.next()) }
                    }
                    QuickTile("Motion", s.motion.label, Icons.Rounded.Animation, colors) {
                        store.update { it.copy(motion = it.motion.next()) }
                    }
                }
                TileRow {
                    QuickTile("Clock", if (s.clock24) "24-hour" else "12-hour", Icons.Rounded.Schedule, colors) {
                        store.update { it.copy(clock24 = !it.clock24) }
                    }
                    QuickTile("DNS", if (dnsOn) dns.label else "Off", Icons.Rounded.Shield, colors) { state.toggleDns() }
                    QuickTile(
                        "Wallpaper", state.wallpapers.credit.ifBlank { "Photo is off" }, Icons.Rounded.Wallpaper, colors,
                    ) { state.wallpapers.tick(force = true) }
                }

                Spacer(Modifier.height(2.dp))
                Eyebrow("TV", colors)
                TileRow {
                    QuickTile("Picture", tv.pictureSummary() ?: "Picture settings", Icons.Rounded.Image, colors, tileFocus[TvPage.PICTURE]) {
                        show(TvPage.PICTURE)
                    }
                    QuickTile("Sound", tv.soundSummary() ?: "Sound & output", Icons.Rounded.MusicNote, colors, tileFocus[TvPage.SOUND]) {
                        show(TvPage.SOUND)
                    }
                    QuickTile("Power saving", tv.saving?.let(::savingLabel) ?: "Energy", Icons.Rounded.EnergySavingsLeaf, colors, tileFocus[TvPage.SAVING]) {
                        show(TvPage.SAVING)
                    }
                }
                TileRow {
                    QuickTile("Screen", "Wide mode & more", Icons.Rounded.AspectRatio, colors) {
                        open(sony("com.sony.dtv.configsettings", "ScreenSettingActivity", "com.sony.dtv.base.screensettings"))
                    }
                    QuickTile("Wi-Fi", wifiText(context), Icons.Rounded.Wifi, colors, tileFocus[TvPage.WIFI]) {
                        show(TvPage.WIFI)
                    }
                    QuickTile("Sleep timer", sleepEnds?.let { "Off at ${timeText(it)}" } ?: "Off", Icons.Rounded.Bedtime, colors, tileFocus[TvPage.SLEEP]) {
                        show(TvPage.SLEEP)
                    }
                }

                Spacer(Modifier.height(2.dp))
                PowerArea(
                    state, colors,
                    onAllSettings = { state.overlay = Overlay.PERSONALIZE },
                    onAndroidSettings = { open(Intent(Settings.ACTION_SETTINGS)) },
                    onPoweringOff = close,
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // Same-frame focus move when switching between tiles and a page (no flash on another tile).
    SideEffect {
        if (focusPending[0]) {
            focusPending[0] = false
            val target = if (page != null) pageFirst else lastPage?.let { tileFocus[it] } ?: first
            runCatching { target.requestFocus() }
        }
    }
}

/** One line of three equal tiles. */
@Composable
private fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** Compact tile: icon on the left, name and current value on the right. */
@Composable
private fun RowScope.QuickTile(
    title: String,
    value: String,
    icon: ImageVector,
    colors: ThemeColors,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val ink = if (focused) colors.pillInk else colors.ink
    Row(
        Modifier
            .weight(1f)
            .height(58.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(shape)
            .background(if (focused) colors.pill else colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(18.dp))
        Column {
            Text(title, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = ink.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private enum class PowerStep { BAR, CHOOSE, CONFIRM_RESTART }

/**
 * The bottom bar: All settings, Android settings and a power button. Power swaps the bar in place
 * for Power off / Restart / Cancel (Restart asks once more). The first time, it pairs Haven with the
 * TV: Sony shows a PIN popup over Haven, and Haven's Home button service reads the PIN from it and
 * submits it (the popup takes focus and cancels the PIN when closed, so it can't be typed here).
 */
@Composable
private fun PowerArea(
    state: HavenState,
    colors: ThemeColors,
    onAllSettings: () -> Unit,
    onAndroidSettings: () -> Unit,
    onPoweringOff: () -> Unit,
) {
    val sony = state.sony
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(PowerStep.BAR) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val stepFocus = remember { FocusRequester() }
    val powerFocus = remember { FocusRequester() }
    // Set when the bar swaps rows; focus moves in the same frame (see SideEffect below).
    val focusPending = remember { booleanArrayOf(false) }

    fun go(next: PowerStep) {
        focusPending[0] = true
        step = next
    }

    suspend fun pair() {
        if (!HomeOverrideService.canReadPin) {
            message = "Pairing needs Haven's Home button service. Turn it on in All settings › TV & network › Home button."
            return
        }
        busy = true
        message = "Pairing with the TV…"
        when (sony.startPairing()) {
            SonyControl.Result.DONE -> { busy = false; message = null; go(PowerStep.CHOOSE); return }
            SonyControl.Result.FAILED -> {
                busy = false
                message = "Couldn't reach the TV's control service. Check Settings › Remote device settings › Control remotely is on."
                return
            }
            SonyControl.Result.NEEDS_PAIRING -> Unit
        }
        // Sony's popup is up now; read its PIN (it can take a moment to appear).
        var pin: String? = null
        repeat(25) {
            if (pin == null) {
                delay(400)
                pin = withContext(Dispatchers.Default) { HomeOverrideService.readSonyPin() }
            }
        }
        val found = pin
        val result = if (found != null) sony.submitPin(found) else null
        busy = false
        when (result) {
            SonyControl.Result.DONE -> {
                message = "Paired. Haven now shows in Remote device settings."
                go(PowerStep.CHOOSE)
            }
            null -> message = "Couldn't read the PIN from the TV's popup. Press Reject on it, then try ⏻ again."
            else -> message = "Pairing didn't finish. Press ⏻ to try again."
        }
    }

    fun send(action: suspend () -> SonyControl.Result, done: () -> Unit) {
        scope.launch {
            busy = true
            message = null
            val r = action()
            busy = false
            when (r) {
                SonyControl.Result.DONE -> done()
                SonyControl.Result.NEEDS_PAIRING -> pair()
                SonyControl.Result.FAILED -> message = "The TV didn't accept that. Try again."
            }
        }
    }

    Column(
        Modifier.onPreviewKeyEvent { e ->
            // Back steps out of the power choices instead of closing Quick settings.
            if (e.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK || step == PowerStep.BAR) return@onPreviewKeyEvent false
            if (e.type == KeyEventType.KeyUp) {
                message = null
                go(PowerStep.BAR)
            }
            true
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message?.let { Text(it, color = colors.ink, fontSize = 11.sp) }
        when (step) {
            PowerStep.BAR -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BarButton("All settings", colors, Modifier.weight(1f), onClick = onAllSettings)
                BarButton("Android settings", colors, Modifier.weight(1f), onClick = onAndroidSettings)
                BarButton(null, colors, Modifier.width(48.dp), Icons.Rounded.PowerSettingsNew, powerFocus) {
                    if (busy) return@BarButton
                    if (sony.paired) go(PowerStep.CHOOSE) else scope.launch { pair() }
                }
            }
            PowerStep.CHOOSE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BarButton("Power off", colors, Modifier.weight(1f), Icons.Rounded.PowerSettingsNew, stepFocus) {
                    send({ sony.powerOff() }) { onPoweringOff() }
                }
                BarButton("Restart", colors, Modifier.weight(1f), Icons.Rounded.RestartAlt) { go(PowerStep.CONFIRM_RESTART) }
                BarButton("Cancel", colors, Modifier.weight(1f)) { message = null; go(PowerStep.BAR) }
            }
            PowerStep.CONFIRM_RESTART -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Restart now?", color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                BarButton("Yes", colors, Modifier.width(96.dp), focusRequester = stepFocus) {
                    send({ sony.restart() }) { message = "Restarting…" }
                }
                BarButton("No", colors, Modifier.width(96.dp)) { go(PowerStep.CHOOSE) }
            }
        }
    }

    // Each step starts with focus on its first button; back on the bar, focus returns to power.
    // The button you pressed is gone in the new row, so focus moves in the same frame the row
    // changes; any later and Android parks it on the panel's first tile for a moment.
    SideEffect {
        if (focusPending[0]) {
            focusPending[0] = false
            runCatching { (if (step == PowerStep.BAR) powerFocus else stepFocus).requestFocus() }
        }
    }
}

/** Bar button: a label with an arrow, an icon with a label, or just an icon (the power button). */
@Composable
private fun BarButton(
    label: String?,
    colors: ThemeColors,
    modifier: Modifier,
    icon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val ink = if (focused) colors.pillInk else colors.ink
    // "All settings →" style for the settings links; everything else is centered.
    val arrow = icon == null && label != null && label.endsWith("settings")
    Row(
        modifier
            .height(48.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clip(shape)
            .background(if (focused) colors.pill else colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = if (label == null) 0.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (arrow) Arrangement.Start else Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.size(18.dp))
        if (label != null) {
            Text(
                label, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = if (arrow) Modifier.weight(1f) else Modifier,
            )
            if (arrow) Text("→", color = ink, fontSize = 15.sp)
        }
    }
}

/** A Sony settings page by its exported activity (with its action, as Sony's own menus send it). */
private fun sony(pkg: String, activity: String, action: String): Intent =
    Intent(action).setClassName(pkg, "$pkg.$activity")

/** Opens a TV settings page; falls back to the TV's main Settings if that page isn't there. */
private fun openSystemPage(context: Context, intent: Intent) {
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK
    runCatching { context.startActivity(intent.addFlags(flags)) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(flags)) } }
}

private fun wifiText(context: Context): String {
    val on = Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) != 0
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
    return when {
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Connected"
        on -> "On · not connected"
        else -> "Off"
    }
}
