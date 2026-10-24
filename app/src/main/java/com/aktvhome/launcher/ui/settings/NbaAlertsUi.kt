package com.aktvhome.launcher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MoreTime
import androidx.compose.material.icons.rounded.SportsBasketball
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.HomeOverrideService
import com.aktvhome.launcher.data.AlertEvent
import com.aktvhome.launcher.data.Nba
import com.aktvhome.launcher.data.NbaAlerts
import com.aktvhome.launcher.data.OTHER_EVENTS
import com.aktvhome.launcher.data.WatchNow
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.tvClickable
import com.aktvhome.launcher.ui.watch.NetImage
import kotlinx.coroutines.delay

/**
 * All settings › Watching & alerts › NBA alerts: a card for your teams and one for other games,
 * each with its switch and alert types, then the shared options, status and a test banner.
 */
@Composable
fun NbaAlertsPage(state: HavenState, colors: ThemeColors, first: Modifier) {
    val ctx = LocalContext.current
    val store = state.settingsStore
    val s = store.settings
    val w = state.watchNow
    // Status lines read live state; look again every few seconds.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); tick++ } }
    var testNote by remember { mutableStateOf("Shows a sample banner in 10 s. Switch to Netflix or YouTube TV to see it there.") }

    AlertCard(
        "Your team", "Get alerts for your favorite team.", s.alertsMine, w, colors, first,
        onSwitch = { store.update { it.copy(alertsMine = !it.alertsMine) } },
        extra = {
            SettingsMultiChoiceRow(
                "Teams you follow", "", teamsLabel(s.followedTeams, s.favoriteTeam),
                Nba.TEAMS.map { (abbr, name) ->
                    SettingsPickerOption(abbr, name, leading = { NetImage(w, Nba.logo(abbr), 24.dp) })
                },
                s.followedTeams, colors,
            ) { abbr -> store.update { it.copy(followedTeams = it.followedTeams.toggle(abbr)) } }
        },
    ) {
        EventTiles(AlertEvent.entries, s.alertsFavorite, colors,
            onToggle = { e -> store.update { it.copy(alertsFavorite = it.alertsFavorite.toggle(e)) } },
            onAll = { store.update { it.copy(alertsFavorite = AlertEvent.entries.toSet()) } },
        )
    }
    AlertCard(
        "Other games", "Get alerts for important games and big moments.", s.alertsOthers, w, colors, Modifier,
        onSwitch = { store.update { it.copy(alertsOthers = !it.alertsOthers) } },
    ) {
        val events = AlertEvent.entries.filter { it in OTHER_EVENTS }
        EventTiles(events, s.alertsOther, colors,
            onToggle = { e -> store.update { it.copy(alertsOther = it.alertsOther.toggle(e)) } },
            onAll = { store.update { it.copy(alertsOther = events.toSet()) } },
        )
    }

    SettingsToggleRow("Hide scores", "Banners say what's happening (\"Close game · tune in\"), never the score or result.", s.hideScores, colors) {
        store.update { it.copy(hideScores = !it.hideScores) }
    }
    SettingsChoiceRow(
        "Space out other-game alerts",
        "At most one per this long. Overtime and the close-game escalation always show; followed teams and games with Set alert are never held back.",
        "${s.alertCooldown} min", listOf("5 min", "10 min", "15 min", "30 min").map { SettingsPickerOption(it) },
        "${s.alertCooldown} min", colors,
    ) { v ->
        store.update { it.copy(alertCooldown = v.removeSuffix(" min").toInt()) }
    }
    SettingsChoiceRow(
        "Banner time", "How long a banner stays up when you don't press anything.", "${s.bannerSeconds} s",
        listOf("5 s", "8 s", "10 s").map { SettingsPickerOption(it) }, "${s.bannerSeconds} s", colors,
    ) { v ->
        store.update { it.copy(bannerSeconds = v.removeSuffix(" s").toInt()) }
    }
    SettingsToggleRow("Last night recap", "A catch-up on Haven's Home each morning", s.lastNightRecap, colors) {
        store.update { it.copy(lastNightRecap = !it.lastNightRecap) }
    }
    SettingsActionRow("Send test alert", testNote, "Send", colors) {
        testNote = if (HomeOverrideService.sendTestAlert()) "Sent. The banner shows in 10 s, over whatever's on."
        else "Needs the Home button service. Turn it on in TV & network › Home button."
    }
    val access = remember(tick) { NbaAlerts.canSeePlaying(ctx) }
    val updated = remember(tick) { NbaAlerts.lastUpdate(ctx) }
    val service = remember(tick) { HomeOverrideService.connected }
    SettingsStatusRow(
        "Status",
        listOf(
            "Notification access: " + if (access) "granted" else "not granted",
            "Home button service: " + if (service) "connected" else "not connected",
            "Last ESPN update: " + if (updated == 0L) "not yet" else timeText(updated),
        ).joinToString("\n"),
        colors,
    )
    if (!access) SettingsStatusRow("One-time setup", "So alerts skip the game you're watching: ${NbaAlerts.GRANT_COMMAND}", colors)
    if (!service) SettingsStatusRow("Needs the Home button service", "Alerts run in it. Turn it on in TV & network › Home button.", colors)

}
/** "Golden State Warriors" for one team, "Warriors + 2 more" for several (your main team named first). */
private fun teamsLabel(teams: Set<String>, main: String): String {
    if (teams.isEmpty()) return "Pick teams"
    if (teams.size == 1) return Nba.teamName(teams.first())
    val lead = if (main in teams) main else teams.minBy { Nba.teamName(it) }
    return "${Nba.teamName(lead).substringAfterLast(' ')} + ${teams.size - 1} more"
}

private fun <T> Set<T>.toggle(e: T) = if (e in this) this - e else this + e

/** One of the two cards: NBA logo, title and subtitle, an optional pill, the switch, then [content]. */
@Composable
private fun AlertCard(
    title: String,
    subtitle: String,
    on: Boolean,
    watchNow: WatchNow,
    colors: ThemeColors,
    switchModifier: Modifier,
    onSwitch: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.glass.copy(alpha = colors.glass.alpha * 0.8f))
            .border(1.dp, colors.edge, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsToggleRow(title, subtitle, on, colors, switchModifier, onToggle = onSwitch)
        Column(
            Modifier.fillMaxWidth().alpha(if (on) 1f else 0.42f).focusProperties { canFocus = on },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            extra?.invoke()
            content()
        }
    }
}

/** The settings switch as a control of its own: focus rings it, OK flips it. */
@Composable
private fun SwitchButton(on: Boolean, colors: ThemeColors, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .border(2.dp, if (focused) colors.accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(3.dp),
    ) {
        Switch(on, colors)
    }
}

/** "Alert types (n)" with Select all, then a tile per event (icon, name, switch) in one row. */
@Composable
private fun EventTiles(
    events: List<AlertEvent>,
    on: Set<AlertEvent>,
    colors: ThemeColors,
    onToggle: (AlertEvent) -> Unit,
    onAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Alert types (${events.size})", color = colors.inkSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Pill("Select all", colors, selected = events.all { it in on }, onClick = onAll)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            events.forEach { e -> EventTile(e, e in on, colors, Modifier.weight(1f)) { onToggle(e) } }
        }
    }
}

@Composable
private fun EventTile(event: AlertEvent, on: Boolean, colors: ThemeColors, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val ink = if (focused) colors.pillInk else colors.ink
    Column(
        modifier
            .clip(shape)
            .background(if (focused) colors.pill else colors.glass)
            .border(if (focused || on) 2.dp else 1.dp, if (focused || on) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(event.icon(), null, tint = ink, modifier = Modifier.size(18.dp))
        Text(event.label, color = ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Switch(on, colors)
    }
}

private fun AlertEvent.icon(): ImageVector = when (this) {
    AlertEvent.TIP_OFF -> Icons.Rounded.SportsBasketball
    AlertEvent.HALFTIME -> Icons.Rounded.HourglassBottom
    AlertEvent.CLOSE_GAME -> Icons.Rounded.LocalFireDepartment
    AlertEvent.OVERTIME -> Icons.Rounded.MoreTime
    AlertEvent.COMEBACK -> Icons.AutoMirrored.Rounded.TrendingUp
    AlertEvent.FINAL -> Icons.Rounded.Flag
}
