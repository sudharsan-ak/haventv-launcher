package com.aktvhome.launcher.data

import java.time.Duration
import java.time.Instant

/** Pure NBA Home-card presentation rules. No networking, Compose, or Android state. */
object NbaRotation {
    /** Yesterday's unfinished games may still be live past midnight. */
    fun overnight(game: Game, now: Instant): Boolean = when (game.state) {
        GameState.LIVE -> true
        GameState.PRE -> !game.calledOff && !game.start.isAfter(now) &&
            Duration.between(game.start, now) <= Duration.ofHours(6)
        GameState.FINAL -> false
    }

    /** ESPN's scoreboard is refreshed elsewhere; this only selects cached game objects. */
    fun slate(today: List<Game>, yesterday: List<Game>, now: Instant): List<Game> =
        (today + yesterday.filter { overnight(it, now) }).distinctBy { it.espnId }

    /** Feature the favorite, then active games, approaching tipoffs, and completed games. */
    fun ordered(games: List<Game>, favorite: String): List<Game> =
        games.distinctBy { it.espnId }.sortedWith(
            compareBy<Game>(
                { if (it.involves(favorite)) 0 else 1 },
                { when (it.state) {
                    GameState.LIVE -> 0
                    GameState.PRE -> 1
                    GameState.FINAL -> 2
                } },
                { it.start },
            ),
        )

    /** A full loop ends on the featured game, which lingers before another loop begins. */
    fun displayMillis(index: Int, count: Int, intervalMillis: Long): Long = when {
        count < 2 -> Long.MAX_VALUE // the coroutine should not run in this case
        index != 0 -> intervalMillis
        count <= 4 -> 20_000L
        else -> 30_000L
    }

    /** Preserves selection by ESPN id even when a refreshed scoreboard changes order. */
    fun nextId(ids: List<String>, currentId: String?): String? {
        if (ids.isEmpty()) return null
        val current = ids.indexOf(currentId)
        return if (current < 0) ids.first() else ids[(current + 1) % ids.size]
    }
}
