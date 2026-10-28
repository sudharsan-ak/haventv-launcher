package com.aktvhome.launcher.ui.watch

import com.aktvhome.launcher.data.ESPN_APP
import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.GameState
import com.aktvhome.launcher.data.LiveGroup
import com.aktvhome.launcher.data.PEACOCK
import com.aktvhome.launcher.data.PRIME
import com.aktvhome.launcher.data.TV_CHANNELS
import com.aktvhome.launcher.data.TvItem
import com.aktvhome.launcher.data.carries
import com.aktvhome.launcher.data.streamingApps
import com.aktvhome.launcher.ui.HavenState

/** One way to watch a game, best first. [app] is just the app's name, for "Watch on <app>". */
data class WatchOption(val label: String, val app: String = label, val open: () -> Unit)

/**
 * What [watchOptions] reads and how it opens things, so it runs in Haven (the NBA cards) and in
 * the Home button service (alert banners) alike.
 */
class WatchSources(
    /** An installed app's label, or null when it isn't installed. */
    val labelOf: (String) -> String?,
    val localStations: Map<String, String>,
    /** Channels from your Live TV apps. */
    val live: List<LiveGroup>,
    /** Every other app's shared rows (ESPN's live shows). */
    val appRows: List<LiveGroup>,
    val openItem: (TvItem) -> Unit,
    val openApp: (String) -> Unit,
    val openGame: (Game) -> Unit,
)

fun HavenState.watchOptions(game: Game): List<WatchOption> = watchOptions(
    game,
    WatchSources(
        labelOf = { pkg -> apps[pkg]?.label },
        localStations = settingsStore.settings.localStations,
        live = watchNow.live,
        appRows = watchNow.appRows,
        openItem = watchNow::open,
        openApp = { pkg -> apps[pkg]?.let { launch(it) } },
        openGame = watchNow::openGame,
    ),
)

/**
 * Where [game] can be watched, from the networks ESPN lists for it:
 * - a national channel (ESPN, ABC, NBC, NBA TV…) in a live TV app that shares it, tuned directly;
 * - the ESPN app, on the exact stream when its Live row lists the game;
 * - Peacock for NBC/Peacock games, Prime Video for Prime games (these open the app; neither
 *   shares per-game links);
 * - the NBA app last, always, for League Pass, local channels and game info.
 * Finished games offer only the NBA app (replays).
 */
fun watchOptions(game: Game, src: WatchSources): List<WatchOption> {
    val nbaApp = WatchOption("NBA app", "NBA app") { src.openGame(game) }
    // Finished games: replays and recaps live in the NBA app. Upcoming games show where they'll air.
    if (game.state == GameState.FINAL) return listOf(nbaApp)

    val options = mutableListOf<WatchOption>()

    // National channels, tuned in whichever live TV app shares them (YouTube TV, Sling, …).
    // ABC/NBC/CBS/FOX appear under the local station's name, e.g. ABC = "WFAA 8".
    for (net in game.networks) {
        if (net !in TV_CHANNELS) continue
        val hit = src.live.flatMap { g -> g.items.map { g to it } }
            .filter { (_, item) -> carries(item.title, net, src.localStations) }
            // When a provider has multiple shared entries, prefer a live, directly linked one.
            .sortedWith(compareByDescending<Pair<LiveGroup, TvItem>> { it.second.live && !it.second.intentUri.isNullOrBlank() }
                .thenByDescending { !it.second.intentUri.isNullOrBlank() })
            .firstOrNull()
        if (hit != null) {
            val app = src.labelOf(hit.first.pkg) ?: hit.first.provider
            options += WatchOption("Watch on $app · ${hit.second.title}", app) { src.openItem(hit.second) }
        }
    }
    val streaming = streamingApps(game).filter { src.labelOf(it) != null }
    // ESPN app: the exact stream if its Live row lists this game, otherwise the app.
    if (ESPN_APP in streaming) {
        val event = (src.appRows + src.live).firstOrNull { it.pkg == ESPN_APP }?.items?.firstOrNull { mentions(it, game) }
        options += if (event != null) WatchOption("ESPN app · live stream", "ESPN") { src.openItem(event) }
        else WatchOption("ESPN app", "ESPN") { src.openApp(ESPN_APP) }
    }
    // If Peacock or Prime publishes this exact game to Android TV, use the actual
    // content intent rather than resuming an unrelated last-opened screen.
    for ((pkg, label) in listOf(PEACOCK to "Peacock", PRIME to "Prime Video")) {
        if (pkg !in streaming) continue
        val direct = (src.live + src.appRows).asSequence()
            .filter { it.pkg == pkg }
            .flatMap { it.items.asSequence() }
            .firstOrNull { !it.intentUri.isNullOrBlank() && mentions(it, game) }
        options += if (direct != null) WatchOption("$label · game", label) { src.openItem(direct) }
            else WatchOption(label) { src.openApp(pkg) }
    }

    options += nbaApp
    return options.distinctBy { it.label }
}

/** True when an ESPN Live item names both teams (by nickname or abbreviation). */
private fun mentions(item: TvItem, game: Game): Boolean {
    val text = "${item.title} ${item.detail}".uppercase()
    fun has(abbr: String, name: String) = name.uppercase() in text || Regex("\\b${Regex.escape(abbr)}\\b").containsMatchIn(text)
    return has(game.away.abbr, game.away.name) && has(game.home.abbr, game.home.name)
}
