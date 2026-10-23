package com.aktvhome.launcher.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One player line in the game detail box score. */
data class PlayerLine(val name: String, val pts: String, val reb: String, val ast: String)

data class BoxScore(val away: List<PlayerLine>, val home: List<PlayerLine>)

/**
 * NBA scores from ESPN's public (unofficial) site API, and NBA's own game IDs from nba.com's
 * games page, which the NBA app's links need. The two IDs differ and are matched by teams + date.
 * Every call can fail (no network, layout change); callers treat failure as "no data".
 */
object Nba {

    /** All 30 teams: ESPN abbreviation to full name, for the favorite-team picker. */
    val TEAMS = listOf(
        "ATL" to "Atlanta Hawks", "BOS" to "Boston Celtics", "BKN" to "Brooklyn Nets",
        "CHA" to "Charlotte Hornets", "CHI" to "Chicago Bulls", "CLE" to "Cleveland Cavaliers",
        "DAL" to "Dallas Mavericks", "DEN" to "Denver Nuggets", "DET" to "Detroit Pistons",
        "GS" to "Golden State Warriors", "HOU" to "Houston Rockets", "IND" to "Indiana Pacers",
        "LAC" to "LA Clippers", "LAL" to "Los Angeles Lakers", "MEM" to "Memphis Grizzlies",
        "MIA" to "Miami Heat", "MIL" to "Milwaukee Bucks", "MIN" to "Minnesota Timberwolves",
        "NO" to "New Orleans Pelicans", "NY" to "New York Knicks", "OKC" to "Oklahoma City Thunder",
        "ORL" to "Orlando Magic", "PHI" to "Philadelphia 76ers", "PHX" to "Phoenix Suns",
        "POR" to "Portland Trail Blazers", "SAC" to "Sacramento Kings", "SA" to "San Antonio Spurs",
        "TOR" to "Toronto Raptors", "UTAH" to "Utah Jazz", "WSH" to "Washington Wizards",
    )

    fun teamName(abbr: String) = TEAMS.firstOrNull { it.first == abbr }?.second ?: abbr

    /** The league's logo; loaded at runtime like all logos (never shipped with the app). */
    const val LEAGUE_LOGO = "https://a.espncdn.com/i/teamlogos/leagues/500/nba.png"

    /** A team's logo: the one ESPN sent with the game, or its standard address by abbreviation. */
    fun logo(abbr: String, fromFeed: String? = null): String =
        fromFeed ?: "https://a.espncdn.com/i/teamlogos/nba/500/${abbr.lowercase()}.png"

    fun logo(team: Team): String = logo(team.abbr, team.logo)

    /** ESPN abbreviations that differ from NBA.com's tricodes. */
    private val NBA_CODE = mapOf("GS" to "gsw", "NY" to "nyk", "SA" to "sas", "NO" to "nop", "UTAH" to "uta", "WSH" to "was")
    private fun nbaCode(espn: String) = NBA_CODE[espn] ?: espn.lowercase()

    private const val ESPN = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba"
    private val ymd = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")

    fun scoreboard(date: LocalDate): List<Game> {
        val o = JSONObject(get("$ESPN/scoreboard?dates=${date.format(ymd)}"))
        val events = o.optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { runCatching { parseEvent(events.getJSONObject(it)) }.getOrNull() }
            .sortedWith(compareBy({ it.state != GameState.LIVE }, { it.start }))
    }

    /** A team's season schedule (ESPN regular season), oldest first. */
    fun schedule(abbr: String): List<Game> {
        val o = JSONObject(get("$ESPN/teams/${abbr.lowercase()}/schedule"))
        val events = o.optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { runCatching { parseEvent(events.getJSONObject(it)) }.getOrNull() }
            .sortedBy { it.start }
    }

    /** Top 5 scorers per team from the game summary, for the game detail view. */
    fun boxScore(espnId: String, game: Game): BoxScore? {
        val o = JSONObject(get("$ESPN/summary?event=$espnId"))
        val teams = o.optJSONObject("boxscore")?.optJSONArray("players") ?: return null
        var away = emptyList<PlayerLine>()
        var home = emptyList<PlayerLine>()
        for (t in 0 until teams.length()) {
            val team = teams.getJSONObject(t)
            val abbr = team.getJSONObject("team").optString("abbreviation")
            val stats = team.optJSONArray("statistics")?.optJSONObject(0) ?: continue
            val labels = stats.getJSONArray("labels").strings()
            val pi = labels.indexOf("PTS"); val ri = labels.indexOf("REB"); val ai = labels.indexOf("AST")
            val athletes = stats.optJSONArray("athletes") ?: continue
            val lines = (0 until athletes.length()).mapNotNull { i ->
                val a = athletes.getJSONObject(i)
                val s = a.optJSONArray("stats")?.strings() ?: return@mapNotNull null
                if (s.isEmpty() || pi < 0) return@mapNotNull null
                PlayerLine(a.getJSONObject("athlete").optString("shortName"), s.getOrElse(pi) { "0" }, s.getOrElse(ri) { "-" }, s.getOrElse(ai) { "-" })
            }.sortedByDescending { it.pts.toIntOrNull() ?: -1 }.take(5)
            if (abbr == game.away.abbr) away = lines else if (abbr == game.home.abbr) home = lines
        }
        return if (away.isEmpty() && home.isEmpty()) null else BoxScore(away, home)
    }

    /**
     * NBA's own game ID (e.g. 0022600007) for [game], from nba.com's games page for that day.
     * Null when the page can't be read or no game matches both teams.
     */
    fun nbaGameId(game: Game): String? {
        // nba.com lists games by US Eastern date.
        val day = game.start.atZone(ZoneId.of("America/New_York")).toLocalDate()
        val html = get("https://www.nba.com/games?date=$day", browser = true)
        val want = "${nbaCode(game.away.abbr)}-vs-${nbaCode(game.home.abbr)}-"
        return Regex("/game/([a-z]{3})-vs-([a-z]{3})-(\\d{10})").findAll(html)
            .firstOrNull { it.value.removePrefix("/game/").startsWith(want) }?.groupValues?.get(3)
    }

    /** Opens the game's page in the NBA app; falls back to its games list, then the app itself. */
    fun openInApp(context: Context, nbaGameId: String?): Boolean {
        val pkg = NBA_APP
        val links = listOfNotNull(nbaGameId?.let { "gametime://game/$it" }, "gametime://games")
        for (link in links) {
            val ok = runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        val launch = context.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    private fun parseEvent(e: JSONObject): Game {
        val comp = e.getJSONArray("competitions").getJSONObject(0)
        val statusObj = comp.optJSONObject("status") ?: e.getJSONObject("status")
        val statusType = statusObj.getJSONObject("type")
        val start = Instant.parse(e.getString("date").let { if (it.length == 17) it.replace("Z", ":00Z") else it })
        val state = when (statusType.optString("state")) {
            "in" -> GameState.LIVE
            "post" -> GameState.FINAL
            else -> GameState.PRE
        }
        var away: Team? = null
        var home: Team? = null
        val cs = comp.getJSONArray("competitors")
        for (i in 0 until cs.length()) {
            val c = cs.getJSONObject(i)
            val team = c.getJSONObject("team")
            // Scoreboard gives "score" as a string; team schedules give an object with displayValue.
            val score = c.optJSONObject("score")?.optString("displayValue") ?: c.optString("score")
            val leader = c.optJSONArray("leaders")?.let { ls ->
                (0 until ls.length()).map { ls.getJSONObject(it) }.firstOrNull { it.optString("name") == "points" }
                    ?.optJSONArray("leaders")?.optJSONObject(0)
                    ?.let { l -> "${l.getJSONObject("athlete").optString("shortName")} ${l.optString("displayValue")}" }
            }
            val t = Team(
                team.optString("abbreviation"), team.optString("shortDisplayName", team.optString("displayName")),
                score, team.optString("color", "5b7bff"), leader,
                logo = team.optString("logo").ifBlank { null },
            )
            if (c.optString("homeAway") == "home") home = t else away = t
        }
        val local = start.atZone(ZoneId.systemDefault())
        val status = when (state) {
            GameState.PRE -> local.format(timeFmt)
            else -> statusType.optString("shortDetail").ifBlank { statusType.optString("description") }
        }
        val network = comp.optJSONArray("broadcasts")?.let { b ->
            (0 until b.length()).flatMap { i -> b.getJSONObject(i).optJSONArray("names")?.strings().orEmpty() }
        }?.plus(
            // Team schedules list broadcasts differently.
            comp.optJSONArray("broadcasts")?.let { b ->
                (0 until b.length()).mapNotNull { i -> b.getJSONObject(i).optJSONObject("media")?.optString("shortName") }
            }.orEmpty(),
        )?.filter { it.isNotBlank() }?.distinct()?.joinToString(", ")?.ifBlank { null }
        val name = statusType.optString("name")
        return Game(
            e.getString("id"), start, away!!, home!!, state, status, network,
            period = statusObj.optInt("period"),
            clock = statusObj.optString("displayClock"),
            halftime = name == "STATUS_HALFTIME",
            calledOff = name == "STATUS_POSTPONED" || name == "STATUS_CANCELED" || name == "STATUS_CANCELLED",
        )
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }

    private fun get(url: String, browser: Boolean = false): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        // nba.com rejects requests that don't look like a browser.
        conn.setRequestProperty(
            "User-Agent",
            if (browser) "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
            else "HavenTV/1.0 (personal TV launcher)",
        )
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
