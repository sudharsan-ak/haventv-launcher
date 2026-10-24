package com.aktvhome.launcher.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.CapState
import com.aktvhome.launcher.data.Capability
import com.aktvhome.launcher.data.Setup
import com.aktvhome.launcher.data.SetupRunner.Phase
import com.aktvhome.launcher.data.SetupRunner.Route
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Overlay
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.tvClickable
import com.aktvhome.launcher.ui.theme.ThemeColors

/** "1 thing needs" / "3 things need". */
private fun thingsNeed(n: Int) = if (n == 1) "1 thing needs" else "$n things need"

/**
 * Offered once, the first time Haven's Home is up and something needs the one-time setup (never
 * on a TV that's already set up). Start opens the setup and begins; Later leaves it to the Finish
 * setup card in Quick settings and All settings › About & data › This TV.
 */
@Composable
fun SetupOffer(state: HavenState, colors: ThemeColors) {
    val setup = state.setup
    val needed = Setup.needed(state.device.status)
    if (setup.offered || needed.isEmpty() || state.overlay != Overlay.NONE || state.backup.offerRestore || state.wallpaperView) return
    val first = remember { FocusRequester() }
    HavenOverlay(dim = 0.5f, onDismiss = { setup.markOffered() }) {
        Column(
            Modifier.align(Alignment.Center).width(560.dp).glassPanel(colors, RoundedCornerShape(26.dp), alpha = 0.96f).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Eyebrow("Haven / Setup", colors)
            Text("Set up Haven for this TV?", color = colors.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "${thingsNeed(needed.size)} a one-time permission: ${needed.joinToString { it.label.lowercase() }}. " +
                    "Haven can set them up from the TV itself, usually in a minute.",
                color = colors.inkSoft, fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Start", colors, selected = true, focusRequester = first) {
                    setup.markOffered()
                    state.overlay = Overlay.SETUP
                    setup.start()
                }
                Pill("Later", colors) { setup.markOffered() }
            }
            Text("Later: it's in Quick settings, and in All settings › About & data › This TV.", color = colors.inkSoft, fontSize = 11.sp)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** The setup itself: what's needed, each route as it's tried, what to do if none works, and the log. */
@Composable
fun SetupWizard(state: HavenState, colors: ThemeColors) {
    val ctx = LocalContext.current
    val setup = state.setup
    val device = state.device
    val primary = remember { FocusRequester() }
    val close = {
        setup.reset()
        state.overlay = Overlay.NONE
    }
    val needed = Setup.needed(device.status)
    HavenOverlay(dim = 0.5f, onDismiss = close) {
        Column(
            Modifier.align(Alignment.Center).width(640.dp).heightIn(max = 480.dp)
                .glassPanel(colors, RoundedCornerShape(26.dp), alpha = 0.96f)
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Eyebrow("Haven / Setup", colors)
            Text("Set up Haven for this TV", color = colors.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Setup.FIXABLE.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.label, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        StateBadge(device.state(c), colors)
                    }
                }
            }

            when (setup.phase) {
                Phase.IDLE -> {
                    Body(
                        if (needed.isEmpty()) "Everything Haven can set up is done. Anything else is on This TV."
                        else "${thingsNeed(needed.size)} setting up. Haven tries root first, then this TV's own debugging, " +
                            "and only runs its fixed setup commands.",
                        colors,
                    )
                    Buttons {
                        Pill(if (needed.isEmpty()) "Run again" else "Start", colors, selected = true, focusRequester = primary) { setup.start() }
                        Pill("Close", colors, onClick = close)
                    }
                }
                Phase.WORKING -> {
                    Body(setup.message, colors)
                    Buttons { Pill("Close", colors, focusRequester = primary, onClick = close) }
                }
                Phase.ALLOW -> {
                    Body(
                        "The TV is asking whether to allow debugging. Tick \"Always allow from this computer\" " +
                            "(the computer is Haven), then choose Allow.",
                        colors,
                    )
                    Buttons { Pill("Close", colors, focusRequester = primary, onClick = close) }
                }
                Phase.NEEDS_DEBUGGING -> {
                    Body(setup.message, colors)
                    InfoStrip(
                        "Turn on this TV's debugging once",
                        "1. Settings › System › About: select Build (Android TV OS build) 7 times, until it says you're a developer.\n" +
                            "2. Settings › System › Developer options: turn on USB debugging (some TVs call it ADB debugging or Network debugging).\n" +
                            "3. Come back and choose Try again. You can turn it off again afterwards.",
                        colors,
                    )
                    Buttons {
                        Pill("Try again", colors, selected = true, focusRequester = primary) { setup.start() }
                        Pill("Open Developer options", colors) { openSystemPage(ctx, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                        Pill("Close", colors, onClick = close)
                    }
                    FocusStrip(
                        "Or from a computer",
                        "If this TV only offers Wireless debugging, Haven can't use it yet (a later update adds it). " +
                            "From a computer or a phone ADB app instead:\n" +
                            Setup.computerCommands(ctx.packageName, Build.VERSION.SDK_INT).joinToString("\n"),
                        colors,
                    )
                }
                Phase.DONE -> {
                    Body(if (needed.isEmpty()) "All set." else "Setup finished, but ${thingsNeed(needed.size).replace("need", "still need")} setting up.", colors)
                    if (setup.route == Route.ADB) {
                        Body("You can turn debugging off again in Developer options; Haven keeps what it was given.", colors)
                    }
                    if (device.state(Capability.HOME_SERVICE) == CapState.NEEDS_SETUP) {
                        InfoStrip(
                            "Home button service",
                            "Turn on Haven in Settings › Accessibility. If Android says the setting is restricted: " +
                                "Settings › Apps › Haven, open its menu and choose Allow restricted settings, then try again.",
                            colors,
                        )
                    }
                    Buttons {
                        Pill("Close", colors, selected = true, focusRequester = primary, onClick = close)
                        if (needed.isNotEmpty()) Pill("Try again", colors) { setup.start() }
                        if (device.state(Capability.HOME_SERVICE) == CapState.NEEDS_SETUP) {
                            Pill("Open Accessibility", colors) { openSystemPage(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        }
                    }
                }
            }

            if (setup.logLines.isNotEmpty()) {
                FocusStrip("Setup log", setup.logLines.takeLast(14).joinToString("\n"), colors, mono = true)
            }
        }
    }
    // Each step's main button takes focus as it appears.
    LaunchedEffect(setup.phase) { runCatching { primary.requestFocus() } }
}

/**
 * Quick settings' top card while the setup has something to do (gone once it doesn't); opens the
 * setup. [focus] makes it the first thing focused.
 */
@Composable
fun FinishSetupCard(state: HavenState, colors: ThemeColors, focus: FocusRequester) {
    val n = Setup.needed(state.device.status).size
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val ink = if (focused) colors.pillInk else colors.ink
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .focusRequester(focus)
            .clip(shape)
            .background(if (focused) colors.pill else colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = { state.overlay = Overlay.SETUP })
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Build, null, tint = colors.accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text("Finish setup", color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("${thingsNeed(n)} a one-time permission", color = ink.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1)
        }
        Text("›", color = ink, fontSize = 18.sp)
    }
}

@Composable
private fun Body(text: String, colors: ThemeColors) {
    if (text.isNotBlank()) Text(text, color = colors.inkSoft, fontSize = 14.sp)
}

@Composable
private fun Buttons(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** An [InfoStrip] the remote can land on, so a long page scrolls down to it. */
@Composable
private fun FocusStrip(title: String, body: String, colors: ThemeColors, mono: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.glass.copy(alpha = colors.glass.alpha * 0.6f))
            .border(1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(
            body, color = colors.inkSoft, fontSize = if (mono) 10.sp else 11.sp,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}
