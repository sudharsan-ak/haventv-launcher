@file:OptIn(ExperimentalLayoutApi::class) // FlowRow for the game's buttons

package com.aktvhome.launcher.ui.watch

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.GameState
import com.aktvhome.launcher.data.GameSummary
import com.aktvhome.launcher.data.Nba
import com.aktvhome.launcher.data.Play
import com.aktvhome.launcher.data.Team
import com.aktvhome.launcher.data.WatchNow
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.settings.CloseButton
import com.aktvhome.launcher.ui.settings.Eyebrow
import com.aktvhome.launcher.ui.settings.GlassCard
import com.aktvhome.launcher.ui.settings.HavenOverlay
import com.aktvhome.launcher.ui.settings.glassPanel
import com.aktvhome.launcher.ui.theme.ThemeColors
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class DetailTab(val label: String) { SUMMARY("Summary"), BOX("Box score"), PLAYS("Play-by-play"), TEAM("Team stats") }

/** Team stats as shown, each with the ESPN names it may come under. */
private val TEAM_STATS = listOf(
    "FG" to listOf("fieldGoalsMade-fieldGoalsAttempted", "FG"),
    "FG%" to listOf("fieldGoalPct", "Field Goal %", "FG%"),
    "3PT" to listOf("threePointFieldGoalsMade-threePointFieldGoalsAttempted", "3PT"),
    "3P%" to listOf("threePointFieldGoalPct", "Three Point %", "3P%"),
    "FT" to listOf("freeThrowsMade-freeThrowsAttempted", "FT"),
    "REB" to listOf("totalRebounds", "Rebounds", "REB"),
    "AST" to listOf("assists", "Assists", "AST"),
    "TO" to listOf("turnovers", "totalTurnovers", "Turnovers", "TO"),
    "STL" to listOf("steals", "Steals", "STL"),
    "BLK" to listOf("blocks", "Blocks", "BLK"),
)

private val BOX_COLUMNS = listOf("MIN", "PTS", "REB", "AST", "FG", "3PT")
private val clockFmt = DateTimeFormatter.ofPattern("h:mm a")

/** A game's summary, kept fresh while its details are open. */
private class SummaryFeed {
    var summary by mutableStateOf<GameSummary?>(null)
    var loaded by mutableStateOf(false)
    /** When the last fetch worked, and since when they've been failing (0 = they aren't). */
    var updatedAt by mutableLongStateOf(0L)
    var failingSince by mutableLongStateOf(0L)
}

/**
 * Game details for [id], always from the latest data: the summary (refreshed every 20 s while the
 * game is live, once more when it ends) or the scoreboard, whichever is newer. Score block,
 * watch / Game stats / Set alert, then Summary, Box score, Play-by-play and Team stats.
 * Refreshes update everything in place; the tab, scroll positions and focus stay.
 */
@Composable
fun GameDetail(state: HavenState, colors: ThemeColors, id: String) {
    val w = state.watchNow
    val close = { state.detailGameId = null }
    val feed = remember(id) { SummaryFeed() }
    val known = w.findGame(id)
    val fresh = feed.summary?.game
    // Whichever was fetched last wins for score and clock; names and logos come from the scoreboard.
    val game = when {
        fresh == null -> known?.first
        known == null -> fresh
        feed.updatedAt >= known.second -> merge(known.first, fresh)
        else -> known.first
    }
    val live = game?.state == GameState.LIVE
    LaunchedEffect(id, live) {
        while (true) {
            val got = w.summary(id)
            val now = System.currentTimeMillis()
            if (got != null) {
                feed.summary = got
                feed.updatedAt = now
                feed.failingSince = 0L
            } else if (feed.failingSince == 0L) feed.failingSince = now
            feed.loaded = true
            // Not live (yet, or any more): this was the one fetch; going live or final restarts it.
            if (!live) break
            delay(20_000)
        }
    }
    var tab by remember(id) { mutableStateOf(DetailTab.SUMMARY) }
    val scrolls = remember(id) { mutableMapOf<DetailTab, ScrollState>() }
    val watchFocus = remember { FocusRequester() }

    HavenOverlay(dim = 0.45f, onDismiss = close) {
        Column(
            Modifier.align(Alignment.Center).width(760.dp).fillMaxHeight(0.9f)
                .glassPanel(colors, RoundedCornerShape(26.dp), alpha = 0.95f).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Eyebrow("NBA ·", colors)
                        game?.let { StatusPill(it, colors) }
                    }
                    Text(
                        game?.let { "${it.away.name} vs ${it.home.name}" } ?: "Game details",
                        color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                CloseButton(colors, onClick = close)
            }
            if (game == null) {
                Text(if (feed.loaded) "Couldn't load this game from ESPN." else "Loading…", color = colors.inkSoft, fontSize = 13.sp)
            } else {
                ScoreBlock(w, game, colors)
                if (live) UpdatedLine(feed, colors)

                // Best way to watch first (from the game's networks), the alternatives, then the game's own actions.
                val options = state.watchOptions(game)
                val alertOn = id in state.settingsStore.settings.alertGames
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEachIndexed { i, o ->
                        Pill(if (i == 0) "▶  Watch on ${o.app}" else o.label, colors, selected = i == 0, focusRequester = if (i == 0) watchFocus else null) {
                            o.open(); close()
                        }
                    }
                    if (game.state != GameState.PRE) Pill("Game stats", colors) { tab = DetailTab.BOX }
                    if (game.state != GameState.FINAL) {
                        Pill(if (alertOn) "Alert on" else "Set alert", colors, selected = alertOn) {
                            state.settingsStore.update {
                                it.copy(alertGames = if (id in it.alertGames) it.alertGames - id else it.alertGames + (id to game.start.toEpochMilli()))
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailTab.entries.forEach { t -> Pill(t.label, colors, selected = t == tab) { tab = t } }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.edge))
                val scroll = scrolls.getOrPut(tab) { ScrollState(0) }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val summary = feed.summary
                    when {
                        game.state == GameState.PRE && tab != DetailTab.SUMMARY -> Note("Available after tip-off.", colors)
                        summary == null -> Note(if (feed.loaded) "Couldn't load the details from ESPN. They'll try again while the game is live." else "Loading…", colors)
                        else -> when (tab) {
                            DetailTab.SUMMARY -> SummaryTab(w, game, summary, colors)
                            DetailTab.BOX -> BoxTab(game, summary, colors)
                            DetailTab.PLAYS -> PlaysTab(w, game, summary, colors)
                            DetailTab.TEAM -> TeamTab(game, summary, colors)
                        }
                    }
                }
            }
        }
    }
    // The watch button exists once the game is known (straight away, or after the first fetch).
    LaunchedEffect(game == null) { if (game != null) runCatching { watchFocus.requestFocus() } }
}

/** [base] with [fresh]'s score, clock and records. */
private fun merge(base: Game, fresh: Game): Game = base.copy(
    away = base.away.copy(score = fresh.away.score, record = fresh.away.record.ifBlank { base.away.record }),
    home = base.home.copy(score = fresh.home.score, record = fresh.home.record.ifBlank { base.home.record }),
    state = fresh.state, status = fresh.status, period = fresh.period, clock = fresh.clock, halftime = fresh.halftime,
)

/** Red LIVE, FINAL, or the tip-off time. */
@Composable
private fun StatusPill(g: Game, colors: ThemeColors) {
    val (text, bg, ink) = when (g.state) {
        GameState.LIVE -> Triple("LIVE", LIVE_RED, Color.White)
        GameState.FINAL -> Triple("FINAL", colors.glass, colors.ink)
        GameState.PRE -> Triple(g.start.atZone(ZoneId.systemDefault()).format(clockFmt), colors.glass, colors.ink)
    }
    Text(
        text, color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun ScoreBlock(w: WatchNow, g: Game, colors: ThemeColors) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamScore(w, g.away, g.state, colors, Modifier.weight(1f))
        Column(Modifier.width(170.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (g.state) {
                GameState.PRE -> {
                    Text(g.start.atZone(ZoneId.systemDefault()).format(clockFmt), color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    g.network?.let { Text(it, color = colors.inkSoft, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                GameState.LIVE -> {
                    Text(g.clockLine(), color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(LIVE_RED))
                        Text("LIVE", color = LIVE_RED, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                GameState.FINAL -> Text("Final", color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        TeamScore(w, g.home, g.state, colors, Modifier.weight(1f))
    }
}

@Composable
private fun TeamScore(w: WatchNow, t: Team, st: GameState, colors: ThemeColors, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
        NetImage(w, Nba.logo(t), 44.dp)
        Column {
            Text(t.abbr, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (st != GameState.PRE) Text(t.score, color = colors.ink, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            if (t.record.isNotBlank()) Text(t.record, color = colors.inkSoft, fontSize = 11.sp)
        }
    }
}

/** "Updated just now" / "Updated 20 s ago"; after a minute of failed refreshes, "Last updated 9:41 PM". */
@Composable
private fun UpdatedLine(feed: SummaryFeed, colors: ThemeColors) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); now = System.currentTimeMillis() } }
    if (feed.updatedAt == 0L) return
    val failing = feed.failingSince != 0L && now - feed.failingSince > 60_000L
    val ago = (now - feed.updatedAt) / 1000
    val text = when {
        failing -> "Last updated " + Instant.ofEpochMilli(feed.updatedAt).atZone(ZoneId.systemDefault()).format(clockFmt)
        ago < 10 -> "Updated just now"
        ago < 60 -> "Updated $ago s ago"
        else -> "Updated ${ago / 60} min ago"
    }
    Text(text, color = colors.inkSoft, fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

@Composable
private fun SummaryTab(w: WatchNow, g: Game, s: GameSummary, colors: ThemeColors) {
    if (g.state == GameState.PRE) {
        val records = listOf(g.away, g.home).filter { it.record.isNotBlank() }.joinToString("   ·   ") { "${it.name} ${it.record}" }
        if (records.isNotBlank()) Line(colors) { Text(records, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        s.series?.let { Line(colors) { Text("Season series: $it", color = colors.ink, fontSize = 13.sp) } }
        if (records.isBlank() && s.series == null) Note("Nothing more until tip-off.", colors)
        return
    }
    // Line score.
    val away = s.lines[g.away.abbr].orEmpty()
    val home = s.lines[g.home.abbr].orEmpty()
    val periods = maxOf(away.size, home.size)
    if (periods > 0) {
        val heads = (0 until periods).map { periodName(it + 1) }
        TableRow(colors, header = true, cells = listOf("") + heads + "T")
        TableRow(colors, cells = listOf(g.away.abbr) + (0 until periods).map { away.getOrElse(it) { "" } } + g.away.score)
        TableRow(colors, cells = listOf(g.home.abbr) + (0 until periods).map { home.getOrElse(it) { "" } } + g.home.score)
    }
    // Top performers: each team's highest scorer.
    val tops = listOf(g.away, g.home).mapNotNull { t -> s.teams[t.abbr]?.players?.maxByOrNull { it.points }?.let { t to it } }
    if (tops.isNotEmpty()) {
        Eyebrow("Top performers", colors)
        tops.forEach { (t, p) ->
            Line(colors) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NetImage(w, p.headshot, 36.dp, shape = CircleShape)
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(listOf(t.abbr, p.position).filter { it.isNotBlank() }.joinToString(" · "), color = colors.inkSoft, fontSize = 11.sp)
                    }
                    Text(
                        listOf("PTS", "REB", "AST").joinToString("  ") { k -> "${p.stats[k] ?: "-"} $k" },
                        color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
    // The last three plays.
    if (s.plays.isNotEmpty()) {
        Eyebrow("Recent plays", colors)
        s.plays.takeLast(3).reversed().forEach { PlayLine(w, g, it, colors) }
    }
}

@Composable
private fun BoxTab(g: Game, s: GameSummary, colors: ThemeColors) {
    var side by remember { mutableStateOf(g.away.abbr) }
    val box = s.teams[side]
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(g.away, g.home).forEach { t -> Pill(t.name, colors, selected = t.abbr == side) { side = t.abbr } }
    }
    if (box == null || box.players.isEmpty()) {
        Note("No box score yet.", colors)
        return
    }
    TableRow(colors, header = true, cells = listOf("Player") + BOX_COLUMNS, firstWide = true)
    box.players.forEach { p -> TableRow(colors, cells = listOf(p.name) + BOX_COLUMNS.map { p.stats[it] ?: "-" }, firstWide = true) }
}

@Composable
private fun PlaysTab(w: WatchNow, g: Game, s: GameSummary, colors: ThemeColors) {
    if (s.plays.isEmpty()) {
        Note("No plays yet.", colors)
        return
    }
    // Newest first, with a line where each quarter starts.
    var period = -1
    s.plays.asReversed().forEach { p ->
        if (p.period != period) {
            period = p.period
            Eyebrow(periodName(p.period), colors)
        }
        PlayLine(w, g, p, colors)
    }
}

@Composable
private fun TeamTab(g: Game, s: GameSummary, colors: ThemeColors) {
    val away = s.teams[g.away.abbr]?.stats.orEmpty()
    val home = s.teams[g.home.abbr]?.stats.orEmpty()
    val rows = TEAM_STATS.mapNotNull { (label, names) ->
        val a = names.firstNotNullOfOrNull { away[it] }
        val h = names.firstNotNullOfOrNull { home[it] }
        if (a == null && h == null) null else Triple(label, a ?: "-", h ?: "-")
    }
    if (rows.isEmpty()) {
        Note("No team stats yet.", colors)
        return
    }
    TableRow(colors, header = true, cells = listOf("", g.away.abbr, g.home.abbr))
    rows.forEach { (label, a, h) -> TableRow(colors, cells = listOf(label, a, h)) }
}

/** Clock, team logo and abbreviation, what happened, and the score after it. */
@Composable
private fun PlayLine(w: WatchNow, g: Game, p: Play, colors: ThemeColors) {
    Line(colors) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.clock, color = colors.inkSoft, fontSize = 11.sp, modifier = Modifier.width(44.dp))
            val team = listOf(g.away, g.home).firstOrNull { it.abbr == p.team }
            Box(Modifier.width(54.dp)) {
                if (team != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    NetImage(w, Nba.logo(team), 16.dp)
                    Text(team.abbr, color = colors.ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(p.text, color = colors.ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${p.awayScore}-${p.homeScore}", color = colors.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * A row in a details list. It can take focus (a faint highlight) so the remote can move down a
 * long box score or play-by-play, and the list scrolls along.
 */
@Composable
private fun Line(colors: ThemeColors, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (focused) colors.glass else Color.Transparent)
            .border(1.dp, if (focused) colors.accent else Color.Transparent, RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) { content() }
}

/** A table row: the first column on the left (wide for player names), the rest even. */
@Composable
private fun TableRow(colors: ThemeColors, cells: List<String>, header: Boolean = false, firstWide: Boolean = false) {
    Line(colors) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            cells.forEachIndexed { i, c ->
                Text(
                    c, color = if (header) colors.inkSoft else colors.ink, fontSize = if (header) 10.sp else 12.sp,
                    fontWeight = if (header || i == 0) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = if (i == 0) TextAlign.Start else TextAlign.Center,
                    modifier = if (i == 0) Modifier.weight(if (firstWide) 3f else 1.4f) else Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Note(text: String, colors: ThemeColors) {
    Text(text, color = colors.inkSoft, fontSize = 13.sp)
}

/** "Q1"…"Q4", "OT", "2OT"… */
private fun periodName(p: Int): String = when {
    p <= 4 -> "Q$p"
    p == 5 -> "OT"
    else -> "${p - 4}OT"
}

/** The favorite team's season, opened at the next game. OK on a game shows its detail. */
@Composable
fun TeamSchedule(state: HavenState, colors: ThemeColors) {
    val w = state.watchNow
    val fav = state.settingsStore.settings.favoriteTeam
    val games = w.schedule
    val close = { state.scheduleOpen = false }
    val nextIndex = games.indexOfFirst { it.state != GameState.FINAL }.let { if (it < 0) (games.size - 1).coerceAtLeast(0) else it }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (nextIndex - 2).coerceAtLeast(0))
    val nextFocus = remember { FocusRequester() }
    HavenOverlay(dim = 0.45f, onDismiss = close) {
        Column(
            Modifier.align(Alignment.Center).width(640.dp).fillMaxHeight(0.86f)
                .glassPanel(colors, RoundedCornerShape(26.dp), alpha = 0.95f).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Eyebrow("Haven / Watch now / Schedule", colors)
            Text("${Nba.teamName(fav)} schedule", color = colors.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            // The last two games of each season are set after the NBA Cup group stage.
            val note = if (games.size in 1 until 82) "${games.size} of 82 games scheduled · ${82 - games.size} to be added after the NBA Cup group stage" else "${games.size} games"
            Text(note, color = colors.inkSoft, fontSize = 12.sp)
            if (games.isEmpty()) Text("Loading schedule…", color = colors.inkSoft, fontSize = 13.sp)
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(games, key = { _, g -> g.espnId }) { i, g ->
                    GlassCard(colors, Modifier.fillMaxWidth(), selected = i == nextIndex, focusRequester = if (i == nextIndex) nextFocus else null, onClick = { state.detailGameId = g.espnId }) { ink ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(shortDate(g), color = ink.copy(alpha = 0.75f), fontSize = 13.sp, modifier = Modifier.width(110.dp))
                            Text(opponent(g, fav) + " · " + Nba.teamName(if (g.home.abbr == fav) g.away.abbr else g.home.abbr), color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(
                                when (g.state) {
                                    GameState.FINAL -> result(g, fav)
                                    GameState.LIVE -> "LIVE · ${g.status}"
                                    GameState.PRE -> g.status + (g.network?.let { " · $it" } ?: "")
                                },
                                color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(games.size) { if (games.isNotEmpty()) runCatching { nextFocus.requestFocus() } }
}
