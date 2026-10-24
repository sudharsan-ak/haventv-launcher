package com.aktvhome.launcher.data

import kotlin.math.abs

// What the morning "Last night" toast on Haven's Home says. Plain Kotlin, unit tested.

/** The toast: its [headline], the [detail] line under it, and the [game] See recaps opens. */
data class LastNightToast(val headline: String, val detail: String?, val game: Game)

object LastNight {

    /**
     * The toast for yesterday's [games], or null when none finished. The main team's game leads;
     * without one, the most notable final: a [followed] team's, else an overtime game, else the
     * closest. With [hideScores] it names the teams but never a score or a result.
     */
    fun pick(games: List<Game>, main: String, followed: Set<String>, hideScores: Boolean): LastNightToast? {
        val finals = games.filter { it.state == GameState.FINAL && !it.calledOff }
        if (finals.isEmpty()) return null
        val ours = finals.firstOrNull { it.involves(main) }
        val game = ours
            ?: finals.firstOrNull { it.away.abbr in followed || it.home.abbr in followed }
            ?: finals.filter { it.period > 4 }.minByOrNull(::margin)
            ?: finals.minBy(::margin)
        val others = finals.size - 1
        val more = when (others) {
            0 -> null
            1 -> "1 more game"
            else -> "$others more games"
        }
        // Told from the main team's side when it played; otherwise the winner's.
        val (us, them) = when {
            ours != null && game.home.abbr == main -> game.home to game.away
            ours != null -> game.away to game.home
            (game.home.score.toIntOrNull() ?: 0) > (game.away.score.toIntOrNull() ?: 0) -> game.home to game.away
            else -> game.away to game.home
        }
        if (hideScores) {
            return LastNightToast("Last night's games are in", listOfNotNull("${us.name} played the ${them.name}", more).joinToString(" · "), game)
        }
        val a = us.score.toIntOrNull() ?: return null
        val b = them.score.toIntOrNull() ?: return null
        val headline = when {
            a > b -> "${us.name} beat the ${them.name} $a–$b"
            a < b -> "${us.name} fell to the ${them.name} $a–$b"
            else -> "${us.name} and the ${them.name} tied $a–$b"
        }
        return LastNightToast(headline, more, game)
    }

    private fun margin(g: Game): Int = abs((g.away.score.toIntOrNull() ?: 0) - (g.home.score.toIntOrNull() ?: 0))
}
