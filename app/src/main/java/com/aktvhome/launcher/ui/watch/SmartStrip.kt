package com.aktvhome.launcher.ui.watch

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.GameState
import com.aktvhome.launcher.data.Nba
import com.aktvhome.launcher.data.NbaRotation
import com.aktvhome.launcher.ui.HavenState
import com.aktvhome.launcher.ui.Overlay
import com.aktvhome.launcher.ui.theme.ThemeColors
import com.aktvhome.launcher.ui.tvClickable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** True when at least one Watch Now section is switched on, so the strip has something to show. */
fun HavenState.stripVisible(): Boolean = settingsStore.settings.let { it.nbaCardEnabled() || it.watchContinue || it.watchLive }

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
    val nbaLogo: @Composable () -> Unit = { NetImage(w, Nba.LEAGUE_LOGO, 11.dp) }
    val eligible = w.homeRotationGames()
    val ids = eligible.map { it.espnId }
    var rotationId by remember { mutableStateOf<String?>(null) }
    var nbaFocused by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val rotate = s.nbaCardEnabled() && s.rotateNba && ids.size > 1

    // Card animation and the timer are Home-only. ESPN updates are handled by WatchNow.
    // Use stable ESPN identifiers to avoid resetting the timer every time a score changes.
    LaunchedEffect(lifecycle, rotate, ids, nbaFocused, s.nbaRotationInterval, state.overlay, state.wallpaperView) {
        if (!rotate || nbaFocused || state.overlay != Overlay.NONE || state.wallpaperView) return@LaunchedEffect
        if (rotationId !in ids) rotationId = ids.first()
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val index = ids.indexOf(rotationId).takeIf { it >= 0 } ?: 0
                delay(NbaRotation.displayMillis(index, ids.size, s.nbaRotationInterval.millis))
                rotationId = NbaRotation.nextId(ids, rotationId)
            }
        }
    }

    Row(modifier.height(68.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      for (section in s.watchOrder) when (section) {
        "nba" -> if (s.nbaCardEnabled()) {
            val g = if (s.rotateNba) eligible.firstOrNull { it.espnId == rotationId } ?: eligible.firstOrNull() ?: w.featured() else w.featured()
            val fav = s.favoriteTeam
            when {
                g != null -> StripCard(
                    (if (g.involves(fav)) Nba.teamName(fav).substringAfterLast(' ').uppercase() + " · TODAY" else "TONIGHT") +
                        (if (rotate) " · ${ids.indexOf(g.espnId) + 1}/${ids.size}" else ""),
                    matchup(g), g.status + (g.network?.let { " · $it" } ?: ""), g.state == GameState.LIVE,
                    colors, claim().weight(1.25f), nbaLogo,
                    onFocusState = { nbaFocused = it },
                ) { state.openGame(g) }
                w.games[w.today()] == null && !w.nbaError -> StripCard("TONIGHT", "Loading games…", "ESPN", false, colors, claim().weight(1.25f), nbaLogo) {
                    state.overlay = Overlay.NBA
                }
                else -> {
                    val next = w.nextFavorite()
                    StripCard(
                        "TONIGHT", if (w.nbaError) "Scores unavailable" else "No NBA games today",
                        next?.let { "Next: ${shortDate(it)} · ${matchup(it)}" } ?: "Check other days in Watch Now",
                        false, colors, claim().weight(1.25f), nbaLogo,
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
    /** Small mark before the kicker (the NBA logo). */
    badge: (@Composable () -> Unit)? = null,
    onFocusState: (Boolean) -> Unit = {},
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
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    onFocusState(focused)
                }
            }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val presentation = StripPresentation(kicker, main, sub, live)
        Crossfade(targetState = presentation, animationSpec = tween(550), label = "Home strip content") { value ->
            Column(verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    badge?.invoke()
                    Text(value.kicker, color = ink.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1)
                }
                Text(value.main, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (value.live) Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFE53935)))
                    Text(value.sub, color = ink.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private data class StripPresentation(val kicker: String, val main: String, val sub: String, val live: Boolean)

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
