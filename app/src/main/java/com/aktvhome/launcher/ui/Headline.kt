package com.aktvhome.launcher.ui

import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.GameState
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

const val FIXED_HEADLINE = "What are we watching?"

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
            // A failed refresh means the score could be stale: fall back to a mood line.
            if (w.nbaError || ours == null || theirs == null) return null
            val score = "$ours–$theirs"
            val verb = when {
                ours > theirs -> "lead"
                ours < theirs -> "trail"
                else -> "tied"
            }
            if (game.status.contains("half", ignoreCase = true)) "${us.name} $verb $score at the half"
            else "${us.name} $verb $score · ${game.status}"
        }
        GameState.FINAL -> {
            if (ours == null || theirs == null) return null
            if (ours > theirs) "${us.name} win $ours–$theirs" else "${us.name} fall $ours–$theirs"
        }
    }
}
