package com.aktvhome.launcher.data

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Everything behind the Smart Strip and the Watch Now drawer. Refreshed from the UI clock while
 * Haven is open ([tick]); nothing runs in the background. NBA data only loads when its section is on.
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
    var live by mutableStateOf<List<LiveGroup>>(emptyList())
        private set

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
            scope.launch(Dispatchers.IO) {
                val cw = if (s.watchContinue) listings.continueWatching() else emptyList()
                val lv = if (s.watchLive) listings.liveGroups(liveApps(), labelOf) else emptyList()
                withContext(Dispatchers.Main) { continueWatching = cw; live = lv }
            }
        }
        if (!s.watchNba) return
        // Today: every 30 s while a game is live, every 5 minutes otherwise.
        val todayGames = games[today()]
        val liveNow = todayGames?.any { it.state == GameState.LIVE } == true
        loadDay(today(), maxAgeMs = if (liveNow) 30_000 else 5 * 60_000, force = force)
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

    suspend fun boxScore(game: Game): BoxScore? = withContext(Dispatchers.IO) { runCatching { Nba.boxScore(game.espnId, game) }.getOrNull() }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
