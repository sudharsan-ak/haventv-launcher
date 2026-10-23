package com.aktvhome.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.aktvhome.launcher.data.AlertRules
import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.GameState
import com.aktvhome.launcher.data.Team
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlin.math.abs

const val FIXED_HEADLINE = "What are we watching?"

/**
 * The headline with notable games ([notableGames]) taking turns: the usual line for about 30 s,
 * then each notable game for about 10 s, then back. No notable games, no rotation.
 */
@Composable
fun rememberHeadline(state: HavenState): String {
    val notable = state.notableGames()
    var turn by remember { mutableIntStateOf(-1) }
    LaunchedEffect(notable.map { it.espnId }) {
        turn = -1
        if (notable.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(30_000)
            for (i in notable.indices) {
                turn = i
                delay(10_000)
            }
            turn = -1
        }
    }
    // Lines are rebuilt from the latest scores each time, so a turn never shows an old score.
    return notable.getOrNull(turn)?.let { state.notableLine(it) } ?: state.headline()
}

/**
 * Kingdom's big line. On a favorite-team game day it follows the game (tip-off, live score, result),
 * updating as scores refresh; otherwise a line that fits the time of day, changing each time you
 * come back to Home. "Fixed" in Display settings keeps it as "What are we watching?".
 */
fun HavenState.headline(): String {
    val s = settingsStore.settings
    if (!s.headlineChanging) return FIXED_HEADLINE
    val now = this.now // read so the countdown recomposes on the 15 s clock
    gameHeadline()?.let { return it }

    val weekendDay = LocalDate.now().dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) && now.hour in 8..16
    val lines = when (now.hour) {
        in 5..11 -> listOf("Something easy to start the day?", "Coffee and a show?")
        in 12..16 -> listOf("What are we watching?", "Something for the afternoon?")
        in 17..20 -> listOf("What are we watching tonight?", "Ready for tonight?")
        in 21..23, 0 -> listOf("One more episode?", "Something before bed?")
        else -> listOf("Still up?")
    } + if (weekendDay) listOf("Lazy weekend?") else emptyList()
    // Changes on each return to Home, never while you're looking at it.
    return lines[(arrivals + homePresses) % lines.size]
}

/** The favorite team's game today, as a headline, or null when there's none (or no fresh score). */
private fun HavenState.gameHeadline(): String? {
    val fav = settingsStore.settings.favoriteTeam
    if (fav.isBlank()) return null
    val w = watchNow
    val game: Game = w.games[w.today()]?.firstOrNull { it.involves(fav) } ?: return null
    val (us, them) = if (game.home.abbr == fav) game.home to game.away else game.away to game.home
    val ours = us.score.toIntOrNull()
    val theirs = them.score.toIntOrNull()
    val hide = settingsStore.settings.hideScores
    return when (game.state) {
        GameState.PRE -> {
            val minutes = Duration.between(Instant.now(), game.start).toMinutes()
            when {
                minutes <= 0 -> "${us.name} tip off any minute"
                minutes == 1L -> "${us.name} tip off in 1 minute"
                minutes <= 30 -> "${us.name} tip off in $minutes minutes"
                else -> "${us.name} tip off at ${game.status}"
            }
        }
        GameState.LIVE -> {
            // A failed or old refresh means the score could be stale: fall back to a mood line.
            if (w.nbaError || !w.fresh(w.today()) || ours == null || theirs == null) return null
            liveLine(game, us, them, hide, withOpponent = false)
        }
        GameState.FINAL -> {
            if (ours == null || theirs == null) return null
            if (hide) "${us.name} vs ${them.name} · Final"
            else if (ours > theirs) "${us.name} win $ours–$theirs" else "${us.name} fall $ours–$theirs"
        }
    }
}

/**
 * A live game as a headline, told from [us]'s side: "Warriors lead 98–96 · Q4 1:42" for the main
 * team, "Bulls lead Bucks 98–96 · Q4 1:42" [withOpponent]; "tied", "at the half" the same way.
 * With Hide scores: "Bulls and Bucks are close · Q4 1:42" or "Bulls vs Bucks · Q2 4:00".
 */
private fun liveLine(game: Game, us: Team, them: Team, hide: Boolean, withOpponent: Boolean): String? {
    val ours = us.score.toIntOrNull() ?: return null
    val theirs = them.score.toIntOrNull() ?: return null
    if (hide) {
        if (game.halftime) return "${us.name} and ${them.name} at the half"
        val pair = if (abs(ours - theirs) <= 5) "${us.name} and ${them.name} are close" else "${us.name} vs ${them.name}"
        return "$pair · ${game.clockLine()}"
    }
    val verb = when {
        ours > theirs -> "lead"
        ours < theirs -> "trail"
        else -> "tied"
    }
    val score = "$ours–$theirs"
    val subject = when {
        !withOpponent -> "${us.name} $verb"
        ours == theirs -> "${us.name} and ${them.name} tied"
        else -> "${us.name} $verb ${them.name}"
    }
    return if (game.halftime) "$subject $score at the half" else "$subject $score · ${game.clockLine()}"
}

/**
 * Games worth a turn in the headline: a followed team's live game (other than the main team's,
 * which has the headline already), or any game in clutch time (Q4 or OT, 5:00 or less, within 5)
 * or overtime. Only from a fresh scoreboard.
 */
fun HavenState.notableGames(): List<Game> {
    val s = settingsStore.settings
    if (!s.headlineChanging) return emptyList()
    val w = watchNow
    val today = w.today()
    if (w.nbaError || !w.fresh(today)) return emptyList()
    return w.games[today].orEmpty().filter { g ->
        if (g.state != GameState.LIVE || g.involves(s.favoriteTeam)) return@filter false
        val a = g.away.score.toIntOrNull()
        val h = g.home.score.toIntOrNull()
        val left = AlertRules.clockSeconds(g.clock)
        val clutch = g.period >= 4 && left != null && left <= 300 && a != null && h != null && abs(a - h) <= 5
        g.away.abbr in s.followedTeams || g.home.abbr in s.followedTeams || clutch || g.period > 4
    }
}

/** A notable game's headline, from the side of the team ahead (or a followed team when tied). */
fun HavenState.notableLine(game: Game): String? {
    val a = game.away.score.toIntOrNull() ?: return null
    val h = game.home.score.toIntOrNull() ?: return null
    val followed = settingsStore.settings.followedTeams
    val awayFirst = when {
        a != h -> a > h
        else -> game.away.abbr in followed || game.home.abbr !in followed
    }
    val (us, them) = if (awayFirst) game.away to game.home else game.home to game.away
    return liveLine(game, us, them, settingsStore.settings.hideScores, withOpponent = true)
}
