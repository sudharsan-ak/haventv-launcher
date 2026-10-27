package com.aktvhome.launcher.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.core.stats.Advanced
import com.aktvhome.launcher.core.stats.MadeAttempted
import com.aktvhome.launcher.data.BoxPlayer
import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.GameState
import com.aktvhome.launcher.data.GameSummary
import com.aktvhome.launcher.data.Nba
import com.aktvhome.launcher.data.PlayerInfo
import com.aktvhome.launcher.data.StatRow
import com.aktvhome.launcher.data.Team
import com.aktvhome.launcher.data.TeamBox
import com.aktvhome.launcher.data.WatchNow
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Pill
import com.aktvhome.launcher.ui.settings.CloseButton
import com.aktvhome.launcher.ui.settings.Eyebrow
import com.aktvhome.launcher.ui.settings.HavenOverlay
import com.aktvhome.launcher.ui.settings.STACKED_DIM
import com.aktvhome.launcher.ui.settings.STACKED_GLASS
import com.aktvhome.launcher.ui.settings.glassPanel
import com.aktvhome.launcher.ui.theme.ThemeColors
import java.time.format.DateTimeFormatter

private enum class CardTab(val label: String) { GAME("Current game"), SEASON("Season"), SPLITS("Splits") }

private val logDateFmt = DateTimeFormatter.ofPattern("MMM d")

/** Season rows, in order, as ESPN labels its averages. */
private val SEASON_STATS = listOf("GP", "GS", "MIN", "PTS", "REB", "OR", "DR", "AST", "STL", "BLK", "TO", "PF", "FG%", "3P%", "FT%")

/** ESPN's own ratios from its "miscellaneous" category, shown when present. */
private val MISC_STATS = listOf("AST/TO", "STL/TO", "SC-EFF", "SH-EFF", "DD2", "TD3")

/**
 * A player's card over the game details: photo, name, team, number and position, four stat
 * tiles, then Current game (live with the details), Season and Splits; Game log swaps the tabs
 * for the last five games. Back or "‹ Back to game" closes it.
 */
@Composable
fun PlayerCard(state: HavenState, colors: ThemeColors, game: Game, summary: GameSummary?, playerId: String, onClose: () -> Unit) {
    val w = state.watchNow
    var info by remember(playerId) { mutableStateOf(w.cachedPlayer(playerId)) }
    LaunchedEffect(playerId) { if (info == null) info = w.player(playerId) }
    // Live numbers come from the game's own summary, which the details keep refreshing.
    val (team, line, box) = findPlayer(game, summary, playerId)
    var tab by remember(playerId) { mutableStateOf(CardTab.GAME) }
    var showLog by remember(playerId) { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val profile = info?.profile

    // Over the NBA panel: dimmer behind and more solid glass, so the panel doesn't compete.
    HavenOverlay(dim = STACKED_DIM, onDismiss = onClose, blur = 30) {
        Column(
            Modifier.align(Alignment.Center).width(520.dp)
                .glassPanel(colors, RoundedCornerShape(22.dp), alpha = STACKED_GLASS).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(colors.glass), contentAlignment = Alignment.Center) {
                    NetImage(w, profile?.headshot ?: line?.headshot, 56.dp, shape = CircleShape)
                }
                Column(Modifier.weight(1f)) {
                    Eyebrow("Player card", colors)
                    Text(profile?.name ?: line?.name ?: "Player", color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = listOf(
                        profile?.team ?: team?.let { Nba.teamName(it.abbr) }.orEmpty(),
                        profile?.jersey?.takeIf { it.isNotBlank() }?.let { "#$it" }.orEmpty(),
                        profile?.position ?: line?.position.orEmpty(),
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, color = colors.inkSoft, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CloseButton(colors, onClick = onClose)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("PTS", "REB", "AST", "MIN").forEach { k -> StatTile(k, line?.stats?.get(k) ?: "-", colors, Modifier.weight(1f)) }
            }
            if (showLog) {
                Eyebrow("Last 5 games", colors)
                GameLog(info, colors)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardTab.entries.forEachIndexed { i, t ->
                        Pill(t.label, colors, selected = t == tab, focusRequester = if (i == 0) first else null) { tab = t }
                    }
                }
                // Fixed height: the tabs hold the detail, the card doesn't grow.
                Column(Modifier.fillMaxWidth().height(200.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (tab) {
                        CardTab.GAME -> CurrentGame(w, game, line, box, colors)
                        CardTab.SEASON -> SeasonTab(info, colors)
                        CardTab.SPLITS -> SplitsTab(info, colors)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("‹ Back to game", colors, focusRequester = if (showLog) first else null, onClick = onClose)
                Pill(if (showLog) "Stats" else "Game log", colors) { showLog = !showLog }
            }
        }
    }
    LaunchedEffect(showLog) { runCatching { first.requestFocus() } }
}

/** The player's team, line and team box in [summary] (the latest), by athlete id. */
private fun findPlayer(game: Game, summary: GameSummary?, id: String): Triple<Team?, BoxPlayer?, TeamBox?> {
    for ((abbr, box) in summary?.teams.orEmpty()) {
        val p = box.players.firstOrNull { it.id == id } ?: continue
        val team = listOf(game.away, game.home).firstOrNull { it.abbr == abbr }
        return Triple(team, p, box)
    }
    return Triple(null, null, null)
}

@Composable
private fun StatTile(label: String, value: String, colors: ThemeColors, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape).background(colors.glass).border(1.dp, colors.edge, shape).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = colors.inkSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * The game so far: a mini score strip, shooting (made-attempted and %), the rest of the line,
 * and advanced numbers worked out from the live box score.
 */
@Composable
private fun CurrentGame(w: WatchNow, g: Game, p: BoxPlayer?, box: TeamBox?, colors: ThemeColors) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NetImage(w, Nba.logo(g.away), 18.dp)
        Text("${g.away.abbr} ${g.away.score}", color = colors.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("·", color = colors.inkSoft, fontSize = 12.sp)
        Text("${g.home.score} ${g.home.abbr}", color = colors.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        NetImage(w, Nba.logo(g.home), 18.dp)
        Box(Modifier.weight(1f))
        if (g.state == GameState.LIVE) Box(Modifier.size(7.dp).clip(CircleShape).background(LIVE_RED))
        Text(if (g.state == GameState.LIVE) g.clockLine() else g.status, color = colors.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
    if (p == null) {
        Text("No line for this game yet.", color = colors.inkSoft, fontSize = 12.sp)
        return
    }
    val s = p.stats
    fun n(k: String) = Advanced.number(s[k])
    val fg = Advanced.madeAttempted(s["FG"])
    val three = Advanced.madeAttempted(s["3PT"])
    val ft = Advanced.madeAttempted(s["FT"])
    fun shooting(ma: MadeAttempted?) = ma?.let { (m, a) -> "${m.toInt()}-${a.toInt()} (${Advanced.show(Advanced.ratio(100 * m, a))})" } ?: "-"
    Section(colors, listOf("FG", "3PT", "FT"), listOf(shooting(fg), shooting(three), shooting(ft)))
    Section(colors, listOf("STL", "BLK", "TO", "PF", "OREB", "DREB", "+/-"), listOf("STL", "BLK", "TO", "PF", "OREB", "DREB", "+/-").map { s[it] ?: "-" })

    val (fgm, fga) = fg ?: MadeAttempted.NONE
    val (ftm, fta) = ft ?: MadeAttempted.NONE
    val threes = three?.made ?: 0.0
    val team = teamTotals(box)
    val minutes = n("MIN")
    Eyebrow("Advanced", colors)
    Section(
        colors, listOf("TS%", "eFG%", "USG%", "AST/TO", "GmSc", "PTS/shot"),
        listOf(
            Advanced.show(Advanced.trueShooting(n("PTS"), fga, fta)),
            Advanced.show(Advanced.effectiveFg(fgm, threes, fga)),
            Advanced.show(team?.let { t -> Advanced.usage(fga, fta, n("TO"), minutes, t.min, t.fga, t.fta, t.to) }),
            Advanced.show(Advanced.ratio(n("AST"), n("TO"))),
            Advanced.show(Advanced.gameScore(n("PTS"), fgm, fga, ftm, fta, n("OREB"), n("DREB"), n("STL"), n("AST"), n("BLK"), n("PF"), n("TO"))),
            Advanced.show(Advanced.ratio(n("PTS"), fga)),
        ),
    )
}

private class TeamTotals(val min: Double, val fga: Double, val fta: Double, val to: Double)

/** The team's minutes (its players' total), FGA, FTA and turnovers (team stats, else its players'). */
private fun teamTotals(box: TeamBox?): TeamTotals? {
    box ?: return null
    fun sum(f: (BoxPlayer) -> Double) = box.players.sumOf(f)
    val fga = Advanced.madeAttempted(box.stats["fieldGoalsMade-fieldGoalsAttempted"] ?: box.stats["FG"])?.attempted
        ?: sum { Advanced.madeAttempted(it.stats["FG"])?.attempted ?: 0.0 }
    val fta = Advanced.madeAttempted(box.stats["freeThrowsMade-freeThrowsAttempted"] ?: box.stats["FT"])?.attempted
        ?: sum { Advanced.madeAttempted(it.stats["FT"])?.attempted ?: 0.0 }
    val to = (box.stats["turnovers"] ?: box.stats["totalTurnovers"] ?: box.stats["TO"])?.let { Advanced.number(it) }
        ?: sum { Advanced.number(it.stats["TO"]) }
    return TeamTotals(sum { Advanced.number(it.stats["MIN"]) }, fga, fta, to)
}

/** This season against the career, a row per stat (so the card stays narrow), then TS%, eFG% and ESPN's ratios. */
@Composable
private fun SeasonTab(info: PlayerInfo?, colors: ThemeColors) {
    val s = info?.season
    if (s == null) {
        Text(if (info == null) "Loading…" else "No season stats from ESPN.", color = colors.inkSoft, fontSize = 12.sp)
        return
    }
    StatTableRow(colors, header = true, cells = listOf("", "This season", "Career"))
    SEASON_STATS.forEach { k ->
        val a = s.averages[k]
        val c = s.careerAverages[k]
        if (a != null || c != null) StatTableRow(colors, cells = listOf(k, a ?: "-", c ?: "-"))
    }
    if (s.totals.isNotEmpty() || s.careerTotals.isNotEmpty()) {
        StatTableRow(colors, cells = listOf("TS%", Advanced.show(ts(s.totals)), Advanced.show(ts(s.careerTotals))))
        StatTableRow(colors, cells = listOf("eFG%", Advanced.show(efg(s.totals)), Advanced.show(efg(s.careerTotals))))
    }
    MISC_STATS.forEach { k ->
        val a = s.misc[k]
        val c = s.careerMisc[k]
        if (a != null || c != null) StatTableRow(colors, cells = listOf(k, a ?: "-", c ?: "-"))
    }
}

/** All games, home, road, the latest month and as a starter: PTS REB AST FG% 3P% MIN, plus TS%. */
@Composable
private fun SplitsTab(info: PlayerInfo?, colors: ThemeColors) {
    val splits = info?.splits.orEmpty()
    if (splits.isEmpty()) {
        Text(if (info == null) "Loading…" else "No splits from ESPN.", color = colors.inkSoft, fontSize = 12.sp)
        return
    }
    val cols = listOf("PTS", "REB", "AST", "FG%", "3P%", "MIN")
    StatTableRow(colors, header = true, cells = listOf("") + cols + "TS%", firstWide = true)
    splits.forEach { sp -> StatTableRow(colors, cells = listOf(sp.name) + cols.map { sp.stats[it] ?: "-" } + Advanced.show(ts(sp.stats)), firstWide = true) }
}

@Composable
private fun GameLog(info: PlayerInfo?, colors: ThemeColors) {
    val log = info?.log.orEmpty()
    if (log.isEmpty()) {
        Text(if (info == null) "Loading…" else "No recent games from ESPN.", color = colors.inkSoft, fontSize = 12.sp)
        return
    }
    StatTableRow(colors, header = true, cells = listOf("", "", "PTS", "REB", "AST"))
    log.forEach { r ->
        StatTableRow(colors, cells = listOf(r.date?.format(logDateFmt) ?: "", r.opponent) + listOf("PTS", "REB", "AST").map { r.stats[it] ?: "-" })
    }
}

/** A row of labelled numbers: labels small above, values below. */
@Composable
private fun Section(colors: ThemeColors, labels: List<String>, values: List<String>) {
    DetailLine(colors) {
        Row {
            labels.zip(values).forEach { (l, v) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(l, color = colors.inkSoft, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(v, color = colors.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/** TS% from a row with PTS, FG and FT (made-attempted), as ESPN's totals and splits have. */
private fun ts(r: StatRow): Double? {
    val fga = Advanced.madeAttempted(r["FG"])?.attempted ?: r["FGA"]?.let { Advanced.number(it) } ?: return null
    val fta = Advanced.madeAttempted(r["FT"])?.attempted ?: r["FTA"]?.let { Advanced.number(it) } ?: return null
    val pts = r["PTS"]?.let { Advanced.number(it) } ?: return null
    return Advanced.trueShooting(pts, fga, fta)
}

/** eFG% from a row with FG and 3PT (made-attempted). */
private fun efg(r: StatRow): Double? {
    val (fgm, fga) = Advanced.madeAttempted(r["FG"]) ?: return null
    val threes = Advanced.madeAttempted(r["3PT"])?.made ?: r["3PM"]?.let { Advanced.number(it) } ?: return null
    return Advanced.effectiveFg(fgm, threes, fga)
}
