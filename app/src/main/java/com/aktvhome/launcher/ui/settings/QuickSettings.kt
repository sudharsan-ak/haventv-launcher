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
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speaker
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
 * behind changes straight away. TV tiles open the TV's own page for that setting (Sony's settings
 * can only be changed there).
 */
@Composable
fun QuickSettings(state: HavenState, colors: ThemeColors) {
    val store = state.settingsStore
    val s = store.settings
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    val close = { state.overlay = Overlay.NONE }
    val open = { intent: Intent -> openSystemPage(context, intent) }

    HavenOverlay(dim = 0.12f, onDismiss = close) {
        val panelShape = RoundedCornerShape(26.dp)
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 60.dp, end = 40.dp)
                .width(460.dp)
                .glassPanel(colors, panelShape, alpha = 0.66f)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("Haven · Preferences", colors)
                    Text("Quick settings", color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                CloseButton(colors, onClick = close)
            }

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
                QuickTile("Picture", brightnessText(context), Icons.Rounded.Image, colors) {
                    open(sony("com.sony.dtv.configsettings", "PictureSettingActivity", "com.sony.dtv.base.picturesettings"))
                }
                QuickTile("Sound", "Sound settings", Icons.Rounded.MusicNote, colors) {
                    open(sony("com.sony.dtv.configsettings", "SoundSettingActivity", "com.sony.dtv.base.soundsettings"))
                }
                QuickTile("Audio system", "Speakers & soundbar", Icons.Rounded.Speaker, colors) {
                    open(sony("com.sony.dtv.configsettings", "AudioSystemSettingsActivity", "com.sony.dtv.base.audiosystemsettings"))
                }
            }
            TileRow {
                QuickTile("Screen", "Wide mode & more", Icons.Rounded.AspectRatio, colors) {
                    open(sony("com.sony.dtv.configsettings", "ScreenSettingActivity", "com.sony.dtv.base.screensettings"))
                }
                QuickTile("Wi-Fi", wifiText(context), Icons.Rounded.Wifi, colors) {
                    open(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
                QuickTile("Sleep timer", "Sony timers", Icons.Rounded.Bedtime, colors) {
                    open(
                        Intent("com.sony.dtv.timers.intent.action.SLEEP_TIMER")
                            .setClassName("com.sony.dtv.timers", "com.sony.dtv.activity.TimersMainActivity"),
                    )
                }
            }

            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BarButton("All settings", colors, Modifier.weight(1f)) { state.overlay = Overlay.PERSONALIZE }
                BarButton("Android settings", colors, Modifier.weight(1f)) { open(Intent(Settings.ACTION_SETTINGS)) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
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

@Composable
private fun BarButton(label: String, colors: ThemeColors, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(colors, modifier.height(48.dp), onClick = onClick) { ink ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            Text("→", color = ink, fontSize = 15.sp)
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

/** Sony's picture Brightness shows as a percent of Android's screen brightness (102 of 255 = 40). */
private fun brightnessText(context: Context): String {
    val raw = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
        ?: return "Picture settings"
    return "Brightness ${Math.round(raw * 100 / 255f)}"
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
