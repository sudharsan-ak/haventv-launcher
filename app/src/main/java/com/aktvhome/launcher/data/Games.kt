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

    /** Networks ESPN lists for the game, upper case: "ESPN", "ABC", "PRIME VIDEO". */
    val networks: List<String> get() = network.orEmpty().split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
}
