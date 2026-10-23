@file:OptIn(ExperimentalLayoutApi::class) // FlowRow for live channels

package com.aktvhome.launcher.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.GameState
import com.aktvhome.launcher.data.Nba
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Overlay
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.settings.CloseButton
import com.aktvhome.launcher.ui.settings.Eyebrow
import com.aktvhome.launcher.ui.settings.GlassCard
import com.aktvhome.launcher.ui.settings.HavenOverlay
import com.aktvhome.launcher.ui.settings.glassPanel
import com.aktvhome.launcher.ui.theme.ThemeColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dayFmt = DateTimeFormatter.ofPattern("EEE d")

/** The glass Watch Now drawer on the right: NBA (team card, day picker, games), Continue Watching, Live Channels. */
@Composable
fun WatchDrawer(state: HavenState, colors: ThemeColors) {
    val s = state.settingsStore.settings
    val w = state.watchNow
    val close = { state.overlay = Overlay.NONE }
    val first = remember { FocusRequester() }
    var day by remember { mutableStateOf(w.today()) }
    val days = remember { DayFocus() }
    var picking by remember { mutableStateOf(false) }
    var firstUsed = false
    fun claim(): FocusRequester? = if (!firstUsed) { firstUsed = true; first } else null

    LaunchedEffect(Unit) { w.tick(force = true) }
    LaunchedEffect(day, s.watchNba) { if (s.watchNba) w.loadDay(day) }

    HavenOverlay(dim = 0.35f, onDismiss = close) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(vertical = 24.dp, horizontal = 28.dp)
                .width(540.dp)
                .fillMaxHeight()
                .glassPanel(colors, RoundedCornerShape(28.dp), alpha = 0.9f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("Haven / Watch now", colors)
                    Text("Watch now", color = colors.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                CloseButton(colors, onClick = close)
            }

          for (section in s.watchOrder) when (section) {
            "nba" -> if (s.watchNba) {
                val fav = s.favoriteTeam
                if (fav.isNotBlank()) {
                    TeamCard(state, colors, fav, claim())
                }
                Section("NBA", colors)
                WeekStrip(state, colors, day, claim(), days) { day = it }
                val list = w.games[day]
                when {
                    list == null && w.nbaError -> Note("Couldn't reach ESPN. Check the connection and try again.", colors)
                    list == null -> Note("Loading games…", colors)
                    list.isEmpty() -> Note("No NBA games on ${if (day == w.today()) "today" else day.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))}.", colors)
                    else -> list.forEachIndexed { i, g ->
                        GameRow(g, fav, colors, modifier = if (i == 0) upTo(days.of(day)) else Modifier) { state.detailGameId = g.espnId }
                    }
                }
            }

            "continue" -> if (s.watchContinue) {
                Section("Continue watching", colors)
                ContinueList(state, colors, claim())
            }

            "live" -> if (s.watchLive) {
                Section("Live channels", colors)
                LiveGrid(state, colors, claim())
            }

          }

            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(if (s.watchNba) "Scores from ESPN; games open in the NBA app." else null, "Items open in the app that shared them.").joinToString(" "),
                color = colors.inkSoft, fontSize = 10.sp,
            )
        }
    }

    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun TeamCard(state: HavenState, colors: ThemeColors, fav: String, focus: FocusRequester?) {
    val w = state.watchNow
    val next = w.nextFavorite()
    val last = w.lastFavorite()
    GlassCard(colors, Modifier.fillMaxWidth(), focusRequester = focus, onClick = { state.scheduleOpen = true }) { ink ->
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Nba.teamName(fav), color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Full schedule ›", color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                next?.let { "Next: ${shortDate(it)} · ${opponent(it, fav)} · ${if (it.state == GameState.LIVE) "LIVE ${it.status}" else it.status}${it.network?.let { n -> " · $n" } ?: ""}" }
                    ?: if (w.schedule.isEmpty()) "Loading schedule…" else "No upcoming games",
                color = ink.copy(alpha = 0.85f), fontSize = 12.sp,
            )
            last?.let { Text("Last: ${result(it, fav)} ${opponent(it, fav)} · ${shortDate(it)}", color = ink.copy(alpha = 0.7f), fontSize = 12.sp) }
        }
    }
}

@Composable
fun GameRow(g: Game, fav: String, colors: ThemeColors, focus: FocusRequester? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassCard(colors, modifier.fillMaxWidth(), selected = g.involves(fav), focusRequester = focus, onClick = onClick) { ink ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TeamLine(g.away.abbr, g.away.name, if (g.state == GameState.PRE) "" else g.away.score, g.away.color, ink)
                TeamLine(g.home.abbr, g.home.name, if (g.state == GameState.PRE) "" else g.home.score, g.home.color, ink)
            }
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (g.state == GameState.LIVE) Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Color(0xFFE53935)))
                    Text(g.status, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                g.network?.let { Text(it, color = ink.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun TeamLine(abbr: String, name: String, score: String, color: String, ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(width = 4.dp, height = 16.dp).clip(RoundedCornerShape(2.dp)).background(teamColor(color)))
        Text("$abbr  $name", color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
        Text(score, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

fun teamColor(hex: String): Color = runCatching { Color(0xFF000000 or hex.toLong(16)) }.getOrDefault(Color(0xFF5B7BFF))

/** "vs MEM" at home, "@ PHX" away. */
fun opponent(g: Game, fav: String) = if (g.home.abbr == fav) "vs ${g.away.abbr}" else "@ ${g.home.abbr}"

/** "W 118–110" / "L 105–127" from the favorite's side. */
fun result(g: Game, fav: String): String {
    val mine = if (g.home.abbr == fav) g.home else g.away
    val theirs = if (g.home.abbr == fav) g.away else g.home
    val won = (mine.score.toIntOrNull() ?: 0) > (theirs.score.toIntOrNull() ?: 0)
    return "${if (won) "W" else "L"} ${mine.score}–${theirs.score}"
}

@Composable
private fun Section(title: String, colors: ThemeColors) {
    Text(title, color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Note(text: String, colors: ThemeColors) {
    Text(text, color = colors.inkSoft, fontSize = 12.sp)
}

/** Month grid for picking any date; past dates show final scores, future ones the schedule. */
@Composable
fun DatePickerDialog(colors: ThemeColors, current: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    var month by remember { mutableStateOf(current.withDayOfMonth(1)) }
    val first = remember { FocusRequester() }
    HavenOverlay(dim = 0.3f, onDismiss = onDismiss) {
        Column(
            Modifier.align(Alignment.Center).width(460.dp).glassPanel(colors, RoundedCornerShape(24.dp), alpha = 0.95f).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("‹", colors) { month = month.minusMonths(1) }
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Pill("›", colors) { month = month.plusMonths(1) }
            }
            Row { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { Text(it, color = colors.inkSoft, fontSize = 11.sp, modifier = Modifier.weight(1f)) } }
            val lead = month.dayOfWeek.value - 1
            val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.withDayOfMonth(it) }
            cells.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { d ->
                        if (d == null) Spacer(Modifier.weight(1f)) else {
                            Pill(
                                d.dayOfMonth.toString(), colors, selected = d == current,
                                modifier = Modifier.weight(1f),
                                focusRequester = if (d == current || (d.dayOfMonth == 1 && current.withDayOfMonth(1) != month)) first else null,
                            ) { onPick(d) }
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    LaunchedEffect(month) { runCatching { first.requestFocus() } }
}
