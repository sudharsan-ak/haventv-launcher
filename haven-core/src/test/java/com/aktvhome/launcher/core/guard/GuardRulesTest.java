package com.aktvhome.launcher.core.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class GuardRulesTest {

    private static final String HAVEN = "com.aktvhome.launcher/com.aktvhome.launcher.HomeOverrideService";
    private static final String MAPPER = "flar2.homebutton/flar2.homebutton.MapperService";

    private static Set<String> set(String... s) {
        return new LinkedHashSet<>(Arrays.asList(s));
    }

    @Test
    public void restoresInTheOrderTheyWereWanted() {
        GuardState state = new GuardState(set(MAPPER, HAVEN), Collections.emptySet());
        GuardResult r = GuardRules.decide(state, Collections.emptySet(), s -> true, true);
        assertEquals(Arrays.asList(MAPPER, HAVEN), r.getRestore());
    }

    @Test
    public void onlyAppsThatAreLoadedComeBack() {
        GuardState state = new GuardState(set(MAPPER, HAVEN), Collections.emptySet());
        GuardResult r = GuardRules.decide(state, Collections.emptySet(), HAVEN::equals, true);
        assertEquals(Collections.singletonList(HAVEN), r.getRestore());
        // The one that isn't loaded yet stays wanted.
        assertEquals(set(MAPPER, HAVEN), r.getState().getWanted());
    }

    @Test
    public void seenIsWhatsOnNowPlusWhatWasRestored() {
        GuardState state = new GuardState(set(MAPPER, HAVEN), Collections.emptySet());
        GuardResult r = GuardRules.decide(state, set(MAPPER), s -> true, false);
        assertEquals(set(MAPPER, HAVEN), r.getState().getSeen());
    }

    @Test
    public void normalOnlyExpandsTheShortForm() {
        assertEquals("a.b/a.b.C", GuardRules.normal("a.b/.C"));
        assertEquals("a.b/x.C", GuardRules.normal("a.b/x.C"));
        assertEquals("/.C", GuardRules.normal("/.C"));
        assertEquals("noslash", GuardRules.normal("noslash"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void resultsAreReadOnly() {
        GuardResult r = GuardRules.decide(null, set(HAVEN), s -> true, false);
        r.getState().getWanted().clear();
    }

    @Test
    public void stateCopiesItsInput() {
        Set<String> wanted = new HashSet<>(set(HAVEN));
        GuardState state = new GuardState(wanted, Collections.emptySet());
        wanted.clear();
        assertEquals(set(HAVEN), state.getWanted());
        List<String> restore = GuardRules.decide(state, Collections.emptySet(), s -> true, true).getRestore();
        assertTrue(restore.contains(HAVEN));
    }
}
