package com.aktvhome.launcher.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeId(val label: String) { KINGDOM("Kingdom"), TIDE("Tide") }

enum class DayMode(val label: String) { AUTO("Auto (6 AM / 6 PM)"), DAY("Always day"), NIGHT("Always night") }

enum class Motion(val label: String) { FULL("Full"), REDUCED("Reduced"), OFF("Off") }

/** App card size. [width] is Kingdom's 16:9 card width in dp; [scale] sizes Tide's dock icons. */
/** [columns]: how many Kingdom app cards fit on one line before wrapping to the next. */
enum class CardSize(val label: String, val width: Int, val scale: Float, val columns: Int) {
    SMALL("Small", 136, 0.8f, 6),
    MEDIUM("Medium", 156, 0.9f, 5),
    LARGE("Large", 176, 1f, 4),
}

/** What sits behind a theme's layout: its own animated scene, or a rotating photo. */
enum class Background(val label: String) { SCENE("Scene"), PHOTO("Photo") }

enum class WallSource(val label: String, val note: String) {
    BING("Bing", "Bing's photo of the day and the past week"),
    EARTH("Earth View", "1,500 satellite photos from Google"),
    WIKI("Wikimedia", "Picture of the Day, landscape ones only"),
    UNSPLASH("Unsplash", "Your topics, needs your free key"),
}

enum class WallInterval(val label: String, val millis: Long) {
    HOUR("Every hour", 3_600_000L),
    SIX("Every 6 hours", 6 * 3_600_000L),
    TWELVE("Every 12 hours", 12 * 3_600_000L),
    DAY("Daily", 24 * 3_600_000L),
}

/** Watch Now sections, id to label, in their default order. */
val WATCH_SECTIONS = listOf("nba" to "NBA", "live" to "Live channels", "continue" to "Continue watching")

/** Unsplash topics: label shown, search sent. */
val WALL_TOPICS = listOf(
    "Nature" to "nature landscape",
    "Animals" to "wild animals",
    "Places" to "travel landmark",
    "Cities" to "city skyline",
    "Architecture" to "architecture",
    "Sports" to "sports",
    "Space" to "space galaxy",
    "Ocean" to "ocean",
    "Mountains" to "mountains",
    "Forests" to "forest",
)

data class Settings(
    val theme: ThemeId = ThemeId.KINGDOM,
    val dayMode: DayMode = DayMode.AUTO,
    val motion: Motion = Motion.FULL,
    val clock24: Boolean = false,
    /** Name used in the greeting. Empty means "Good evening" with no name. */
    val name: String = "",
    val showGreeting: Boolean = true,
    /** Kingdom's headline follows your team's game and the time of day; off keeps it fixed. */
    val headlineChanging: Boolean = true,
    val cardSize: CardSize = CardSize.MEDIUM,
    /** The Private DNS the user picked (what "Keep enforced" restores). */
    val dnsChoice: DnsChoice = DnsChoice.AUTO,
    /** The last filtering provider picked; what the Quick settings switch turns on. */
    val dnsProvider: DnsChoice = DnsChoice.ADGUARD,
    val dnsCustom: String = "",
    val dnsEnforce: Boolean = false,
    val bgKingdom: Background = Background.SCENE,
    val bgTide: Background = Background.SCENE,
    val wallSources: Set<WallSource> = setOf(WallSource.BING, WallSource.EARTH),
    /** Labels from [WALL_TOPICS]. */
    val wallTopics: Set<String> = setOf("Nature", "Animals", "Places"),
    val wallInterval: WallInterval = WallInterval.SIX,
    val wallCredit: Boolean = true,
    val unsplashKey: String = "",
    /** Watch Now sections. NBA is a personal module and starts off for everyone. */
    val watchNba: Boolean = false,
    val watchContinue: Boolean = true,
    val watchLive: Boolean = true,
    /** ESPN team abbreviation of the favorite team ("" = none), e.g. "GS". */
    val favoriteTeam: String = "",
    /** Local station for each broadcast network, e.g. "ABC" to "WFAA 8", as the live TV app names it. */
    val localStations: Map<String, String> = emptyMap(),
    /** Order of the Watch Now cards and drawer sections: ids from [WATCH_SECTIONS]. */
    val watchOrder: List<String> = WATCH_SECTIONS.map { it.first },
    /** NBA alerts over any app. Off until you turn them on, like the NBA section. */
    val nbaAlerts: Boolean = false,
    val alertsFavorite: Set<AlertEvent> = AlertEvent.entries.toSet(),
    val alertsOther: Set<AlertEvent> = OTHER_EVENTS,
    /** Banners leave out scores and results. */
    val hideScores: Boolean = false,
    /** Minutes between two other-team alerts. */
    val alertCooldown: Int = 10,
) {
    fun alertPrefs() = AlertPrefs(nbaAlerts, favoriteTeam, alertsFavorite, alertsOther, alertCooldown * 60_000L)

    fun backgroundFor(theme: ThemeId) = when (theme) {
        ThemeId.KINGDOM -> bgKingdom
        ThemeId.TIDE -> bgTide
    }
}

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var settings by mutableStateOf(load())
        private set

    /** Re-reads everything from storage (after a restore or reset). */
    fun reload() {
        settings = load()
    }

    fun update(change: (Settings) -> Settings) {
        val next = change(settings)
        settings = next
        prefs.edit()
            .putString("theme", next.theme.name)
            .putString("dayMode", next.dayMode.name)
            .putString("motion", next.motion.name)
            .putBoolean("clock24", next.clock24)
            .putString("name", next.name)
            .putBoolean("showGreeting", next.showGreeting)
            .putBoolean("headlineChanging", next.headlineChanging)
            .putString("cardSize", next.cardSize.name)
            .putString("dnsChoice", next.dnsChoice.name)
            .putString("dnsProvider", next.dnsProvider.name)
            .putString("dnsCustom", next.dnsCustom)
            .putBoolean("dnsEnforce", next.dnsEnforce)
            .putString("bgKingdom", next.bgKingdom.name)
            .putString("bgTide", next.bgTide.name)
            .putString("wallSources", next.wallSources.joinToString(",") { it.name })
            .putString("wallTopics", next.wallTopics.joinToString(","))
            .putString("wallInterval", next.wallInterval.name)
            .putBoolean("wallCredit", next.wallCredit)
            .putString("unsplashKey", next.unsplashKey)
            .putBoolean("watchNba", next.watchNba)
            .putBoolean("watchContinue", next.watchContinue)
            .putBoolean("watchLive", next.watchLive)
            .putString("favoriteTeam", next.favoriteTeam)
            .putString("localStations", next.localStations.entries.joinToString("\n") { "${it.key}=${it.value}" })
            .putString("watchOrder", next.watchOrder.joinToString(","))
            .putBoolean("nbaAlerts", next.nbaAlerts)
            .putString("alertsFavorite", next.alertsFavorite.joinToString(",") { it.name })
            .putString("alertsOther", next.alertsOther.joinToString(",") { it.name })
            .putBoolean("hideScores", next.hideScores)
            .putInt("alertCooldown", next.alertCooldown)
            .apply()
    }

    companion object {
        /** DNS settings straight from storage, for code that runs without the UI (boot). */
        fun readDns(context: Context): DnsPrefs {
            val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            return DnsPrefs(
                choice = p.getString("dnsChoice", null)?.let { n -> DnsChoice.entries.firstOrNull { it.name == n } } ?: DnsChoice.AUTO,
                custom = p.getString("dnsCustom", null) ?: "",
                enforce = p.getBoolean("dnsEnforce", false),
            )
        }
    }

    private fun load() = Settings(
        theme = enumOr(prefs.getString("theme", null), ThemeId.KINGDOM),
        dayMode = enumOr(prefs.getString("dayMode", null), DayMode.AUTO),
        motion = enumOr(prefs.getString("motion", null), Motion.FULL),
        clock24 = prefs.getBoolean("clock24", false),
        name = prefs.getString("name", null) ?: "",
        showGreeting = prefs.getBoolean("showGreeting", true),
        headlineChanging = prefs.getBoolean("headlineChanging", true),
        cardSize = enumOr(prefs.getString("cardSize", null), CardSize.MEDIUM),
        dnsChoice = enumOr(prefs.getString("dnsChoice", null), DnsChoice.AUTO),
        dnsProvider = enumOr(prefs.getString("dnsProvider", null), DnsChoice.ADGUARD),
        dnsCustom = prefs.getString("dnsCustom", null) ?: "",
        dnsEnforce = prefs.getBoolean("dnsEnforce", false),
        bgKingdom = enumOr(prefs.getString("bgKingdom", null), Background.SCENE),
        bgTide = enumOr(prefs.getString("bgTide", null), Background.SCENE),
        wallSources = prefs.getString("wallSources", null)
            ?.split(",")?.mapNotNull { n -> WallSource.entries.firstOrNull { it.name == n } }?.toSet()
            ?: setOf(WallSource.BING, WallSource.EARTH),
        wallTopics = prefs.getString("wallTopics", null)?.split(",")?.filter { it.isNotBlank() }?.toSet()
            ?: setOf("Nature", "Animals", "Places"),
        wallInterval = enumOr(prefs.getString("wallInterval", null), WallInterval.SIX),
        wallCredit = prefs.getBoolean("wallCredit", true),
        unsplashKey = prefs.getString("unsplashKey", null) ?: "",
        watchNba = prefs.getBoolean("watchNba", false),
        watchContinue = prefs.getBoolean("watchContinue", true),
        watchLive = prefs.getBoolean("watchLive", true),
        favoriteTeam = prefs.getString("favoriteTeam", null) ?: "",
        localStations = (prefs.getString("localStations", null) ?: "").lines().mapNotNull { line ->
            line.split('=', limit = 2).takeIf { it.size == 2 && it[1].isNotBlank() }?.let { it[0] to it[1] }
        }.toMap(),
        // Keep any saved order, and add sections that didn't exist when it was saved.
        watchOrder = (prefs.getString("watchOrder", null)?.split(",").orEmpty().filter { id -> WATCH_SECTIONS.any { it.first == id } } +
            WATCH_SECTIONS.map { it.first }).distinct(),
        nbaAlerts = prefs.getBoolean("nbaAlerts", false),
        alertsFavorite = events(prefs.getString("alertsFavorite", null)) ?: AlertEvent.entries.toSet(),
        alertsOther = events(prefs.getString("alertsOther", null)) ?: OTHER_EVENTS,
        hideScores = prefs.getBoolean("hideScores", false),
        alertCooldown = prefs.getInt("alertCooldown", 10),
    )

    private fun events(saved: String?): Set<AlertEvent>? =
        saved?.split(",")?.mapNotNull { n -> AlertEvent.entries.firstOrNull { it.name == n } }?.toSet()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
}
