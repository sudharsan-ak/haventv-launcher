package com.aktvhome.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aktvhome.launcher.core.sports.NbaMomentEngine
import org.json.JSONObject

/**
 * Private, on-TV memory of NBA games the viewer explicitly opened.
 * The Java engine decides what is worth saying; this class only saves data
 * and arbitrates when to show a transient Home headline.
 */
class NbaCompanionStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nba_companion", Context.MODE_PRIVATE)
    private var openedId: String? = null
    private var panelId: String? = null
    var panelMoment by mutableStateOf<NbaMomentEngine.Moment?>(null)
        private set
    var activeMoment by mutableStateOf<NbaMomentEngine.Moment?>(null)
        private set
    private var endsAt = 0L

    private fun snapshot(g: Game, now: Long = System.currentTimeMillis()) =
        NbaMomentEngine.Snapshot(
            g.espnId, g.away.abbr, g.home.abbr, g.away.name, g.home.name,
            g.away.score.toIntOrNull() ?: -1, g.home.score.toIntOrNull() ?: -1,
            g.period, g.state.name, now,
        )

    private fun read(key: String): NbaMomentEngine.Snapshot? = runCatching {
        val raw = prefs.getString(key, null) ?: return@runCatching null
        val j = JSONObject(raw)
        NbaMomentEngine.Snapshot(
            j.getString("id"), j.getString("away"), j.getString("home"),
            j.getString("awayName"), j.getString("homeName"),
            j.getInt("a"), j.getInt("h"), j.getInt("period"), j.getString("state"),
            j.getLong("seen"),
        )
    }.getOrNull()

    private fun save(key: String, s: NbaMomentEngine.Snapshot) {
        prefs.edit().putString(key, JSONObject()
            .put("id", s.id()).put("away", s.awayAbbr()).put("home", s.homeAbbr())
            .put("awayName", s.awayName()).put("homeName", s.homeName())
            .put("a", s.awayScore()).put("h", s.homeScore())
            .put("period", s.period()).put("state", s.state())
            .put("seen", s.observedAt()).toString()).apply()
    }

    /** Called only on an explicit OK/select, never by passive card focus. */
    fun opened(g: Game, favorite: String) {
        val current = snapshot(g)
        val previous = read("view:${g.espnId}")
        panelId = g.espnId
        openedId = g.espnId
        panelMoment = NbaMomentEngine.sinceChecked(previous, current, favorite)
        prefs.edit().putString("lastOpenedId", g.espnId).apply()
        save("view:${g.espnId}", current)
        // Opening the details itself counts as checking the latest development.
        if (activeMoment?.key()?.startsWith("${g.espnId}:") == true) clear()
    }

    /** Keep the last actually seen score fresh while this game's details remain on screen. */
    fun updateViewed(g: Game) {
        if (g.espnId == panelId) save("view:${g.espnId}", snapshot(g))
    }

    fun detailFor(id: String, hideScores: Boolean): String? =
        if (panelId == id) panelMoment?.detail(hideScores) else null

    fun closePanel() {
        panelId = null
        panelMoment = null
    }

    fun clear() {
        activeMoment = null
        endsAt = 0L
    }

    fun expire(key: String) {
        if (activeMoment?.key() == key) clear()
    }

    /** Only current fresh scoreboard data may trigger conversational Home text. */
    fun observeHome(games: List<Game>, favorite: String, now: Long = System.currentTimeMillis()) {
        val last = prefs.getString("lastOpenedId", null)
        val prior = last?.let { read("view:$it") }
        val latest = games.firstOrNull { it.espnId == last }
        val lastMoment = if (prior != null && latest != null &&
            now - prior.observedAt() >= 10 * 60_000L &&
            now - prior.observedAt() < 18 * 3_600_000L
        ) NbaMomentEngine.sinceChecked(prior, snapshot(latest, now), favorite) else null

        val favGame = games.firstOrNull { it.involves(favorite) }
        var favoriteMoment: NbaMomentEngine.Moment? = null
        if (favGame != null) {
            val latestFav = snapshot(favGame, now)
            val oldFav = read("favoriteAnchor")
            if (latestFav.state() == "LIVE" && oldFav != null &&
                now - oldFav.observedAt() < 2 * 3_600_000L
            ) favoriteMoment = NbaMomentEngine.favoriteLive(oldFav, latestFav, favorite)
            if (oldFav == null || oldFav.id() != latestFav.id() ||
                latestFav.state() != "LIVE" || now - oldFav.observedAt() >= 2 * 3_600_000L
            ) save("favoriteAnchor", latestFav)
        }

        val candidates = listOfNotNull(
            lastMoment?.takeIf { it.headline() != null },
            favoriteMoment,
        ).sortedWith(compareByDescending<NbaMomentEngine.Moment> { it.urgent() })
        val candidate = candidates.firstOrNull() ?: return
        if (candidate.liveOnly() &&
            games.none { it.espnId == candidate.key().substringBefore(':') && it.state == GameState.LIVE }
        ) return
        val previousKey = prefs.getString("lastShown", null)
        val previousAt = prefs.getLong("lastShownAt", 0L)
        if (previousKey == candidate.key() || (!candidate.urgent() && now - previousAt < 10 * 60_000L)) return
        // Don't interrupt an already running announcement except for a critical new moment.
        if (activeMoment != null && now < endsAt && !candidate.urgent()) return

        activeMoment = candidate
        endsAt = now + candidate.seconds() * 1000L
        prefs.edit().putString("lastShown", candidate.key()).putLong("lastShownAt", now).apply()
        if (favoriteMoment != null && candidate.key() == favoriteMoment.key() && favGame != null) {
            save("favoriteAnchor", snapshot(favGame, now))
        }
    }

    fun headline(games: List<Game>, hideScores: Boolean, now: Long = System.currentTimeMillis()): String? {
        val m = activeMoment ?: return null
        if (now >= endsAt) return null
        if (m.liveOnly() && games.none { it.espnId == m.key().substringBefore(':') && it.state == GameState.LIVE }) return null
        return m.headline(hideScores)
    }
}
