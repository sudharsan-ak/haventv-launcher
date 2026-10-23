package com.aktvhome.launcher.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.BoxScore
import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.GameState
import com.aktvhome.launcher.data.Nba
import com.aktvhome.launcher.data.PlayerLine
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.settings.Eyebrow
import com.aktvhome.launcher.ui.settings.GlassCard
import com.aktvhome.launcher.ui.settings.HavenOverlay
import com.aktvhome.launcher.ui.settings.glassPanel
import com.aktvhome.launcher.ui.theme.ThemeColors

/** Score, clock and network, top five scorers per team, and Open in NBA app / Dismiss. */
@Composable
fun GameDetail(state: HavenState, colors: ThemeColors, game: Game) {
    val close = { state.detailGame = null }
    val openFocus = remember { FocusRequester() }
    var box by remember(game.espnId) { mutableStateOf<BoxScore?>(null) }
    var loaded by remember(game.espnId) { mutableStateOf(false) }
    LaunchedEffect(game.espnId) {
        if (game.state != GameState.PRE) box = state.watchNow.boxScore(game)
        loaded = true
    }
    HavenOverlay(dim = 0.45f, onDismiss = close) {
        Column(
            Modifier.align(Alignment.Center).width(720.dp).glassPanel(colors, RoundedCornerShape(26.dp), alpha = 0.95f).padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Eyebrow(listOfNotNull(if (game.state == GameState.LIVE) "Live" else null, shortDate(game), game.network).joinToString(" · "), colors)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Side(game.away.abbr, game.away.name, game.away.score, game.away.color, game.state, Alignment.Start, colors, Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(game.status, color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(if (game.state == GameState.PRE) "Tip-off" else "@", color = colors.inkSoft, fontSize = 12.sp)
                }
                Side(game.home.abbr, game.home.name, game.home.score, game.home.color, game.state, Alignment.End, colors, Modifier.weight(1f))
            }
            when {
                game.state == GameState.PRE -> Text("Leaders and box score appear after tip-off.", color = colors.inkSoft, fontSize = 13.sp)
                !loaded -> Text("Loading box score…", color = colors.inkSoft, fontSize = 13.sp)
                box == null -> Text(
                    listOfNotNull(game.away.leader?.let { "${game.away.abbr}: $it" }, game.home.leader?.let { "${game.home.abbr}: $it" })
                        .joinToString("   ").ifBlank { "Box score unavailable right now." },
                    color = colors.inkSoft, fontSize = 13.sp,
                )
                else -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Players(game.away.abbr, box!!.away, colors, Modifier.weight(1f))
                    Players(game.home.abbr, box!!.home, colors, Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("▶  Open in NBA app", colors, focusRequester = openFocus) { state.watchNow.openGame(game); close() }
                Pill("Dismiss", colors, onClick = close)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { openFocus.requestFocus() } }
}

@Composable
private fun Side(abbr: String, name: String, score: String, color: String, gameState: GameState, align: Alignment.Horizontal, colors: ThemeColors, modifier: Modifier) {
    Column(modifier, horizontalAlignment = align) {
        Text(abbr, color = teamColor(color), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(name, color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (gameState != GameState.PRE) Text(score, color = colors.ink, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Players(abbr: String, lines: List<PlayerLine>, colors: ThemeColors, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(abbr, color = colors.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            listOf("PTS", "REB", "AST").forEach { Text(it, color = colors.inkSoft, fontSize = 11.sp, modifier = Modifier.width(38.dp)) }
        }
        lines.forEach { p ->
            Row {
                Text(p.name, color = colors.ink, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                listOf(p.pts, p.reb, p.ast).forEach { Text(it, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(38.dp)) }
            }
        }
    }
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
                    GlassCard(colors, Modifier.fillMaxWidth(), selected = i == nextIndex, focusRequester = if (i == nextIndex) nextFocus else null, onClick = { state.detailGame = g }) { ink ->
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
