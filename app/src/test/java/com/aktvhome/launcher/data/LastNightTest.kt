package com.aktvhome.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class LastNightTest {

    private fun game(
        id: String, away: String, awayName: String, awayScore: Int, home: String, homeName: String, homeScore: Int,
        state: GameState = GameState.FINAL, period: Int = 4,
    ) = Game(
        id, Instant.EPOCH,
        Team(away, awayName, awayScore.toString(), "000000", null),
        Team(home, homeName, homeScore.toString(), "000000", null),
        state, "Final", null, period = period,
    )

    private val gsWin = game("1", "MEM", "Grizzlies", 112, "GS", "Warriors", 118)
    private val gsLoss = game("1", "GS", "Warriors", 112, "MEM", "Grizzlies", 118)
    private val bullsBucks = game("2", "CHI", "Bulls", 132, "MIL", "Bucks", 125)
    private val blowout = game("3", "BOS", "Celtics", 140, "WSH", "Wizards", 100)
    private val close = game("4", "NY", "Knicks", 101, "MIA", "Heat", 100)
    private val overtime = game("5", "DEN", "Nuggets", 120, "LAL", "Lakers", 110, period = 5)

    @Test fun mainTeamWin() {
        val t = LastNight.pick(listOf(bullsBucks, gsWin, blowout), "GS", emptySet(), hideScores = false)!!
        assertEquals("Warriors beat the Grizzlies 118–112", t.headline)
        assertEquals("2 more games", t.detail)
        assertEquals("1", t.game.espnId)
    }

    @Test fun mainTeamLoss() {
        val t = LastNight.pick(listOf(gsLoss, close), "GS", emptySet(), hideScores = false)!!
        assertEquals("Warriors fell to the Grizzlies 112–118", t.headline)
        assertEquals("1 more game", t.detail)
    }

    @Test fun onlyGameHasNoMoreLine() {
        assertNull(LastNight.pick(listOf(gsWin), "GS", emptySet(), hideScores = false)!!.detail)
    }

    @Test fun noMainGamePicksAFollowedTeam() {
        val t = LastNight.pick(listOf(blowout, close, bullsBucks), "GS", setOf("CHI"), hideScores = false)!!
        assertEquals("Bulls beat the Bucks 132–125", t.headline)
        assertEquals("2 more games", t.detail)
    }

    @Test fun noFollowedPicksOvertimeThenClosest() {
        assertEquals("5", LastNight.pick(listOf(blowout, close, overtime), "GS", emptySet(), hideScores = false)!!.game.espnId)
        val t = LastNight.pick(listOf(blowout, close), "GS", emptySet(), hideScores = false)!!
        assertEquals("Knicks beat the Heat 101–100", t.headline)
    }

    @Test fun hideScoresNamesTeamsOnly() {
        val t = LastNight.pick(listOf(gsLoss, close, blowout), "GS", emptySet(), hideScores = true)!!
        assertEquals("Last night's games are in", t.headline)
        assertEquals("Warriors played the Grizzlies · 2 more games", t.detail)
    }

    @Test fun noFinalsNoToast() {
        assertNull(LastNight.pick(emptyList(), "GS", emptySet(), hideScores = false))
        val live = game("6", "GS", "Warriors", 50, "MEM", "Grizzlies", 48, state = GameState.LIVE)
        assertNull(LastNight.pick(listOf(live), "GS", emptySet(), hideScores = false))
    }
}
