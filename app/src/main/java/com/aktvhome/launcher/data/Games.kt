package com.aktvhome.launcher.data

import java.time.Instant

// Plain Kotlin (no Android), so the alert rules that read these can be unit tested.

enum class GameState { PRE, LIVE, FINAL }

data class Team(
    /** ESPN abbreviation, e.g. "GS". */
    val abbr: String,
    val name: String,
    val score: String,
    /** Hex color without '#', from ESPN. */
    val color: String,
    /** "P. Banchero 35", when ESPN reports it. */
    val leader: String?,
    /** Logo URL from ESPN, when the feed includes one. */
    val logo: String? = null,
    /** City or region, e.g. "Portland", "Golden State". */
    val location: String = "",
    /** Season record, e.g. "2-1", when ESPN sends it. */
    val record: String = "",
)

data class Game(
    val espnId: String,
    val start: Instant,
    val away: Team,
    val home: Team,
    val state: GameState,
    /** "Q4 1:42", "Final", "Halftime", or the tip-off time. */
    val status: String,
    val network: String?,
    /** Quarter, 5 and up for overtime; 0 before tip-off. */
    val period: Int = 0,
    /** Game clock as ESPN shows it: "5:00", or "45.2" in the last minute. */
    val clock: String = "",
    val halftime: Boolean = false,
    /** Postponed or cancelled. */
    val calledOff: Boolean = false,
) {
    fun involves(abbr: String) = abbr.isNotBlank() && (away.abbr == abbr || home.abbr == abbr)

    /** "Q4 1:42", "OT 2:10", "2OT 0:45" or "Halftime" while live; [status] otherwise. */
    fun clockLine(): String {
        if (state != GameState.LIVE || period == 0) return status
        if (halftime) return "Halftime"
        val name = when {
            period <= 4 -> "Q$period"
            period == 5 -> "OT"
            else -> "${period - 4}OT"
        }
        return "$name $clock".trim()
    }

    /** Every broadcaster ESPN lists, as it names them: "NBA League Pass", "MyMi", "Bucks App". */
    val broadcasters: List<String> get() = network.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Networks ESPN lists for the game, upper case: "ESPN", "ABC", "PRIME VIDEO". */
    val networks: List<String> get() = network.orEmpty().split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
}

/** One play: "Q4 1:42", what happened, which team, and the score after it. */
data class Play(val period: Int, val clock: String, val text: String, val team: String?, val awayScore: String, val homeScore: String)

/** A player's line in the box score; [stats] by ESPN's labels (MIN, PTS, FG, 3PT, REB, AST…). */
data class BoxPlayer(val name: String, val position: String, val headshot: String?, val stats: Map<String, String>, val id: String = "") {
    val points: Int get() = stats["PTS"]?.toIntOrNull() ?: -1
}

/** A team's box score: its players, and its totals by ESPN's stat names and labels. */
data class TeamBox(val players: List<BoxPlayer>, val stats: Map<String, String>)

/**
 * A game's summary. [game] has the latest score and clock; [lines] and [teams] are keyed by team
 * abbreviation; [plays] run oldest first. Missing parts are empty (before tip-off, most are).
 */
data class GameSummary(
    val game: Game?,
    val lines: Map<String, List<String>>,
    val plays: List<Play>,
    val teams: Map<String, TeamBox>,
    val series: String?,
    /** ESPN's recap of a finished game, when it has one. */
    val recap: String? = null,
)

/**
 * A finished game in one or two plain sentences, from the data alone: "Bulls beat the Bucks
 * 132–125. Norman Powell led Chicago with 25; Tyler Herro had 34 for Milwaukee." [awayTop] and
 * [homeTop] are each side's top scorer and points. Null without a winner.
 */
fun recapLine(game: Game, awayTop: Pair<String, Int>?, homeTop: Pair<String, Int>?): String? {
    val a = game.away.score.toIntOrNull() ?: return null
    val h = game.home.score.toIntOrNull() ?: return null
    if (a == h) return null
    val awayWon = a > h
    val (win, lose) = if (awayWon) game.away to game.home else game.home to game.away
    val (winTop, loseTop) = if (awayWon) awayTop to homeTop else homeTop to awayTop
    val ot = if (game.period > 4) " in overtime" else ""
    val result = "${win.name} beat the ${lose.name} ${maxOf(a, h)}–${minOf(a, h)}$ot."
    val led = winTop?.let { "${it.first} led ${win.location.ifBlank { win.name }} with ${it.second}" }
    val had = loseTop?.let { "${it.first} had ${it.second} for ${lose.location.ifBlank { lose.name }}" }
    val scorers = listOfNotNull(led, had).joinToString("; ")
    return if (scorers.isEmpty()) result else "$result ${scorers.replaceFirstChar { it.uppercase() }}."
}
