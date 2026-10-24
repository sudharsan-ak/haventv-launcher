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
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    /** Western Conference teams (ESPN abbreviations); the rest of [TEAMS] are in the East. */
    val WEST = setOf("DAL", "DEN", "GS", "HOU", "LAC", "LAL", "MEM", "MIN", "NO", "OKC", "PHX", "POR", "SAC", "SA", "UTAH")

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

    /**
     * Everything the game details show, from ESPN's game summary: the game itself (latest score,
     * clock, records), line scores, plays, box score and team stats, and the season series.
     */
    fun summary(espnId: String): GameSummary {
        val o = JSONObject(get("$ESPN/summary?event=$espnId"))
        val header = o.getJSONObject("header")
        val comp = header.getJSONArray("competitions").getJSONObject(0)
        val game = runCatching {
            parseEvent(JSONObject().put("id", header.optString("id", espnId)).put("date", comp.getString("date")).put("competitions", JSONArray().put(comp)))
        }.getOrNull()

        val idToAbbr = mutableMapOf<String, String>()
        val lines = mutableMapOf<String, List<String>>()
        comp.optJSONArray("competitors")?.let { cs ->
            for (i in 0 until cs.length()) {
                val c = cs.getJSONObject(i)
                val team = c.getJSONObject("team")
                val abbr = team.optString("abbreviation")
                idToAbbr[team.optString("id")] = abbr
                c.optJSONArray("linescores")?.let { ls ->
                    lines[abbr] = (0 until ls.length()).map { k -> ls.getJSONObject(k).let { it.optString("displayValue").ifBlank { it.optString("value") } } }
                }
            }
        }

        val plays = o.optJSONArray("plays")?.let { ps ->
            (0 until ps.length()).mapNotNull { i ->
                val p = ps.getJSONObject(i)
                val text = p.optString("text").ifBlank { return@mapNotNull null }
                Play(
                    period = p.optJSONObject("period")?.optInt("number") ?: 0,
                    clock = p.optJSONObject("clock")?.optString("displayValue").orEmpty(),
                    text = text,
                    team = p.optJSONObject("team")?.optString("id")?.let { idToAbbr[it] },
                    awayScore = p.optString("awayScore"),
                    homeScore = p.optString("homeScore"),
                )
            }
        }.orEmpty()

        val box = o.optJSONObject("boxscore")
        val teamStats = mutableMapOf<String, Map<String, String>>()
        box?.optJSONArray("teams")?.let { ts ->
            for (i in 0 until ts.length()) {
                val t = ts.getJSONObject(i)
                val abbr = t.getJSONObject("team").optString("abbreviation")
                val stats = mutableMapOf<String, String>()
                t.optJSONArray("statistics")?.let { st ->
                    for (k in 0 until st.length()) {
                        val e = st.getJSONObject(k)
                        val v = e.optString("displayValue")
                        // Findable by any of its names: "fieldGoalPct", "Field Goal %", "FG%".
                        listOf("name", "label", "abbreviation").forEach { key -> e.optString(key).takeIf { it.isNotBlank() }?.let { stats[it] = v } }
                    }
                }
                teamStats[abbr] = stats
            }
        }
        val players = mutableMapOf<String, List<BoxPlayer>>()
        box?.optJSONArray("players")?.let { ts ->
            for (i in 0 until ts.length()) {
                val t = ts.getJSONObject(i)
                val abbr = t.getJSONObject("team").optString("abbreviation")
                val st = t.optJSONArray("statistics")?.optJSONObject(0) ?: continue
                val labels = st.optJSONArray("labels")?.strings().orEmpty()
                val athletes = st.optJSONArray("athletes") ?: continue
                players[abbr] = (0 until athletes.length()).mapNotNull { k ->
                    val a = athletes.getJSONObject(k)
                    val stats = a.optJSONArray("stats")?.strings().orEmpty()
                    if (stats.isEmpty()) return@mapNotNull null
                    val who = a.getJSONObject("athlete")
                    BoxPlayer(
                        name = who.optString("displayName"),
                        position = who.optJSONObject("position")?.optString("abbreviation").orEmpty(),
                        headshot = who.optJSONObject("headshot")?.optString("href")?.ifBlank { null },
                        stats = labels.zip(stats).toMap(),
                        id = who.optString("id"),
                    )
                }
            }
        }
        val abbrs = (lines.keys + teamStats.keys + players.keys).distinct()
        val series = o.optJSONArray("seasonseries")?.optJSONObject(0)?.let { it.optString("summary").ifBlank { it.optString("description") } }?.ifBlank { null }
        // The recap article, when ESPN has one: its short description, else the story's first paragraph.
        val recap = o.optJSONObject("article")?.let { a ->
            a.optString("description").ifBlank { null }
                ?: a.optString("story").let { html -> Regex("<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: html }
                    .replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&amp;", "&").trim().ifBlank { null }
        }
        return GameSummary(
            game = game,
            lines = lines,
            plays = plays,
            teams = abbrs.associateWith { TeamBox(players[it].orEmpty(), teamStats[it].orEmpty()) },
            series = series,
            recap = recap,
        )
    }

    private const val ATHLETES = "https://site.web.api.espn.com/apis/common/v3/sports/basketball/nba/athletes"

    /** Name, jersey, position, team and photo. */
    fun playerProfile(id: String): PlayerProfile? {
        val a = JSONObject(get("$ATHLETES/$id")).optJSONObject("athlete") ?: return null
        return PlayerProfile(
            name = a.optString("displayName"),
            jersey = a.optString("jersey"),
            position = a.optJSONObject("position")?.optString("displayName").orEmpty(),
            team = a.optJSONObject("team")?.optString("displayName").orEmpty(),
            headshot = a.optJSONObject("headshot")?.optString("href")?.ifBlank { null },
        )
    }

    /**
     * This season and career, from the stats page's "averages", "totals" and "miscellaneous"
     * categories (each has a row per season, newest last, and a career row in "totals"). Falls
     * back to the overview's Regular Season and Career averages.
     */
    fun playerSeason(id: String): PlayerSeason? {
        val cats = runCatching { JSONObject(get("$ATHLETES/$id/stats")).optJSONArray("categories") }.getOrNull()
        fun rows(name: String): Pair<StatRow, StatRow> {
            val c = cats?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name") == name } }
                ?: return emptyMap<String, String>() to emptyMap()
            val labels = c.optJSONArray("labels")?.strings().orEmpty()
            val seasons = c.optJSONArray("statistics")
            val latest = seasons?.optJSONObject(seasons.length() - 1)?.optJSONArray("stats")?.strings().orEmpty()
            val career = c.optJSONArray("totals")?.strings().orEmpty()
            return labels.zip(latest).toMap() to labels.zip(career).toMap()
        }
        val (avg, careerAvg) = rows("averages")
        val (tot, careerTot) = rows("totals")
        val (misc, careerMisc) = rows("miscellaneous")
        if (avg.isNotEmpty() || careerAvg.isNotEmpty()) return PlayerSeason(avg, careerAvg, tot, careerTot, misc, careerMisc)
        val st = runCatching { JSONObject(get("$ATHLETES/$id/overview")).optJSONObject("statistics") }.getOrNull() ?: return null
        val labels = st.optJSONArray("labels")?.strings().orEmpty()
        val splits = st.optJSONArray("splits") ?: return null
        fun split(name: String): StatRow = (0 until splits.length()).map { splits.getJSONObject(it) }
            .firstOrNull { it.optString("displayName") == name }?.optJSONArray("stats")?.strings()?.let { labels.zip(it).toMap() }.orEmpty()
        return PlayerSeason(split("Regular Season"), split("Career"), emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }

    /**
     * The splits worth a glance: all games, home, road, the latest month and as a starter, in
     * that order; any ESPN doesn't have are left out.
     */
    fun playerSplits(id: String): List<PlayerSplit> {
        val o = JSONObject(get("$ATHLETES/$id/splits"))
        val topLabels = o.optJSONArray("labels")?.strings().orEmpty()
        val cats = o.optJSONArray("splitCategories") ?: return emptyList()
        val all = mutableListOf<Triple<String, String, StatRow>>()
        for (i in 0 until cats.length()) {
            val c = cats.getJSONObject(i)
            val labels = c.optJSONArray("labels")?.strings() ?: topLabels
            val splits = c.optJSONArray("splits") ?: continue
            for (k in 0 until splits.length()) {
                val sp = splits.getJSONObject(k)
                val stats = sp.optJSONArray("stats")?.strings() ?: continue
                all += Triple(c.optString("name").lowercase(), sp.optString("displayName"), labels.zip(stats).toMap())
            }
        }
        fun named(vararg names: String) = all.firstOrNull { (_, n, _) -> names.any { n.equals(it, ignoreCase = true) } }
        return listOfNotNull(
            named("All Splits")?.let { PlayerSplit("All games", it.third) },
            named("Home")?.let { PlayerSplit("Home", it.third) },
            named("Road", "Away")?.let { PlayerSplit("Road", it.third) },
            all.lastOrNull { it.first == "month" }?.let { PlayerSplit("Last month (${it.second})", it.third) },
            named("As Starter", "Starter")?.let { PlayerSplit("As starter", it.third) },
        )
    }

    /** The last [count] games: date, opponent ("@ BOS", "vs MIA") and the stat line. */
    fun playerLog(id: String, count: Int = 5): List<GameLogRow> {
        val o = JSONObject(get("$ATHLETES/$id/gamelog"))
        val labels = o.optJSONArray("labels")?.strings().orEmpty()
        val events = o.optJSONObject("events")
        val types = o.optJSONArray("seasonTypes") ?: return emptyList()
        val out = mutableListOf<GameLogRow>()
        for (t in 0 until types.length()) {
            val cats = types.getJSONObject(t).optJSONArray("categories") ?: continue
            for (c in 0 until cats.length()) {
                val evs = cats.getJSONObject(c).optJSONArray("events") ?: continue
                for (e in 0 until evs.length()) {
                    if (out.size >= count) return out
                    val row = evs.getJSONObject(e)
                    val info = events?.optJSONObject(row.optString("eventId"))
                    val date = info?.optString("gameDate")?.let(::parseTime)?.atZone(ZoneId.systemDefault())?.toLocalDate()
                    val opp = info?.optJSONObject("opponent")?.optString("abbreviation").orEmpty()
                    val at = info?.optString("atVs").orEmpty()
                    out += GameLogRow(date, "$at $opp".trim(), labels.zip(row.optJSONArray("stats")?.strings().orEmpty()).toMap())
                }
            }
            // The first season type with games is the one in progress.
            if (out.isNotEmpty()) return out
        }
        return out
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
                location = team.optString("location"),
                // Scoreboards call it "records", game summaries "record".
                record = (c.optJSONArray("records") ?: c.optJSONArray("record"))?.optJSONObject(0)?.optString("summary").orEmpty(),
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

    /** ESPN's times: "2025-10-23T23:30Z", with seconds, or with an offset. */
    private fun parseTime(s: String): Instant? =
        runCatching { Instant.parse(if (s.length == 17) s.replace("Z", ":00Z") else s) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()

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
