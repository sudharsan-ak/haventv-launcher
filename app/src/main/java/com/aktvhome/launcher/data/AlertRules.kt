package com.aktvhome.launcher.data

import kotlin.math.abs

// When a game is worth interrupting the TV for. Plain Kotlin, unit tested; NbaAlerts feeds it
// ESPN polls and shows what comes out.

/** What an alert is about. [label] names it in settings, [headline] titles the banner. */
enum class AlertEvent(val label: String, val headline: String) {
    TIP_OFF("Tip-off", "Tip-off in 10 minutes"),
    HALFTIME("Halftime", "Halftime"),
    CLOSE_GAME("Close game", "We've got a close game!"),
    OVERTIME("Overtime", "Overtime!"),
    COMEBACK("Comeback", "Comeback brewing"),
    FINAL("Final", "Final"),
}

/** The events other teams' games can raise; the rest are for your team only. */
val OTHER_EVENTS = setOf(AlertEvent.CLOSE_GAME, AlertEvent.OVERTIME, AlertEvent.COMEBACK)

data class AlertPrefs(
    val on: Boolean = true,
    /** ESPN abbreviations of the teams you follow. */
    val followed: Set<String> = emptySet(),
    /** ESPN ids of single games you asked to be alerted about ("Set alert"); they count as followed. */
    val alertGames: Set<String> = emptySet(),
    /** Events for followed games. */
    val favoriteEvents: Set<AlertEvent> = AlertEvent.entries.toSet(),
    val otherEvents: Set<AlertEvent> = OTHER_EVENTS,
    /** Least time between two other-game alerts (overtime and the close-game escalation excepted). */
    val cooldownMs: Long = 10 * 60_000L,
) {
    fun follows(g: Game) = g.espnId in alertGames || g.away.abbr in followed || g.home.abbr in followed
}

/** [followed]: a followed team's game, or one with Set alert. */
data class Alert(val game: Game, val event: AlertEvent, val followed: Boolean)

/**
 * What the rules remember between polls. [sent] (alerts already raised, and games seen live),
 * [trail] (each team's worst deficit) and [lastOtherAt] are saved by the caller; [confirm] only
 * lives as long as the process.
 */
class AlertMemory(
    sent: Collection<String> = emptyList(),
    trail: Map<String, Int> = emptyMap(),
    var lastOtherAt: Long = 0L,
) {
    val sent = LinkedHashSet(sent)
    val trail = HashMap(trail)
    /** Close game and comeback candidates: how many polls in a row they've held. */
    val confirm = HashMap<String, Int>()
    var lastPollAt = 0L

    /** Keeps the newest [max] sent keys and drops deficits for games no longer polled. */
    fun prune(gameIds: Set<String>, max: Int = 400) {
        while (sent.size > max) sent.remove(sent.first())
        trail.keys.retainAll { it.substringBefore(':') in gameIds }
    }
}

/** A media session as Android reports it: which app, what it shows, and whether it's playing. */
data class NowPlaying(
    val pkg: String,
    val title: String?,
    /** YouTube TV puts the channel here. */
    val subtitle: String?,
    /** -1 when the app doesn't report one (live streams). */
    val position: Long,
    val playing: Boolean,
)

object AlertRules {
    /** Polls older than this never raise anything. */
    const val STALE_MS = 3 * 60_000L
    const val TIP_OFF_MS = 10 * 60_000L

    /**
     * Alerts for one poll of [games] fetched at [fetchedAt], followed games first. Updates
     * [memory]; skips (and uses up) alerts for games you're [watching].
     */
    fun evaluate(
        games: List<Game>,
        fetchedAt: Long,
        now: Long,
        prefs: AlertPrefs,
        memory: AlertMemory,
        watching: (Game) -> Boolean = { false },
    ): List<Alert> {
        if (!prefs.on || now - fetchedAt > STALE_MS) return emptyList()
        // "Two polls in a row" means two fresh ones; a gap starts the count again.
        if (memory.lastPollAt != 0L && now - memory.lastPollAt > STALE_MS) memory.confirm.clear()
        memory.lastPollAt = now

        val found = mutableListOf<Pair<Alert, String>>()
        val held = mutableSetOf<String>()
        for (g in games) {
            if (g.calledOff) continue
            val fav = prefs.follows(g)
            val allowed = if (fav) prefs.favoriteEvents else prefs.otherEvents intersect OTHER_EVENTS
            val id = g.espnId
            fun offer(event: AlertEvent, key: String, confirm: Boolean = false) {
                if (event !in allowed || key in memory.sent) return
                if (confirm) {
                    held += key
                    val polls = (memory.confirm[key] ?: 0) + 1
                    memory.confirm[key] = polls
                    if (polls < 2) return
                }
                found += Alert(g, event, fav) to key
            }
            when (g.state) {
                GameState.PRE -> {
                    val until = g.start.toEpochMilli() - now
                    if (until in 1..TIP_OFF_MS) offer(AlertEvent.TIP_OFF, "$id:TIP_OFF")
                }
                // Only for games seen live, so a game that ended before the TV came on stays quiet.
                GameState.FINAL -> if ("$id:LIVE" in memory.sent) offer(AlertEvent.FINAL, "$id:FINAL")
                GameState.LIVE -> {
                    memory.sent += "$id:LIVE"
                    if (g.halftime) offer(AlertEvent.HALFTIME, "$id:HALFTIME")
                    if (g.period > 4) offer(AlertEvent.OVERTIME, "$id:OVERTIME:${g.period}")
                    val away = g.away.score.toIntOrNull() ?: continue
                    val home = g.home.score.toIntOrNull() ?: continue
                    val margin = abs(away - home)
                    val left = clockSeconds(g.clock)
                    // The NBA's clutch time: the last 5 minutes of the 4th quarter or overtime, within 5.
                    if (g.period >= 4 && left != null && left <= 300 && margin <= 5) {
                        offer(AlertEvent.CLOSE_GAME, "$id:CLOSE_GAME", confirm = true)
                        // One more, only once the first is out and it's tighter still.
                        if (left <= 60 && margin <= 2 && "$id:CLOSE_GAME" in memory.sent) {
                            offer(AlertEvent.CLOSE_GAME, "$id:CLOSE_GAME:2", confirm = true)
                        }
                    }
                    for ((team, deficit) in listOf(g.away to home - away, g.home to away - home)) {
                        val worst = maxOf(memory.trail["$id:${team.abbr}"] ?: 0, deficit)
                        memory.trail["$id:${team.abbr}"] = worst
                        if (g.period >= 3 && worst >= 15 && deficit <= 5) offer(AlertEvent.COMEBACK, "$id:COMEBACK:${team.abbr}", confirm = true)
                    }
                }
            }
        }
        memory.confirm.keys.retainAll(held)

        val out = mutableListOf<Alert>()
        for ((alert, key) in found.sortedByDescending { it.first.followed }) {
            if (watching(alert.game)) {
                memory.sent += key
                continue
            }
            // Other games share one alert per cooldown; overtime and the close-game escalation always
            // get through. A held alert isn't used up, so it can still come once the cooldown ends
            // (if it still holds).
            val exempt = alert.event == AlertEvent.OVERTIME || key.endsWith(":CLOSE_GAME:2")
            if (!alert.followed && !exempt) {
                if (memory.lastOtherAt != 0L && now - memory.lastOtherAt < prefs.cooldownMs) continue
                memory.lastOtherAt = now
            }
            memory.sent += key
            out += alert
        }
        return out
    }

    /** "5:00" is 300, "45.2" is 45; null when ESPN sent nothing usable. */
    fun clockSeconds(clock: String): Int? {
        val c = clock.trim()
        if (c.isEmpty()) return null
        if (':' !in c) return c.toDoubleOrNull()?.toInt()
        val (m, s) = c.split(':', limit = 2)
        return (m.toIntOrNull() ?: return null) * 60 + (s.toDoubleOrNull()?.toInt() ?: return null)
    }

    /**
     * True when you're watching [game] right now:
     * - YouTube TV is on one of its networks (its subtitle is the channel);
     * - the NBA app is playing a live stream of either team, by nickname or city ("Bulls
     *   (In-Arena)", "Portland (Home)"); highlights have a position and no feed in brackets;
     * - the app in front carries the game (ESPN, Peacock, Prime Video) and is playing.
     */
    /** Require both teams for full-matchup titles; checking the entire string prevents a prefix mismatch. */
    private fun matchesMatchup(text: String, game: Game): Boolean {
        val normalized = text.uppercase().replace(Regex("[^A-Z0-9]+"), " ").trim()
        fun matches(team: Team): Boolean {
            val candidates = listOf(team.name, team.location, team.abbr).filter { it.length >= 2 }
            return candidates.any { term ->
                val word = term.uppercase().replace(Regex("[^A-Z0-9]+"), " ").trim()
                word.isNotBlank() && (" $normalized ").contains(" $word ")
            }
        }
        return matches(game.away) && matches(game.home)
    }

    fun isWatching(game: Game, sessions: List<NowPlaying>, foreground: String?, localStations: Map<String, String>): Boolean {
        val nets = game.networks
        for (s in sessions) {
            when (s.pkg) {
                YOUTUBE_TV -> {
                    if (!s.playing) continue
                    // YouTube TV may advertise the matchup rather than a channel subtitle.
                    if (s.title != null && matchesMatchup(s.title, game)) return true
                    val channel = s.subtitle ?: continue
                    if (nets.any { carries(channel, it, localStations) }) return true
                }
                NBA_APP -> {
                    val title = s.title ?: continue
                    // Some NBA app builds prepend "NBA" or "Live" to the matchup and
                    // report an ordinary seek position for live streams.
                    if (s.playing && matchesMatchup(title, game)) return true
                    val liveFeed = s.position == -1L || Regex("\\(.+\\)").containsMatchIn(title)
                    val names = listOf(game.away.name, game.home.name, game.away.location, game.home.location)
                    val team = names.any { it.isNotBlank() && title.startsWith(it, ignoreCase = true) }
                    if (liveFeed && team) return true
                }
            }
        }
        if (foreground == null || foreground !in streamingApps(game)) return false
        return sessions.any { it.pkg == foreground && it.playing }
    }
}
