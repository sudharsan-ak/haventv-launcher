package com.aktvhome.launcher.data

// Advanced stats for the player card, worked out from box score numbers. Plain Kotlin, unit tested.
// Each returns null when its denominator is 0 (no shots, no minutes yet), shown as "-".

object Advanced {

    /** True shooting %: points per two shooting possessions, PTS / (2 * (FGA + 0.44 * FTA)). */
    fun trueShooting(pts: Double, fga: Double, fta: Double): Double? {
        val shots = 2 * (fga + 0.44 * fta)
        return if (shots == 0.0) null else 100 * pts / shots
    }

    /** Effective FG %: threes count 1.5 field goals, (FGM + 0.5 * 3PM) / FGA. */
    fun effectiveFg(fgm: Double, threes: Double, fga: Double): Double? =
        if (fga == 0.0) null else 100 * (fgm + 0.5 * threes) / fga

    /**
     * Usage %: the share of the team's possessions a player used while on the floor,
     * 100 * ((FGA + 0.44 * FTA + TO) * (teamMIN / 5)) / (MIN * (teamFGA + 0.44 * teamFTA + teamTO)).
     */
    fun usage(fga: Double, fta: Double, to: Double, min: Double, teamMin: Double, teamFga: Double, teamFta: Double, teamTo: Double): Double? {
        val bottom = min * (teamFga + 0.44 * teamFta + teamTo)
        return if (bottom == 0.0) null else 100 * ((fga + 0.44 * fta + to) * (teamMin / 5)) / bottom
    }

    /**
     * John Hollinger's Game Score: PTS + 0.4 FGM - 0.7 FGA - 0.4 (FTA - FTM) + 0.7 OREB + 0.3 DREB
     * + STL + 0.7 AST + 0.7 BLK - 0.4 PF - TO.
     */
    fun gameScore(
        pts: Double, fgm: Double, fga: Double, ftm: Double, fta: Double, oreb: Double, dreb: Double,
        stl: Double, ast: Double, blk: Double, pf: Double, to: Double,
    ): Double = pts + 0.4 * fgm - 0.7 * fga - 0.4 * (fta - ftm) + 0.7 * oreb + 0.3 * dreb + stl + 0.7 * ast + 0.7 * blk - 0.4 * pf - to

    /** A ratio such as AST/TO or points per shot; null over 0. */
    fun ratio(top: Double, bottom: Double): Double? = if (bottom == 0.0) null else top / bottom

    /** One decimal, or "-" for a value that can't be worked out. */
    fun show(value: Double?): String = value?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "-"

    /** "5-10" (or "8.5-17.1" in averages) as made and attempted; null if it isn't that shape. */
    fun madeAttempted(text: String?): Pair<Double, Double>? {
        val parts = text?.split('-')?.takeIf { it.size == 2 } ?: return null
        val made = parts[0].trim().toDoubleOrNull() ?: return null
        val att = parts[1].trim().toDoubleOrNull() ?: return null
        return made to att
    }

    /** A plain number from a stat cell ("31", "35.2", "+7"); 0 when it's blank or "-". */
    fun number(text: String?): Double = text?.trim()?.removePrefix("+")?.toDoubleOrNull() ?: 0.0
}
