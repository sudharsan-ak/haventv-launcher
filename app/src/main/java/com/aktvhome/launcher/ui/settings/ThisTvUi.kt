package com.aktvhome.launcher.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.CapState
import com.aktvhome.launcher.data.Capability
import com.aktvhome.launcher.data.Setup
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Overlay
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.theme.ThemeColors

/**
 * All settings › About & data › This TV: everything Haven can do on some TVs, and what this one
 * allows. Available, Set up (with what to do) or Not supported (with why), so nothing hidden
 * elsewhere is a mystery. "Check again" looks afresh, after changing a setting. The badges and the
 * setup strip take focus so the remote can move down the list and the page scrolls along.
 */
@Composable
fun ThisTvPage(state: HavenState, colors: ThemeColors, first: Modifier) {
    val ctx = LocalContext.current
    val device = state.device
    LaunchedEffect(Unit) { device.refresh() }
    val status = device.status
    val needs = status.values.count { it.state == CapState.NEEDS_SETUP }
    SettingsRow(
        "This TV",
        if (needs == 0) "Everything this TV allows is set up." else "$needs ${if (needs == 1) "thing needs" else "things need"} setting up. Anything this TV can't do is hidden from Haven.",
        colors,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (Setup.needed(status).isNotEmpty()) {
                Pill("Set up", colors, selected = true, modifier = first) { state.overlay = Overlay.SETUP }
                Pill("Check again", colors) { device.refresh() }
            } else {
                Pill("Check again", colors, modifier = first) { device.refresh() }
            }
        }
    }
    Capability.entries.forEach { c ->
        val st = status.getValue(c)
        val note = listOfNotNull(st.reason, st.fix).joinToString(" ")
        if (c == Capability.HOME_SERVICE && st.state == CapState.NEEDS_SETUP) {
            // The one item anyone can fix on the TV itself, without the one-time grant.
            SettingsActionRow(c.label, note, "Open Accessibility", colors) {
                openSystemPage(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } else {
            SettingsStatusRow(
                c.label,
                listOf(note, stateLabel(st.state)).filter { it.isNotBlank() }.joinToString(" · "),
                colors,
                traversable = true,
            )
        }
    }
    if (listOf(Capability.SECURE_SETTINGS, Capability.TV_LISTINGS, Capability.NOTIFICATIONS, Capability.FILES).any { device.state(it) == CapState.NEEDS_SETUP }) {
        var focused by remember { mutableStateOf(false) }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .border(1.dp, if (focused) colors.accent else Color.Transparent, RoundedCornerShape(16.dp))
                .onFocusChanged { focused = it.isFocused }
                .focusable(),
        ) {
            InfoStrip(
                "One-time setup",
                "Set up does these from the TV itself. Or from a computer or a phone ADB app:\n" +
                    Setup.computerCommands(ctx.packageName, Build.VERSION.SDK_INT).joinToString("\n"),
                colors,
            )
        }
    }
}

private fun stateLabel(state: CapState): String = when (state) {
    CapState.AVAILABLE -> "Available"
    CapState.NEEDS_SETUP -> "Set up"
    CapState.UNSUPPORTED -> "Not supported"
}

/**
 * "Available", "Set up" or "Not supported" as a small label. Not a button, but it takes focus (it
 * fills in) so the remote can step down the list.
 */
@Composable
internal fun StateBadge(state: CapState, colors: ThemeColors) {
    val (text, ring) = when (state) {
        CapState.AVAILABLE -> "Available" to colors.accent
        CapState.NEEDS_SETUP -> "Set up" to colors.ink
        CapState.UNSUPPORTED -> "Not supported" to colors.edge
    }
    val shape = RoundedCornerShape(50)
    var focused by remember { mutableStateOf(false) }
    Text(
        text, color = if (state == CapState.UNSUPPORTED) colors.inkSoft else colors.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(shape).background(if (focused) colors.glass else Color.Transparent)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else ring, shape)
            .onFocusChanged { focused = it.isFocused }.focusable()
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
