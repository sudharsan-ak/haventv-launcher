package com.aktvhome.launcher.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.tvClickable
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** True when at least one Watch Now section is switched on, so the strip has something to show. */
fun HavenState.stripVisible(): Boolean = settingsStore.settings.let { it.watchNba || it.watchContinue || it.watchLive }

/**
 * The slim Watch Now strip on Home: Tonight (NBA), Continue, Live TV, and See more.
 * [first] takes focus when the strip is entered; [modifier] carries the theme's key handling.
 */
@Composable
fun SmartStrip(state: HavenState, colors: ThemeColors, first: FocusRequester, modifier: Modifier = Modifier) {
    val s = state.settingsStore.settings
    val w = state.watchNow
    var firstUsed = false
    fun claim(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier

    Row(modifier.height(68.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      for (section in s.watchOrder) when (section) {
        "nba" -> if (s.watchNba) {
            val g = w.featured()
            val fav = s.favoriteTeam
            when {
                g != null -> StripCard(
                    if (g.involves(fav)) Nba.teamName(fav).substringAfterLast(' ').uppercase() + " · TODAY" else "TONIGHT",
                    matchup(g), g.status + (g.network?.let { " · $it" } ?: ""), g.state == GameState.LIVE,
                    colors, claim().weight(1.25f),
                ) { state.overlay = Overlay.NBA }
                w.games[w.today()] == null && !w.nbaError -> StripCard("TONIGHT", "Loading games…", "ESPN", false, colors, claim().weight(1.25f)) {
                    state.overlay = Overlay.NBA
                }
                else -> {
                    val next = w.nextFavorite()
                    StripCard(
                        "TONIGHT", if (w.nbaError) "Scores unavailable" else "No NBA games today",
                        next?.let { "Next: ${shortDate(it)} · ${matchup(it)}" } ?: "Check other days in Watch Now",
                        false, colors, claim().weight(1.25f),
                    ) { state.overlay = Overlay.NBA }
                }
            }
        }
        "continue" -> if (s.watchContinue) {
            val item = w.continueWatching.firstOrNull()
            StripCard(
                "CONTINUE", item?.title ?: "Nothing to continue",
                item?.let { listOf(it.detail, state.apps[it.pkg]?.label.orEmpty(), it.progress?.let { p -> "${(p * 100).toInt()}% watched" }.orEmpty()).filter { s -> s.isNotBlank() }.joinToString(" · ") }
                    ?: "Apps that share progress show up here",
                false, colors, claim().weight(1f),
            ) { state.overlay = Overlay.CONTINUE }
        }
        "live" -> if (s.watchLive) {
            val groups = w.live
            StripCard(
                "LIVE TV", groups.take(2).joinToString(" + ") { state.apps[it.pkg]?.label ?: it.provider }.ifBlank { "No live channels" },
                groups.firstOrNull()?.items?.take(3)?.joinToString(", ") { it.title } ?: "Live TV apps that share channels show up here",
                groups.any { g -> g.items.any { it.live } }, colors, claim().weight(1f),
            ) { state.overlay = Overlay.LIVE }
        }
      }
        SeeMore(colors, claim()) { state.overlay = Overlay.WATCH }
    }
}

@Composable
private fun RowScope.StripCard(
    kicker: String,
    main: String,
    sub: String,
    live: Boolean,
    colors: ThemeColors,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val ink = if (focused) colors.pillInk else colors.ink
    Column(
        modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (focused) colors.pill else colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(kicker, color = ink.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1)
        Text(main, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (live) Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFE53935)))
            Text(sub, color = ink.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SeeMore(colors: ThemeColors, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    // Same glass as the cards beside it; only focus makes it solid.
    Column(
        modifier
            .width(118.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(if (focused) colors.pill else colors.glass)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.accent else colors.edge, shape)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val ink = if (focused) colors.pillInk else colors.ink
        Text("WATCH NOW", color = ink.copy(alpha = 0.8f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Text("See more", color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** "GS 105 · LAL 127" once it's started, "MEM @ GS" before. */
fun matchup(g: Game): String =
    if (g.state == GameState.PRE) "${g.away.abbr} @ ${g.home.abbr}"
    else "${g.away.abbr} ${g.away.score} · ${g.home.abbr} ${g.home.score}"

private val shortFmt = DateTimeFormatter.ofPattern("EEE MMM d")

fun shortDate(g: Game): String = g.start.atZone(ZoneId.systemDefault()).format(shortFmt)
