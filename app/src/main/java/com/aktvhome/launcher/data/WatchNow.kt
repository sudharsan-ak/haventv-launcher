package com.aktvhome.launcher.data

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant

/**
 * Everything behind the Smart Strip and the Watch Now drawer. Refreshed from the UI clock while
 * Haven is open ([tick]); NBA data only loads when its section is on. The alert banners keep
 * their own copy in the Home button service, for its watch options ([loadRows]).
 */
class WatchNow(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val liveApps: () -> Set<String>,
    private val labelOf: (String) -> String,
) {
    private val listings = TvListings(context)

    var continueWatching by mutableStateOf<List<TvItem>>(emptyList())
        private set
    /** Channels from your Live TV apps (YouTube TV…), shared now or remembered, A to Z. */
    var live by mutableStateOf<List<LiveGroup>>(emptyList())
        private set
    /** Every other app's shared row (e.g. ESPN's live shows), used to find a game's exact stream. */
    var appRows by mutableStateOf<List<LiveGroup>>(emptyList())
        private set
    /** Channel and show artwork, loaded on demand. */
    val art = mutableStateMapOf<String, ImageBitmap>()
    private val artLoading = mutableSetOf<String>()
    private val remembered = context.getSharedPreferences("channels", Context.MODE_PRIVATE)

    /** Games per date. A missing date means not loaded yet; an empty list means no games. */
    val games = mutableStateMapOf<LocalDate, List<Game>>()
    private val loadedAt = mutableMapOf<LocalDate, Long>()
    private val loading = mutableSetOf<LocalDate>()

    var schedule by mutableStateOf<List<Game>>(emptyList())
        private set
    private var scheduleTeam = ""
    private var scheduleAt = 0L
    /** True when the last NBA fetch failed, so the UI can say so instead of "no games". */
    var nbaError by mutableStateOf(false)
        private set

    private var tvAt = 0L
    private val nbaIds = mutableMapOf<String, String>()

    fun today(): LocalDate = LocalDate.now()

    /** Called every ~15 s while Haven is open, and when the drawer opens ([force]). */
    fun tick(force: Boolean = false) {
        val s = settingsStore.settings
        val now = System.currentTimeMillis()
        if ((s.watchContinue || s.watchLive) && (force || now - tvAt > 60_000)) {
            tvAt = now
            scope.launch { loadRows() }
        }
        // Today's games also feed Kingdom's headline when it follows your team.
        if (!s.watchNba && !(s.headlineChanging && s.favoriteTeam.isNotBlank())) return
        // Today: every 10 s while a game is live (see [liveTick]), every 5 minutes otherwise.
        loadDay(today(), maxAgeMs = if (liveToday()) 10_000 else 5 * 60_000, force = force)
        // Yesterday's late games may still be active after local midnight. Reuse this cache
        // for card rotation rather than fetching individual games as the UI advances.
        if (s.watchNba) overnightDay()?.let { day ->
            val previous = games[day]
            if (previous == null || previous.any { NbaRotation.overnight(it, Instant.now()) }) {
                loadDay(day, maxAgeMs = if (previous?.any { it.state == GameState.LIVE } == true) 10_000 else 5 * 60_000)
            }
        }
        val fav = s.favoriteTeam
        if (fav.isNotBlank() && (fav != scheduleTeam || now - scheduleAt > 6 * 3_600_000L || (force && schedule.isEmpty()))) {
            scheduleTeam = fav
            scheduleAt = now
            scope.launch(Dispatchers.IO) {
                val list = runCatching { Nba.schedule(fav) }.getOrNull()
                if (list != null) withContext(Dispatchers.Main) { schedule = list } else scheduleAt = 0L
            }
        }
    }

    fun liveToday(): Boolean = games[today()]?.any { it.state == GameState.LIVE } == true

    /**
     * Called every 10 s while Haven is in front: refreshes today's scoreboard when a game is live,
     * so the NBA card, the headline and its rotation keep up (ESPN caches it for only 2 s).
     */
    fun liveTick() {
        val s = settingsStore.settings
        if (!s.watchNba && !s.headlineChanging) return
        if (liveToday()) loadDay(today(), maxAgeMs = 9_000)
        overnightDay()?.let { day ->
            if (games[day].orEmpty().any { it.state == GameState.LIVE }) loadDay(day, maxAgeMs = 9_000)
        }
    }

    /** During the early morning, look for yesterday's unfinished games as well. */
    private fun overnightDay(): LocalDate? =
        today().minusDays(1).takeIf { LocalTime.now().hour < 7 }

    /** The cached home slate. This selection makes no requests to ESPN. */
    fun homeRotationGames(): List<Game> = NbaRotation.ordered(
        NbaRotation.slate(
            games[today()].orEmpty(),
            overnightDay()?.let { games[it] }.orEmpty(),
            Instant.now(),
        ),
        settingsStore.settings.favoriteTeam,
    )

    /** Reads Continue watching and the apps' shared rows now. The alert banners call it directly. */
    suspend fun loadRows() {
        val s = settingsStore.settings
        val liveSet = liveApps()
        val (cw, rows) = withContext(Dispatchers.IO) {
            (if (s.watchContinue) listings.continueWatching() else emptyList()) to listings.appRows(labelOf)
        }
        val lv = withContext(Dispatchers.IO) { if (s.watchLive) rows.filter { it.pkg in liveSet }.map { remember(it) } else emptyList() }
        withContext(Dispatchers.Main) {
            continueWatching = cw
            live = lv
            appRows = rows.filter { it.pkg !in liveSet }
        }
    }

    /** When [date]'s games were last fetched, or 0. */
    fun fetchedAt(date: LocalDate): Long = loadedAt[date] ?: 0L

    /** True when [date]'s games were fetched in the last 3 minutes (the alerts' rule for fresh). */
    fun fresh(date: LocalDate): Boolean = System.currentTimeMillis() - (loadedAt[date] ?: 0L) <= 3 * 60_000L

    /** Loads a day's games if missing or older than [maxAgeMs]. */
    fun loadDay(date: LocalDate, maxAgeMs: Long = 10 * 60_000, force: Boolean = false) {
        val age = System.currentTimeMillis() - (loadedAt[date] ?: 0L)
        if (date in loading || (!force && date in games && age < maxAgeMs)) return
        loading += date
        scope.launch(Dispatchers.IO) {
            val list = runCatching { Nba.scoreboard(date) }.getOrNull()
            withContext(Dispatchers.Main) {
                loading -= date
                nbaError = list == null
                if (list != null) {
                    games[date] = list
                    loadedAt[date] = System.currentTimeMillis()
                }
            }
        }
    }

    /** The game the strip's Tonight card features: favorite first, then live, then next to start. */
    fun featured(): Game? {
        val list = games[today()] ?: return null
        val fav = settingsStore.settings.favoriteTeam
        return list.firstOrNull { it.involves(fav) && it.state != GameState.FINAL }
            ?: list.firstOrNull { it.state == GameState.LIVE }
            ?: list.firstOrNull { it.state == GameState.PRE }
            ?: list.firstOrNull { it.involves(fav) }
            ?: list.firstOrNull()
    }

    /**
     * Live TV apps share only some channels at a time (YouTube TV shares about ten), and rotate
     * them. Each channel's link is permanent, so every channel ever shared is remembered and stays
     * tunable. Returns the group with remembered channels added, A to Z.
     */
    private fun remember(group: LiveGroup): LiveGroup {
        val key = "c_${group.pkg}"
        val saved = runCatching { org.json.JSONArray(remembered.getString(key, "[]")) }.getOrDefault(org.json.JSONArray())
        val byTitle = linkedMapOf<String, TvItem>()
        for (i in 0 until saved.length()) {
            val o = saved.getJSONObject(i)
            byTitle[o.getString("t")] = TvItem(group.pkg, o.getString("t"), "", null, false, o.optString("u").ifBlank { null }, o.optString("a").ifBlank { null })
        }
        group.items.forEach { byTitle[it.title] = it }
        val out = org.json.JSONArray()
        byTitle.values.forEach { out.put(org.json.JSONObject().put("t", it.title).put("u", it.intentUri ?: "").put("a", it.art ?: "")) }
        remembered.edit().putString(key, out.toString()).apply()
        return group.copy(items = byTitle.values.sortedBy { it.title.lowercase() })
    }

    /** Loads [url]'s image into [art] (once), scaled down to [width] px for tiles (logos ask for less). */
    fun loadArt(url: String?, width: Int = 320) {
        if (url == null || url in art || url in artLoading) return
        artLoading += url
        scope.launch(Dispatchers.IO) {
            val bmp = runCatching {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.inputStream.use { stream ->
                    val raw = android.graphics.BitmapFactory.decodeStream(stream)
                    val w = width
                    android.graphics.Bitmap.createScaledBitmap(raw, w, (raw.height * w / raw.width).coerceAtLeast(1), true)
                }.asImageBitmap()
            }.getOrNull()
            withContext(Dispatchers.Main) {
                artLoading -= url
                if (bmp != null) art[url] = bmp
            }
        }
    }

    /** Any team's season schedule (ESPN abbreviation to games, oldest first), for the Schedules tab. */
    val teamSchedules = mutableStateMapOf<String, List<Game>>()
    private val teamScheduleAt = mutableMapOf<String, Long>()
    private val teamScheduleLoading = mutableSetOf<String>()
    /** Teams whose last schedule fetch failed, so the tab can say so instead of "Loading". */
    val teamScheduleFailed = mutableStateMapOf<String, Boolean>()

    /** Loads [abbr]'s schedule if it's missing or more than 10 minutes old (scores change during games). */
    fun loadSchedule(abbr: String) {
        val age = System.currentTimeMillis() - (teamScheduleAt[abbr] ?: 0L)
        if (abbr in teamScheduleLoading || (abbr in teamSchedules && age < 10 * 60_000L)) return
        teamScheduleLoading += abbr
        scope.launch(Dispatchers.IO) {
            val list = runCatching { Nba.schedule(abbr) }.getOrNull()
            withContext(Dispatchers.Main) {
                teamScheduleLoading -= abbr
                teamScheduleFailed[abbr] = list == null
                if (list != null) {
                    teamSchedules[abbr] = list
                    teamScheduleAt[abbr] = System.currentTimeMillis()
                }
            }
        }
    }

    fun nextFavorite(): Game? = schedule.firstOrNull { it.state != GameState.FINAL }
    fun lastFavorite(): Game? = schedule.lastOrNull { it.state == GameState.FINAL }

    fun open(item: TvItem) {
        if (!listings.open(item)) toast("Can't open ${item.title}")
    }

    /** Looks up NBA's own game ID (cached), then opens the game page in the NBA app. */
    fun openGame(game: Game) {
        scope.launch(Dispatchers.IO) {
            val id = nbaIds[game.espnId] ?: runCatching { Nba.nbaGameId(game) }.getOrNull()?.also { nbaIds[game.espnId] = it }
            withContext(Dispatchers.Main) {
                if (id == null) toast("Opening the NBA app's games list")
                if (!Nba.openInApp(context, id)) toast("NBA app isn't installed")
            }
        }
    }

    /** Players looked up this session; their season, splits and log don't change during a game. */
    private val players = mutableMapOf<String, PlayerInfo>()

    fun cachedPlayer(id: String): PlayerInfo? = players[id]

    /** A player's profile, season, splits and game log (each part may be missing). */
    suspend fun player(id: String): PlayerInfo {
        players[id]?.let { return it }
        val info = withContext(Dispatchers.IO) {
            PlayerInfo(
                runCatching { Nba.playerProfile(id) }.getOrNull(),
                runCatching { Nba.playerSeason(id) }.getOrNull(),
                runCatching { Nba.playerSplits(id) }.getOrDefault(emptyList()),
                runCatching { Nba.playerLog(id) }.getOrDefault(emptyList()),
            )
        }
        // Only keep a full answer; a failed part is tried again next time.
        if (info.profile != null && info.season != null) players[id] = info
        return info
    }

    /** Game summaries fetched this session, so going back to a game shows it at once. */
    val summaries = mutableStateMapOf<String, GameSummary>()
    private val summaryAt = mutableMapOf<String, Long>()

    fun summaryTime(espnId: String): Long = summaryAt[espnId] ?: 0L

    /** A game's summary from ESPN (kept in [summaries]), or null if it couldn't be fetched. */
    suspend fun summary(espnId: String): GameSummary? {
        val got = withContext(Dispatchers.IO) { runCatching { Nba.summary(espnId) }.getOrNull() } ?: return null
        summaries[espnId] = got
        summaryAt[espnId] = System.currentTimeMillis()
        return got
    }

    /** A game already loaded (any day's scoreboard, or the team schedule), with when it was fetched. */
    fun findGame(espnId: String): Pair<Game, Long>? {
        for ((date, list) in games) list.firstOrNull { it.espnId == espnId }?.let { return it to (loadedAt[date] ?: 0L) }
        return schedule.firstOrNull { it.espnId == espnId }?.let { it to scheduleAt }
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
