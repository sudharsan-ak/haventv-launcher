package com.aktvhome.launcher.data

import java.time.LocalDate

// What the player card shows beyond the live box score. Plain Kotlin.

/** A row of stats by ESPN's labels: "PTS" to "25.3", "FG" to "9.1-18.4". */
typealias StatRow = Map<String, String>

data class PlayerProfile(val name: String, val jersey: String, val position: String, val team: String, val headshot: String?)

/** This season's and the career's averages, totals and ESPN's miscellaneous ratios. */
data class PlayerSeason(
    val averages: StatRow,
    val careerAverages: StatRow,
    val totals: StatRow,
    val careerTotals: StatRow,
    val misc: StatRow,
    val careerMisc: StatRow,
)

data class PlayerSplit(val name: String, val stats: StatRow)

data class GameLogRow(val date: LocalDate?, val opponent: String, val stats: StatRow)

/** Everything fetched for a player once per session; any part may be missing. */
data class PlayerInfo(
    val profile: PlayerProfile?,
    val season: PlayerSeason?,
    val splits: List<PlayerSplit>,
    val log: List<GameLogRow>,
)
