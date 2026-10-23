package com.aktvhome.launcher.data

// Which apps and channels carry a game. Plain Kotlin, shared by Watch options and the alerts.

const val ESPN_APP = "com.espn.score_center"
const val PEACOCK = "com.peacocktv.peacockandroid"
const val PRIME = "com.amazon.amazonvideo.livingroom"
const val NBA_APP = "com.nbaimd.gametime.nba2011"
const val YOUTUBE_TV = "com.google.android.youtube.tvunplugged"

/** Networks that live TV apps carry as channels, as ESPN names them. */
val TV_CHANNELS = listOf("ESPN", "ESPN2", "ABC", "NBC", "CBS", "FOX", "TNT", "TRUTV", "NBA TV")

/** Broadcast networks that live TV apps show under a local station's name (set in Watch Now settings). */
val LOCAL_NETWORKS = listOf("ABC", "NBC", "CBS", "FOX")

/**
 * "ESPN" matches "ESPN" but not "ESPN2"; "NBA TV" matches "NBATV". Broadcast networks also match
 * their local station when it's named network + number, e.g. NBC = "NBC 5", FOX = "FOX 4".
 */
fun sameChannel(title: String, network: String): Boolean {
    val t = title.uppercase().replace(" ", "")
    val n = network.replace(" ", "")
    if (t == n || t == "${n}HD") return true
    return network in LOCAL_NETWORKS && Regex("^${Regex.escape(network)}\\s*\\d+$").matches(title.uppercase().trim())
}

/** True when a live TV channel named [title] carries [network], by name or as your local station. */
fun carries(title: String, network: String, localStations: Map<String, String>): Boolean {
    val station = localStations[network]
    return sameChannel(title, network) || (station != null && title.equals(station, ignoreCase = true))
}

/**
 * The app a network is watched in, for its icon in the games list: Prime Video, ESPN (also ABC),
 * Peacock (also NBC), YouTube TV for the other channels, the NBA app for League Pass.
 */
fun networkApp(network: String): String? {
    val n = network.uppercase()
    return when {
        n.contains("PRIME") || n.contains("AMAZON") -> PRIME
        n.startsWith("ESPN") || n == "ABC" -> ESPN_APP
        n.contains("PEACOCK") || n == "NBC" -> PEACOCK
        n.contains("LEAGUE PASS") -> NBA_APP
        n in TV_CHANNELS -> YOUTUBE_TV
        else -> null
    }
}

/** Streaming apps that carry [game], from its networks: ESPN (also for ABC), Peacock, Prime Video. */
fun streamingApps(game: Game): List<String> {
    val nets = game.networks
    return listOfNotNull(
        ESPN_APP.takeIf { nets.any { it.startsWith("ESPN") || it == "ABC" } },
        PEACOCK.takeIf { nets.any { it.contains("PEACOCK") || it == "NBC" } },
        PRIME.takeIf { nets.any { it.contains("PRIME") || it.contains("AMAZON") } },
    )
}
