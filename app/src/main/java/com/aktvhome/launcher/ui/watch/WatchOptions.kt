package com.aktvhome.launcher.ui.watch

import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.GameState
import com.aktvhome.launcher.data.TvItem
import com.aktvhome.launcher.ui.HavenState
import java.time.Duration
import java.time.Instant

/** One way to watch a game, best first. */
data class WatchOption(val label: String, val open: () -> Unit)

private const val ESPN_APP = "com.espn.score_center"
private const val PEACOCK = "com.peacocktv.peacockandroid"
private const val PRIME = "com.amazon.amazonvideo.livingroom"

/** Networks that live TV apps carry as channels, as ESPN names them. */
private val TV_CHANNELS = listOf("ESPN", "ESPN2", "ABC", "NBC", "CBS", "FOX", "TNT", "TRUTV", "NBA TV")

/** Broadcast networks that live TV apps show under a local station's name (set in Watch Now settings). */
val LOCAL_NETWORKS = listOf("ABC", "NBC", "CBS", "FOX")

/**
 * Where [game] can be watched, from the networks ESPN lists for it:
 * - a national channel (ESPN, ABC, NBC, NBA TV…) in a live TV app that shares it, tuned directly;
 * - the ESPN app, on the exact stream when its Live row lists the game;
 * - Peacock for NBC/Peacock games, Prime Video for Prime games (these open the app; neither
 *   shares per-game links);
 * - the NBA app last, always, for League Pass, local channels and game info.
 * Watch options only appear from an hour before tip-off until the game ends.
 */
fun HavenState.watchOptions(game: Game): List<WatchOption> {
    val nbaApp = WatchOption("NBA app") { watchNow.openGame(game) }
    val soon = game.state == GameState.LIVE ||
        (game.state == GameState.PRE && Duration.between(Instant.now(), game.start).toMinutes() <= 60)
    if (!soon) return listOf(nbaApp)

    val networks = game.network.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val upper = networks.map { it.uppercase() }
    val options = mutableListOf<WatchOption>()

    // National channels, tuned in whichever live TV app shares them (YouTube TV, Sling, …).
    val local = settingsStore.settings.localStations
    for (net in upper) {
        if (net !in TV_CHANNELS) continue
        // ABC/NBC/CBS/FOX appear under the local station's name, e.g. ABC = "WFAA 8".
        val station = local[net]
        val hit = watchNow.live.flatMap { g -> g.items.map { g to it } }.firstOrNull { (_, item) ->
            sameChannel(item.title, net) || (station != null && item.title.equals(station, ignoreCase = true))
        }
        if (hit != null) options += WatchOption("Watch on ${apps[hit.first.pkg]?.label ?: hit.first.provider} · ${hit.second.title}") { watchNow.open(hit.second) }
    }
    // ESPN app: the exact stream if its Live row lists this game, otherwise the app.
    if (upper.any { it.startsWith("ESPN") || it == "ABC" } && ESPN_APP in apps) {
        val event = (watchNow.appRows + watchNow.live).firstOrNull { it.pkg == ESPN_APP }?.items?.firstOrNull { mentions(it, game) }
        options += if (event != null) WatchOption("ESPN app · live stream") { watchNow.open(event) }
        else WatchOption("ESPN app") { openPackage(ESPN_APP) }
    }
    if (upper.any { it.contains("PEACOCK") || it == "NBC" } && PEACOCK in apps) options += WatchOption("Peacock") { openPackage(PEACOCK) }
    if (upper.any { it.contains("PRIME") || it.contains("AMAZON") } && PRIME in apps) options += WatchOption("Prime Video") { openPackage(PRIME) }

    options += nbaApp
    return options.distinctBy { it.label }
}

/** "ESPN" matches "ESPN" but not "ESPN2"; "NBA TV" matches "NBATV". */
private fun sameChannel(title: String, network: String): Boolean {
    val t = title.uppercase().replace(" ", "")
    val n = network.replace(" ", "")
    return t == n || t == "$n HD".replace(" ", "")
}

/** True when an ESPN Live item names both teams (by nickname or abbreviation). */
private fun mentions(item: TvItem, game: Game): Boolean {
    val text = "${item.title} ${item.detail}".uppercase()
    fun has(abbr: String, name: String) = name.uppercase() in text || Regex("\\b${Regex.escape(abbr)}\\b").containsMatchIn(text)
    return has(game.away.abbr, game.away.name) && has(game.home.abbr, game.home.name)
}

private fun HavenState.openPackage(pkg: String) {
    apps[pkg]?.let { launch(it) }
}
