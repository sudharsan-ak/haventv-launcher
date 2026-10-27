package com.aktvhome.launcher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aktvhome.launcher.core.guard.ServiceStatus
import com.aktvhome.launcher.data.A11yService
import com.aktvhome.launcher.data.AccessibilityGuard
import com.aktvhome.launcher.data.PrivateDns
import com.aktvhome.launcher.ui.theme.ThemeColors
import kotlinx.coroutines.delay

/**
 * The Accessibility tab: every accessibility service on this TV with one toggle each. On means
 * Haven turns it on and keeps it on through restarts; off means Haven leaves it alone. Haven's own
 * Home button service is listed but kept by the Home button tab. Without the one-time setup's
 * grant the list is read-only.
 */
@Composable
fun AccessibilityPage(colors: ThemeColors, first: Modifier) {
    val context = LocalContext.current
    // Redraws when the watchdog changes something, and every few seconds for changes made elsewhere.
    val version = AccessibilityGuard.version
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            tick++
        }
    }
    val services = remember(version, tick) { AccessibilityGuard.services(context) }
    val canWrite = remember(tick) { PrivateDns.canWrite(context) }
    val other = remember(tick) { AccessibilityGuard.otherWatchdog(context) }

    SettingsStatusRow(
        "What this does",
        "Turn a service on here and Haven keeps it on, even when the TV drops it at a restart. Turn it off and Haven " +
            "leaves it alone. If one is switched off in the TV's own settings, Haven asks what you want.",
        colors, first, traversable = true,
    )
    if (!canWrite) {
        SettingsStatusRow(
            "One-time setup needed",
            "Haven can show these but not change them yet. Use Set up in About & data › This TV, or change them in the TV's own Settings › Accessibility.",
            colors, traversable = true,
        )
    }
    other?.let {
        SettingsStatusRow(
            "Another watchdog",
            "$it is installed and does the same job. Two watchdogs can undo each other's changes; keep only one of them.",
            colors, traversable = true,
        )
    }
    if (services.isEmpty()) {
        SettingsStatusRow("Services", "No accessibility services on this TV.", colors)
        return
    }
    services.forEach { s ->
        when {
            s.self -> SettingsStatusRow(
                s.label, "${if (s.status == ServiceStatus.KEPT_ON) "On" else "Off"} · Haven's Home button service, kept in TV & network › Home button",
                colors, traversable = true,
            )
            !canWrite -> SettingsStatusRow(s.label, statusText(s), colors, traversable = true)
            else -> SettingsToggleRow(s.label, statusText(s), s.status.isToggled, colors) {
                AccessibilityGuard.setKeep(context, s.id, !s.status.isToggled)
            }
        }
    }
}

private fun statusText(s: A11yService): String {
    val state = when (s.status) {
        ServiceStatus.KEPT_ON -> "On · Haven keeps it on"
        ServiceStatus.WAITING_FOR_APP -> "Off · Haven turns it on once its app loads"
        ServiceStatus.TURNING_ON -> "Off · Haven is turning it back on"
        ServiceStatus.SWITCHED_OFF_OUTSIDE -> "Off · switched off in the TV's settings"
        ServiceStatus.REMIND_AFTER_RESTART -> "Off · Haven will ask after the next restart"
        ServiceStatus.OFF -> "Off"
    }
    return if (s.app.isNotBlank() && s.app != s.label) "$state · ${s.app}" else state
}
