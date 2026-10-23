package com.aktvhome.launcher.data

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.core.app.NotificationManagerCompat
import com.aktvhome.launcher.HavenMediaListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

/**
 * NBA alerts over any app. Runs in the Home button service, which stays up while the TV is on:
 * polls ESPN's scoreboard, runs [AlertRules] and hands each alert to [show]. ESPN is asked every
 * 30 s while a game is live or about to tip off, every 5 minutes on a game day, hourly otherwise;
 * yesterday's games too until they're all over (late games run past midnight).
 */
class NbaAlerts(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: SettingsStore,
    /** Package of the app in front. */
    private val foreground: () -> String?,
    private val show: (Alert) -> Unit,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val memory = AlertMemory(
        sent = prefs.getString("sent", null).orEmpty().lines().filter { it.isNotBlank() },
        trail = prefs.getString("trail", null).orEmpty().lines().mapNotNull { line ->
            line.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> v.toIntOrNull()?.let { k to it } }
        }.toMap(),
        lastOtherAt = prefs.getLong("lastOtherAt", 0L),
    )
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            while (isActive) delay(runCatching { poll() }.getOrDefault(RETRY_MS))
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** A sample banner in 10 s, so you can switch to another app and see it there. */
    fun sendTest() {
        scope.launch {
            delay(10_000)
            val fav = store.settings.favoriteTeam.ifBlank { "GS" }
            val other = if (fav == "LAL") "BOS" else "LAL"
            fun team(abbr: String, score: String) = Team(abbr, Nba.teamName(abbr).substringAfterLast(' '), score, "5b7bff", null)
            val game = Game(
                "test", Instant.now(), team(other, "101"), team(fav, "104"), GameState.LIVE, "", "ESPN",
                period = 4, clock = "2:31",
            )
            show(Alert(game, AlertEvent.CLOSE_GAME, favorite = true))
        }
    }

    /** One round: fetch, decide, show. Returns how long to wait before the next. */
    private suspend fun poll(): Long {
        // Settings change in Haven's own copy; read them fresh each round.
        val s = withContext(Dispatchers.Main) { store.reload(); store.settings }
        // Off: nothing is fetched, only checked again soon so turning it on takes effect.
        if (!s.nbaAlerts) return RETRY_MS
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val todayGames = withContext(Dispatchers.IO) { Nba.scoreboard(today) }
        val fetchedAt = System.currentTimeMillis()
        val lateGames = if (prefs.getString("doneDay", null) == yesterday.toString()) emptyList()
        else withContext(Dispatchers.IO) { runCatching { Nba.scoreboard(yesterday) }.getOrNull() }?.also { list ->
            if (list.all { it.state == GameState.FINAL || it.calledOff }) prefs.edit().putString("doneDay", yesterday.toString()).apply()
        }.orEmpty()
        prefs.edit().putLong("updatedAt", fetchedAt).apply()

        val games = todayGames + lateGames
        // What's playing is only looked up when an alert is about to go out.
        val sessions by lazy { nowPlaying() }
        val front by lazy { foreground() }
        val alerts = AlertRules.evaluate(games, fetchedAt, System.currentTimeMillis(), s.alertPrefs(), memory) { g ->
            AlertRules.isWatching(g, sessions, front, s.localStations)
        }
        memory.prune(games.map { it.espnId }.toSet())
        save()
        alerts.forEach(show)
        return nextPoll(games, todayGames, System.currentTimeMillis())
    }

    private fun nextPoll(games: List<Game>, today: List<Game>, now: Long): Long {
        val untilTip = today.filter { it.state == GameState.PRE && !it.calledOff }.map { it.start.toEpochMilli() - now }
        if (games.any { it.state == GameState.LIVE } || untilTip.any { it <= 15 * 60_000L }) return 30_000L
        // Game day: every 5 minutes, waking in time for the 15 minutes before the next tip-off.
        if (untilTip.isNotEmpty()) return minOf(5 * 60_000L, untilTip.min() - 15 * 60_000L).coerceAtLeast(30_000L)
        return 3_600_000L
    }

    private fun save() {
        prefs.edit()
            .putString("sent", memory.sent.joinToString("\n"))
            .putString("trail", memory.trail.entries.joinToString("\n") { "${it.key}=${it.value}" })
            .putLong("lastOtherAt", memory.lastOtherAt)
            .apply()
    }

    /** Active media sessions; empty without Notification access. */
    private fun nowPlaying(): List<NowPlaying> = runCatching {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        msm.getActiveSessions(ComponentName(context, HavenMediaListener::class.java)).map { c ->
            val d = c.metadata?.description
            val st = c.playbackState
            NowPlaying(c.packageName, d?.title?.toString(), d?.subtitle?.toString(), st?.position ?: 0L, st?.state == PlaybackState.STATE_PLAYING)
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val PREFS = "nba_alerts"
        private const val RETRY_MS = 60_000L

        /** When ESPN last answered, or 0. */
        fun lastUpdate(context: Context): Long =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("updatedAt", 0L)

        /** True once Notification access is granted, so Haven can see what's playing. */
        fun canSeePlaying(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        const val GRANT_COMMAND =
            "adb shell cmd notification allow_listener com.aktvhome.launcher/com.aktvhome.launcher.HavenMediaListener"
    }
}
