package com.aktvhome.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdvancedTest {

    private val eps = 0.01

    @Test fun trueShooting() {
        // 30 points on 20 FGA and 10 FTA: 30 / (2 * 24.4) = 61.5%.
        assertEquals(61.48, Advanced.trueShooting(30.0, 20.0, 10.0)!!, eps)
        // Only free throws still counts as shooting.
        assertEquals(113.64, Advanced.trueShooting(2.0, 0.0, 2.0)!!, eps)
        assertNull(Advanced.trueShooting(0.0, 0.0, 0.0))
    }

    @Test fun effectiveFg() {
        // 8 of 16 with 4 threes: (8 + 2) / 16 = 62.5%.
        assertEquals(62.5, Advanced.effectiveFg(8.0, 4.0, 16.0)!!, eps)
        assertEquals(0.0, Advanced.effectiveFg(0.0, 0.0, 5.0)!!, eps)
        assertNull(Advanced.effectiveFg(0.0, 0.0, 0.0))
    }

    @Test fun usage() {
        // 20 FGA, 5 FTA, 3 TO in 36 of the team's 240 minutes; team 85 FGA, 20 FTA, 12 TO.
        // 100 * (25.2 * 48) / (36 * 105.8) = 31.76%.
        assertEquals(31.76, Advanced.usage(20.0, 5.0, 3.0, 36.0, 240.0, 85.0, 20.0, 12.0)!!, eps)
        assertNull(Advanced.usage(0.0, 0.0, 0.0, 0.0, 240.0, 85.0, 20.0, 12.0))
        assertNull(Advanced.usage(1.0, 0.0, 0.0, 10.0, 240.0, 0.0, 0.0, 0.0))
    }

    @Test fun gameScore() {
        // 30 PTS, 11-20 FG, 6-8 FT, 2 OREB, 6 DREB, 2 STL, 7 AST, 1 BLK, 3 PF, 4 TO.
        // 30 + 4.4 - 14 - 0.8 + 1.4 + 1.8 + 2 + 4.9 + 0.7 - 1.2 - 4 = 25.2
        assertEquals(25.2, Advanced.gameScore(30.0, 11.0, 20.0, 6.0, 8.0, 2.0, 6.0, 2.0, 7.0, 1.0, 3.0, 4.0), eps)
        assertEquals(0.0, Advanced.gameScore(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), eps)
    }

    @Test fun ratiosAndDisplay() {
        assertEquals(2.5, Advanced.ratio(10.0, 4.0)!!, eps)
        assertNull(Advanced.ratio(3.0, 0.0))
        assertEquals("61.5", Advanced.show(61.476))
        assertEquals("-", Advanced.show(null))
    }

    @Test fun parsing() {
        assertEquals(5.0 to 10.0, Advanced.madeAttempted("5-10"))
        assertEquals(8.5 to 17.1, Advanced.madeAttempted("8.5-17.1"))
        assertNull(Advanced.madeAttempted("--"))
        assertNull(Advanced.madeAttempted(null))
        assertEquals(7.0, Advanced.number("+7"), eps)
        assertEquals(-3.0, Advanced.number("-3"), eps)
        assertEquals(0.0, Advanced.number("-"), eps)
    }
}
