package com.aktvhome.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NbaLeagueSettingsTest {
    @Test fun offDisablesCardsAndAlertsWithoutErasingPreferences() {
        val s = Settings(nbaLeagueEnabled = false, watchNba = true,
            alertsMine = true, alertsOthers = true, favoriteTeam = "GS")
        assertFalse(s.nbaCardEnabled())
        assertFalse(s.alertPrefs().on)
        assertEquals("GS", s.favoriteTeam)
    }
    @Test fun hidingHomeCardDoesNotDisableAlerts() {
        val s = Settings(nbaLeagueEnabled = true, watchNba = false,
            alertsMine = true, alertsOthers = false)
        assertFalse(s.nbaCardEnabled())
        assertTrue(s.alertPrefs().on)
    }
    @Test fun disablingAlertsDoesNotHideHomeCard() {
        val s = Settings(nbaLeagueEnabled = true, watchNba = true,
            alertsMine = false, alertsOthers = false)
        assertTrue(s.nbaCardEnabled())
        assertFalse(s.alertPrefs().on)
    }
    @Test fun restoringLeagueKeepsSelections() {
        val s = Settings(nbaLeagueEnabled = false, watchNba = true,
            rotateNba = false, nbaRotationInterval = NbaRotationInterval.TWENTY,
            favoriteTeam = "GS", followedTeams = setOf("GS", "BOS"))
        val restored = s.copy(nbaLeagueEnabled = true)
        assertTrue(restored.nbaCardEnabled())
        assertEquals(NbaRotationInterval.TWENTY, restored.nbaRotationInterval)
        assertFalse(restored.rotateNba)
        assertEquals(setOf("GS", "BOS"), restored.followedTeams)
    }
}
