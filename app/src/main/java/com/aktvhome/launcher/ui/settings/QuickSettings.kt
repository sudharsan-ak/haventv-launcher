package com.aktvhome.launcher.ui.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.DnsChoice
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Overlay
import com.aktvhome.launcher.ui.theme.ThemeColors

private inline fun <reified E : Enum<E>> E.next(): E = enumValues<E>().let { it[(ordinal + 1) % it.size] }

/**
 * The small card under the gear. OK on a tile steps to the next value; the home screen
 * behind changes straight away.
 */
@Composable
fun QuickSettings(state: HavenState, colors: ThemeColors) {
    val store = state.settingsStore
    val s = store.settings
    val first = remember { FocusRequester() }
    val close = { state.overlay = Overlay.NONE }

    HavenOverlay(dim = 0.12f, onDismiss = close) {
        val panelShape = RoundedCornerShape(26.dp)
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 64.dp, end = 40.dp)
                .width(380.dp)
                .glassPanel(colors, panelShape, alpha = 0.66f)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("Haven · Preferences", colors)
                    Text("Quick settings", color = colors.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                CloseButton(colors, onClick = close)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickTile("Theme", s.theme.label, Icons.Rounded.Palette, colors, Modifier.weight(1f), first) {
                    store.update { it.copy(theme = it.theme.next()) }
                }
                QuickTile("Day & night", s.dayMode.label, Icons.Rounded.BrightnessAuto, colors, Modifier.weight(1f)) {
                    store.update { it.copy(dayMode = it.dayMode.next()) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickTile("Motion", s.motion.label, Icons.Rounded.Animation, colors, Modifier.weight(1f)) {
                    store.update { it.copy(motion = it.motion.next()) }
                }
                QuickTile("Clock", if (s.clock24) "24-hour" else "12-hour", Icons.Rounded.Schedule, colors, Modifier.weight(1f)) {
                    store.update { it.copy(clock24 = !it.clock24) }
                }
            }
            val dns by rememberDnsView(state)
            val dnsOn = dns.active?.host != null || dns.active == DnsChoice.CUSTOM
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassCard(colors, Modifier.weight(1f), onClick = { state.toggleDns() }) { ink ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Shield, null, tint = ink, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text("DNS · ${if (dnsOn) dns.label else "Off"}", color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(dns.status.text, color = ink.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                GlassCard(colors, Modifier.weight(1f), onClick = { state.wallpapers.tick(force = true) }) { ink ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Wallpaper, null, tint = ink, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Next wallpaper", color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(
                                state.wallpapers.credit.ifBlank { "Turn on Photo in All settings" },
                                color = ink.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            GlassCard(colors, Modifier.fillMaxWidth(), onClick = { state.overlay = Overlay.PERSONALIZE }) { ink ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("All settings", color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("→", color = ink, fontSize = 16.sp)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun QuickTile(
    title: String,
    value: String,
    icon: ImageVector,
    colors: ThemeColors,
    modifier: Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    GlassCard(colors, modifier.height(108.dp), focusRequester = focusRequester, onClick = onClick) { ink ->
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(colors.glass),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp)) }
            Column {
                Text(title, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(value, color = ink.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}
