package com.aktvhome.launcher.core.sports;

import org.junit.Test;
import static org.junit.Assert.*;

public class NbaMomentEngineTest {
    private NbaMomentEngine.Snapshot snap(int away, int home, String state, int period, long at) {
        return new NbaMomentEngine.Snapshot("401", "BKN", "BOS", "Nets", "Celtics",
            away, home, period, state, at);
    }

    @Test public void largeSwingOnlyAfterGameWasChecked() {
        var before = snap(61, 55, "LIVE", 2, 100_000L);
        var after = snap(85, 99, "LIVE", 4, 4_000_000L);
        var m = NbaMomentEngine.sinceChecked(before, after, "BKN");
        assertNotNull(m);
        assertNotNull(m.headline());
        assertTrue(m.detail().contains("24 to 44"));
        assertNull(NbaMomentEngine.sinceChecked(null, after, "BKN"));
    }

    @Test public void minorMovementStaysInDetailsAndDoesNotTakeOverHome() {
        var m = NbaMomentEngine.sinceChecked(snap(61,55,"LIVE",2,100_000L),
            snap(64,57,"LIVE",2,400_000L), "BKN");
        assertNotNull(m);
        assertNull(m.headline());
        assertNotNull(m.detail());
    }

    @Test public void favoriteLeadGrowthOnlyWhileLive() {
        var old = snap(61,55,"LIVE",2,100_000L);
        var live = snap(73,57,"LIVE",3,200_000L);
        var event = NbaMomentEngine.favoriteLive(old,live,"BKN");
        assertNotNull(event);
        assertTrue(event.liveOnly());
        assertTrue(event.headline().contains("lead"));
        assertNull(NbaMomentEngine.favoriteLive(old,snap(110,80,"FINAL",4,300_000L),"BKN"));
    }

    @Test public void hideScoresUsesSpoilerSafePhrases() {
        var m = NbaMomentEngine.favoriteLive(snap(61,55,"LIVE",2,100_000L),
            snap(73,57,"LIVE",3,200_000L),"BKN");
        assertNotNull(m);
        assertFalse(m.headline(true).contains("16"));
        assertFalse(m.detail(true).contains("lead"));
    }

    @Test public void finalNeverUsesLiveOnlyHeadline() {
        var m = NbaMomentEngine.sinceChecked(snap(61,55,"LIVE",2,100_000L),
            snap(110,108,"FINAL",4,400_000L), "BKN");
        assertNotNull(m);
        assertFalse(m.liveOnly());
        assertTrue(m.headline().contains("finished"));
    }

    @Test public void differentGameDoesNotReuseLastCheck() {
        var other = new NbaMomentEngine.Snapshot("402", "DET", "NY", "Pistons", "Knicks",
            65, 71, 3, "LIVE", 500_000L);
        assertNull(NbaMomentEngine.sinceChecked(snap(61,55,"LIVE",2,100_000L),other,"BKN"));
    }
}
