package com.aktvhome.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RecapTest {

    private fun final(away: Int, home: Int, period: Int = 4) = Game(
        "1", Instant.EPOCH,
        Team("CHI", "Bulls", away.toString(), "000000", null, location = "Chicago"),
        Team("MIL", "Bucks", home.toString(), "000000", null, location = "Milwaukee"),
        GameState.FINAL, "Final", null, period = period,
    )

    @Test fun winnerFirstWithBothScorers() {
        assertEquals(
            "Bulls beat the Bucks 132–125. Norman Powell led Chicago with 25; Tyler Herro had 34 for Milwaukee.",
            recapLine(final(132, 125), "Norman Powell" to 25, "Tyler Herro" to 34),
        )
    }

    @Test fun homeWinInOvertime() {
        assertEquals(
            "Bucks beat the Bulls 120–118 in overtime. Giannis Antetokounmpo led Milwaukee with 41.",
            recapLine(final(118, 120, period = 5), null, "Giannis Antetokounmpo" to 41),
        )
    }

    @Test fun losingScorerOnly() {
        assertEquals("Bulls beat the Bucks 100–90. Tyler Herro had 30 for Milwaukee.", recapLine(final(100, 90), null, "Tyler Herro" to 30))
    }

    @Test fun noWinnerNoLine() {
        assertNull(recapLine(final(100, 100), null, null))
    }
}
