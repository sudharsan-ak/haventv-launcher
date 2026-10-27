package com.aktvhome.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class NbaRotationTest {
    private val midnight = Instant.parse("2026-10-28T05:00:00Z")

    private fun game(id: String, away: String, home: String, state: GameState,
                     start: Instant = midnight.minus(2, ChronoUnit.HOURS), calledOff: Boolean = false): Game =
        Game(id, start,
            Team(away, away, "0", "000000", null),
            Team(home, home, "0", "000000", null),
            state, "", null, calledOff = calledOff)

    @Test fun favoriteFirstThenLiveUpcomingFinal() {
        val upcoming = game("pre", "LAL", "BOS", GameState.PRE)
        val live = game("live", "NY", "PHX", GameState.LIVE)
        val final = game("final", "MIA", "ORL", GameState.FINAL)
        val favorite = game("gs", "GS", "DEN", GameState.PRE)
        assertEquals(listOf("gs", "live", "pre", "final"),
            NbaRotation.ordered(listOf(final, upcoming, favorite, live), "GS").map { it.espnId })
    }

    @Test fun nextIdKeepsPositionAfterStatusRefresh() {
        assertEquals("pre", NbaRotation.nextId(listOf("gs", "live", "pre"), "live"))
        assertEquals("gs", NbaRotation.nextId(listOf("gs", "live", "pre"), "pre"))
        assertEquals("live", NbaRotation.nextId(listOf("live", "gs"), "missing"))
        assertEquals(null, NbaRotation.nextId(emptyList(), "missing"))
    }

    @Test fun previousDayLiveGameSurvivesMidnightWithoutDuplication() {
        val live = game("night", "GS", "LAL", GameState.LIVE)
        val nextDay = game("new", "BOS", "NY", GameState.PRE,
            midnight.plus(16, ChronoUnit.HOURS))
        assertEquals(listOf("new", "night"),
            NbaRotation.slate(listOf(nextDay), listOf(live), midnight).map { it.espnId })
        assertEquals(2, NbaRotation.slate(listOf(nextDay, live), listOf(live), midnight).size)
        assertFalse(NbaRotation.overnight(live.copy(state = GameState.FINAL), midnight))
    }

    @Test fun overduePregameCanSurviveFeedDelayButNotForever() {
        val game = game("pending", "GS", "NY", GameState.PRE)
        assertTrue(NbaRotation.overnight(game, midnight))
        assertFalse(NbaRotation.overnight(game.copy(calledOff = true), midnight))
        assertFalse(NbaRotation.overnight(game.copy(start = midnight.minus(10, ChronoUnit.HOURS)), midnight))
        assertFalse(NbaRotation.overnight(game.copy(start = midnight.plus(1, ChronoUnit.HOURS)), midnight))
    }

    @Test fun featuredGameLongerBreakButNotAddedToNormalInterval() {
        assertEquals(20_000L, NbaRotation.displayMillis(0, 4, 10_000))
        assertEquals(30_000L, NbaRotation.displayMillis(0, 5, 10_000))
        assertEquals(10_000L, NbaRotation.displayMillis(1, 5, 10_000))
        assertEquals(8_000L, NbaRotation.displayMillis(2, 5, 8_000))
        assertEquals(15_000L, NbaRotation.displayMillis(2, 5, 15_000))
        assertEquals(20_000L, NbaRotation.displayMillis(2, 5, 20_000))
    }
}
