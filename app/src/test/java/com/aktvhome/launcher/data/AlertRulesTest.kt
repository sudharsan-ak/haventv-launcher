package com.aktvhome.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AlertRulesTest {

    private val t0 = 1_800_000_000_000L
    private val poll = 30_000L
    private val fav = AlertPrefs(favorite = "GS")

    private fun game(
        id: String = "1",
        away: String = "LAL", awayScore: Int? = 100,
        home: String = "BOS", homeScore: Int? = 100,
        state: GameState = GameState.LIVE,
        period: Int = 4,
        clock: String = "4:00",
        halftime: Boolean = false,
        calledOff: Boolean = false,
        start: Long = t0 - 2 * 3_600_000L,
        network: String? = "ESPN",
        awayName: String = "Lakers",
        homeName: String = "Celtics",
    ) = Game(
        id, Instant.ofEpochMilli(start),
        Team(away, awayName, awayScore?.toString() ?: "", "000000", null),
        Team(home, homeName, homeScore?.toString() ?: "", "000000", null),
        state, "", network, period, clock, halftime, calledOff,
    )

    /** Runs one fresh poll at [now]. */
    private fun AlertMemory.poll(now: Long, vararg games: Game, prefs: AlertPrefs = fav, watching: (Game) -> Boolean = { false }) =
        AlertRules.evaluate(games.toList(), fetchedAt = now, now = now, prefs = prefs, memory = this, watching = watching)

    private fun List<Alert>.events() = map { it.event }

    // ---- Clock ----

    @Test fun clockParsing() {
        assertEquals(300, AlertRules.clockSeconds("5:00"))
        assertEquals(301, AlertRules.clockSeconds("5:01"))
        assertEquals(45, AlertRules.clockSeconds("45.2"))
        assertEquals(0, AlertRules.clockSeconds("0.0"))
        assertEquals(null, AlertRules.clockSeconds(""))
        assertEquals(null, AlertRules.clockSeconds("abc"))
    }

    @Test fun closeGameAtFiveMinutesButNotAfter() {
        val m = AlertMemory()
        m.poll(t0, game(clock = "5:01", awayScore = 100, homeScore = 95))
        assertTrue(m.poll(t0 + poll, game(clock = "5:01", awayScore = 100, homeScore = 95)).isEmpty())

        val m2 = AlertMemory()
        m2.poll(t0, game(clock = "5:00", awayScore = 100, homeScore = 95))
        assertEquals(listOf(AlertEvent.CLOSE_GAME), m2.poll(t0 + poll, game(clock = "5:00", awayScore = 100, homeScore = 95)).events())
    }

    @Test fun closeGameMarginBoundary() {
        val m = AlertMemory()
        m.poll(t0, game(awayScore = 100, homeScore = 94))
        assertTrue(m.poll(t0 + poll, game(awayScore = 100, homeScore = 94)).isEmpty())
    }

    @Test fun closeGameOnlyInFourthQuarter() {
        val m = AlertMemory()
        m.poll(t0, game(period = 3, clock = "1:00"))
        assertTrue(m.poll(t0 + poll, game(period = 3, clock = "1:00")).isEmpty())
    }

    // ---- Two-poll confirmation ----

    @Test fun closeGameNeedsTwoPollsInARow() {
        val m = AlertMemory()
        assertTrue(m.poll(t0, game()).isEmpty())
        // Not close on the next poll: the count starts over.
        assertTrue(m.poll(t0 + poll, game(awayScore = 110)).isEmpty())
        assertTrue(m.poll(t0 + 2 * poll, game()).isEmpty())
        assertEquals(listOf(AlertEvent.CLOSE_GAME), m.poll(t0 + 3 * poll, game()).events())
    }

    @Test fun aGapBetweenPollsRestartsConfirmation() {
        val m = AlertMemory()
        m.poll(t0, game())
        assertTrue(m.poll(t0 + AlertRules.STALE_MS + 1, game()).isEmpty())
        assertEquals(1, m.poll(t0 + AlertRules.STALE_MS + 1 + poll, game()).size)
    }

    // ---- Dedupe ----

    /** Your team's game, so the other-team cooldown stays out of the way. */
    private fun mine(clock: String = "4:00", awayScore: Int = 100) = game(home = "GS", clock = clock, awayScore = awayScore)

    @Test fun closeGameOnceThenOnlyOnEscalation() {
        val m = AlertMemory()
        m.poll(t0, mine())
        assertEquals(1, m.poll(t0 + poll, mine()).size)
        assertTrue(m.poll(t0 + 2 * poll, mine(clock = "3:00")).isEmpty())
        // Escalation: within 2 with a minute left, confirmed over two polls, once.
        assertTrue(m.poll(t0 + 3 * poll, mine(clock = "1:00", awayScore = 101)).isEmpty())
        val again = m.poll(t0 + 4 * poll, mine(clock = "58.0", awayScore = 101))
        assertEquals(listOf(AlertEvent.CLOSE_GAME), again.events())
        assertTrue(m.poll(t0 + 5 * poll, mine(clock = "30.0", awayScore = 101)).isEmpty())
        assertTrue(m.poll(t0 + 6 * poll, mine(clock = "10.0", awayScore = 101)).isEmpty())
    }

    @Test fun escalationAloneDoesNotFireTwiceAtOnce() {
        val m = AlertMemory()
        // Already very close when first seen: one alert, then nothing more.
        m.poll(t0, mine(clock = "40.0"))
        assertEquals(1, m.poll(t0 + poll, mine(clock = "30.0")).size)
        assertTrue(m.poll(t0 + 2 * poll, mine(clock = "20.0")).isEmpty())
        // The escalation still needs its own two polls after the first alert went out.
        assertEquals(1, m.poll(t0 + 3 * poll, mine(clock = "10.0")).size)
    }

    @Test fun otherTeamEscalationWaitsForTheCooldown() {
        val m = AlertMemory()
        m.poll(t0, game())
        assertEquals(1, m.poll(t0 + poll, game()).size)
        // Two minutes later it's tighter, but the cooldown from the first alert still holds.
        m.poll(t0 + 4 * poll, game(clock = "1:00", awayScore = 101))
        assertTrue(m.poll(t0 + 5 * poll, game(clock = "50.0", awayScore = 101)).isEmpty())
    }

    @Test fun favoriteOnceOnlyEvents() {
        val m = AlertMemory()
        val pre = game(home = "GS", state = GameState.PRE, period = 0, clock = "", start = t0 + 9 * 60_000L)
        assertEquals(listOf(AlertEvent.TIP_OFF), m.poll(t0, pre).events())
        assertTrue(m.poll(t0 + poll, pre).isEmpty())

        val half = game(home = "GS", period = 2, clock = "0.0", halftime = true, awayScore = 50, homeScore = 70)
        assertEquals(listOf(AlertEvent.HALFTIME), m.poll(t0 + 2 * poll, half).events())
        assertTrue(m.poll(t0 + 3 * poll, half).isEmpty())

        val fin = game(home = "GS", state = GameState.FINAL, awayScore = 100, homeScore = 120)
        assertEquals(listOf(AlertEvent.FINAL), m.poll(t0 + 4 * poll, fin).events())
        assertTrue(m.poll(t0 + 5 * poll, fin).isEmpty())
    }

    @Test fun tipOffOnlyWithinTenMinutes() {
        val m = AlertMemory()
        val early = game(home = "GS", state = GameState.PRE, period = 0, clock = "", start = t0 + 10 * 60_000L + 1)
        assertTrue(m.poll(t0, early).isEmpty())
        val started = game(home = "GS", state = GameState.PRE, period = 0, clock = "", start = t0)
        assertTrue(m.poll(t0, started).isEmpty())
    }

    @Test fun finalOnlyForGamesSeenLive() {
        val m = AlertMemory()
        assertTrue(m.poll(t0, game(home = "GS", state = GameState.FINAL)).isEmpty())
    }

    @Test fun nothingForCalledOffGames() {
        val m = AlertMemory()
        val off = game(home = "GS", state = GameState.PRE, period = 0, clock = "", start = t0 + 5 * 60_000L, calledOff = true)
        assertTrue(m.poll(t0, off).isEmpty())
    }

    @Test fun otherTeamsGetOnlyTheirThreeEvents() {
        val m = AlertMemory()
        val pre = game(state = GameState.PRE, period = 0, clock = "", start = t0 + 5 * 60_000L)
        assertTrue(m.poll(t0, pre).isEmpty())
        assertTrue(m.poll(t0 + poll, game(period = 2, halftime = true, clock = "0.0")).isEmpty())
    }

    @Test fun settingsTurnEventsOff() {
        val m = AlertMemory()
        val prefs = fav.copy(otherEvents = emptySet())
        m.poll(t0, game(), prefs = prefs)
        assertTrue(m.poll(t0 + poll, game(), prefs = prefs).isEmpty())
        assertTrue(m.poll(t0 + 2 * poll, game(), prefs = prefs.copy(on = false)).isEmpty())
    }

    // ---- Overtime ----

    @Test fun overtimeOncePerPeriod() {
        val m = AlertMemory()
        assertEquals(listOf(AlertEvent.OVERTIME), m.poll(t0, game(period = 5, clock = "4:00", awayScore = 110, homeScore = 100)).events())
        assertTrue(m.poll(t0 + poll, game(period = 5, clock = "3:00", awayScore = 110, homeScore = 100)).isEmpty())
        assertEquals(listOf(AlertEvent.OVERTIME), m.poll(t0 + 2 * poll, game(period = 6, clock = "5:00", awayScore = 110, homeScore = 100)).events())
    }

    // ---- Cooldown ----

    @Test fun otherTeamsShareTheCooldown() {
        val m = AlertMemory()
        val a = game(id = "1")
        val b = game(id = "2", away = "MIA", home = "NY")
        m.poll(t0, a, b)
        // Both confirm on the same poll; only one gets through.
        assertEquals(1, m.poll(t0 + poll, a, b).size)
        assertTrue(m.poll(t0 + 2 * poll, a, b).isEmpty())
        // After the cooldown the held one comes, if it still holds.
        val later = t0 + poll + 10 * 60_000L
        m.poll(later - poll, a, b)
        assertEquals(1, m.poll(later, a, b).size)
    }

    @Test fun overtimeAndFavoriteIgnoreCooldown() {
        val m = AlertMemory(lastOtherAt = t0)
        val ot = game(id = "1", period = 5, awayScore = 110, homeScore = 100)
        val favClose = game(id = "2", home = "GS")
        m.poll(t0 + poll, ot, favClose)
        val out = m.poll(t0 + 2 * poll, favClose)
        assertEquals(listOf(AlertEvent.CLOSE_GAME), out.events())
        assertTrue(out.single().favorite)
        assertEquals(listOf(AlertEvent.OVERTIME), AlertMemory(lastOtherAt = t0).poll(t0 + poll, ot).events())
    }

    @Test fun favoriteComesFirst() {
        val m = AlertMemory()
        val other = game(id = "1")
        val mine = game(id = "2", away = "GS")
        m.poll(t0, other, mine)
        assertEquals(listOf(true, false), m.poll(t0 + poll, other, mine).map { it.favorite })
    }

    // ---- Comeback ----

    @Test fun comebackFromFifteenDown() {
        val m = AlertMemory()
        m.poll(t0, game(period = 2, clock = "6:00", awayScore = 40, homeScore = 56))
        // Back within 5, but still in the first half: nothing.
        m.poll(t0 + poll, game(period = 2, clock = "1:00", awayScore = 52, homeScore = 56))
        assertTrue(m.poll(t0 + 2 * poll, game(period = 2, clock = "0:30", awayScore = 52, homeScore = 56)).isEmpty())
        m.poll(t0 + 3 * poll, game(period = 3, clock = "9:00", awayScore = 60, homeScore = 64))
        val out = m.poll(t0 + 4 * poll, game(period = 3, clock = "8:30", awayScore = 60, homeScore = 64))
        assertEquals(listOf(AlertEvent.COMEBACK), out.events())
        // Once per trailing team.
        m.poll(t0 + 5 * poll, game(period = 3, clock = "8:00", awayScore = 66, homeScore = 64))
        assertTrue(m.poll(t0 + 6 * poll, game(period = 3, clock = "7:30", awayScore = 66, homeScore = 64)).isEmpty())
    }

    @Test fun noComebackFromFourteenDown() {
        val m = AlertMemory()
        m.poll(t0, game(period = 3, clock = "9:00", awayScore = 60, homeScore = 74))
        m.poll(t0 + poll, game(period = 3, clock = "5:00", awayScore = 70, homeScore = 74))
        assertTrue(m.poll(t0 + 2 * poll, game(period = 3, clock = "4:30", awayScore = 70, homeScore = 74)).isEmpty())
    }

    // ---- Stale data ----

    @Test fun staleDataNeverAlerts() {
        val m = AlertMemory()
        val g = game(period = 5, awayScore = 110, homeScore = 100)
        val stale = AlertRules.evaluate(listOf(g), fetchedAt = t0, now = t0 + AlertRules.STALE_MS + 1, prefs = fav, memory = m)
        assertTrue(stale.isEmpty())
        // And it didn't use the alert up.
        assertEquals(1, AlertRules.evaluate(listOf(g), fetchedAt = t0, now = t0 + AlertRules.STALE_MS, prefs = fav, memory = m).size)
    }

    // ---- Watching ----

    @Test fun watchedGameIsSkippedAndUsedUp() {
        val m = AlertMemory()
        val ot = game(period = 5, awayScore = 110, homeScore = 100)
        assertTrue(m.poll(t0, ot, watching = { true }).isEmpty())
        assertTrue(m.poll(t0 + poll, ot).isEmpty())
    }

    @Test fun watchingMatchesYouTubeTvChannel() {
        val g = game(network = "ABC")
        val yttv = NowPlaying(YOUTUBE_TV, "NBA Basketball", "WFAA 8", 0, true)
        assertTrue(AlertRules.isWatching(g, listOf(yttv), YOUTUBE_TV, mapOf("ABC" to "WFAA 8")))
        assertFalse(AlertRules.isWatching(g, listOf(yttv), YOUTUBE_TV, emptyMap()))
        assertTrue(AlertRules.isWatching(game(network = "ESPN"), listOf(yttv.copy(subtitle = "ESPN")), null, emptyMap()))
        assertFalse(AlertRules.isWatching(game(network = "ESPN"), listOf(yttv.copy(subtitle = "ESPN2")), null, emptyMap()))
        assertFalse(AlertRules.isWatching(game(network = "ESPN"), listOf(yttv.copy(subtitle = "TBS")), null, emptyMap()))
    }

    @Test fun watchingMatchesNbaAppLiveStreamOnly() {
        val g = game(awayName = "Bulls", homeName = "Bucks")
        assertTrue(AlertRules.isWatching(g, listOf(NowPlaying(NBA_APP, "Bulls (In-Arena)", null, -1, false)), NBA_APP, emptyMap()))
        assertTrue(AlertRules.isWatching(g, listOf(NowPlaying(NBA_APP, "Bucks (In-Arena)", null, -1, false)), NBA_APP, emptyMap()))
        // Highlights: a real position and no feed.
        assertFalse(AlertRules.isWatching(g, listOf(NowPlaying(NBA_APP, "Bucks", null, 5971, true)), NBA_APP, emptyMap()))
        assertFalse(AlertRules.isWatching(g, listOf(NowPlaying(NBA_APP, "Heat (In-Arena)", null, -1, true)), NBA_APP, emptyMap()))
    }

    @Test fun watchingInStreamingAppNeedsItInFrontAndPlaying() {
        val g = game(network = "Prime Video")
        val prime = NowPlaying(PRIME, "TNF", null, 1000, true)
        assertTrue(AlertRules.isWatching(g, listOf(prime), PRIME, emptyMap()))
        assertFalse(AlertRules.isWatching(g, listOf(prime.copy(playing = false)), PRIME, emptyMap()))
        assertFalse(AlertRules.isWatching(g, listOf(prime), "com.netflix.ninja", emptyMap()))
        assertFalse(AlertRules.isWatching(game(network = "ESPN"), listOf(prime), PRIME, emptyMap()))
    }
}
