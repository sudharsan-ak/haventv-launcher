package com.aktvhome.launcher.data

/**
 * First-run sorting. Only used to seed collections the first time Haven sees an app;
 * after that the user's own moves always win.
 */
object Defaults {

    data class Seed(val id: String, val name: String, val packages: List<String>)

    const val OTHER_ID = "other"
    const val OTHER_NAME = "Other"
    const val INPUTS_ID = "inputs"
    const val INPUTS_NAME = "Inputs"
    /** Icon a newly created collection starts with. */
    const val NEW_ICON = "star"

    private val defaultIcons = mapOf(
        "streaming" to "tv",
        "sports" to "basketball",
        "livetv" to "cable",
        "video" to "play",
        "tools" to "wrench",
        "system" to "chip",
        INPUTS_ID to "hdmi",
        OTHER_ID to "dots",
    )

    fun iconFor(collectionId: String): String = defaultIcons[collectionId] ?: NEW_ICON

    val seeds = listOf(
        Seed(
            "streaming", "Streaming", listOf(
                "com.netflix.ninja",
                "com.amazon.amazonvideo.livingroom",
                "com.disney.disneyplus",
                "com.hulu.livingroomplus",
                "com.peacocktv.peacockandroid",
                "com.cbs.ott",
                "com.apple.atve.sony.appletv",
            )
        ),
        Seed(
            "sports", "Sports", listOf(
                "com.espn.score_center",
                "com.nbaimd.gametime.nba2011",
                "com.wnba.centercourt",
            )
        ),
        Seed(
            "livetv", "Live TV", listOf(
                "com.google.android.youtube.tvunplugged",
                "com.fox.foxone",
                "com.sony.dtv.tvx",
            )
        ),
        Seed(
            "video", "Video", listOf(
                "org.smarttube.stable",
                "com.ycngmn.notubetv",
                "com.instantbits.cast.receiver",
            )
        ),
        Seed(
            "tools", "Tools", listOf(
                "nextapp.fx",
                "com.android.vending",
                "com.sony.dtv.timers",
                "com.sec.android.app.sbrowser",
                "com.betafish.adblocksbrowser",
                "com.ext.ui",
            )
        ),
        Seed(
            "system", "System", listOf(
                "com.nordvpn.android",
                "com.adguard.android",
                "zank.remote",
                "flar2.homebutton",
                "com.sony.dtv.seeds.iot",
                "com.tvfilebridge.a11ywatchdog",
                "com.tvfilebridge.tvcompanion",
                "com.spocky.projengmenu",
            )
        ),
    )

    /** Built-in extras that start hidden. Unhide any of them from Settings. */
    val hiddenAtStart = setOf(
        "com.sony.dtv.sonyselect",
        "com.google.android.play.games",
        "com.google.android.videos",
        "com.sony.dtv.ecodashboard",
        "com.sony.dtv.notificationcenter",
        "com.sony.dtv.smartmediaapp",
        "com.sony.dtv.osat.music",
    )

    fun seedFor(pkg: String): String? = seeds.firstOrNull { pkg in it.packages }?.id

    /** Position in the seed lists, so first-run order matches what's written above. Unknown apps sort last. */
    fun seedRank(pkg: String): Int {
        var rank = 0
        for (s in seeds) for (p in s.packages) {
            if (p == pkg) return rank
            rank++
        }
        return Int.MAX_VALUE
    }
}
