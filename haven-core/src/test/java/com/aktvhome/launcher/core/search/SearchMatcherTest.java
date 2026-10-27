package com.aktvhome.launcher.core.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class SearchMatcherTest {

    private final List<SearchDocument> documents = Arrays.asList(
        new SearchDocument("app:youtube", "YouTube", "Streaming · Installed app", "Launch YouTube.", "video"),
        new SearchDocument("app:netflix", "Netflix", "Streaming · Installed app", "Launch Netflix.", "movies"),
        new SearchDocument("app:spotify", "Spotify", "Music · Installed app", "Launch Spotify.", "songs"),
        new SearchDocument("team:GS", "Golden State Warriors", "NBA team", "Open the schedule.", "GS GSW"),
        new SearchDocument("setting:private_dns", "Private DNS", "TV & network", "Configure encrypted DNS.", "AdGuard Cloudflare")
    );

    private List<String> ids(String query) {
        List<String> out = new ArrayList<>();
        for (SearchDocument d : SearchMatcher.ranked(documents, query)) out.add(d.getId());
        return out;
    }

    @Test
    public void oneTypoFindsTheApp() {
        assertEquals("app:netflix", ids("netflx").get(0));
        assertEquals("app:spotify", ids("spotifu").get(0));
    }

    @Test
    public void swappedLettersCountAsOneTypo() {
        assertEquals("app:netflix", ids("netlfix").get(0));
        assertEquals("app:youtube", ids("yuotube").get(0));
    }

    @Test
    public void longWordsMayHaveTwoTypos() {
        // Typed words of 8 or more letters get two typos; "woriors" (7 letters) gets one, too few.
        assertEquals("team:GS", ids("wariorss golden").get(0));
        assertTrue(ids("woriors").isEmpty());
        assertEquals("team:GS", ids("warroirz").get(0));
    }

    @Test
    public void aTypoWhileStillTypingMatchesTheStartOfAWord() {
        assertEquals("app:netflix", ids("netlf").get(0));
    }

    @Test
    public void shortWordsNeedAnExactMatch() {
        // Three letters: one typo away from "dns" isn't a match.
        assertTrue(ids("dnz").isEmpty());
        assertEquals(0, SearchMatcher.typosAllowed("you"));
        assertEquals(1, SearchMatcher.typosAllowed("netfl"));
        assertEquals(2, SearchMatcher.typosAllowed("warriors"));
    }

    @Test
    public void tooManyTyposIsNoMatch() {
        assertTrue(ids("nxtflx").isEmpty());
        assertTrue(ids("zzzzzz").isEmpty());
    }

    @Test
    public void exactMatchesHideNearMisses() {
        List<SearchDocument> games = Arrays.asList(new SearchDocument("game", "Game Night"), new SearchDocument("gate", "Gate Keeper"));
        List<SearchDocument> found = SearchMatcher.ranked(games, "game");
        assertEquals(1, found.size());
        assertEquals("game", found.get(0).getId());
        // With no exact match, the near miss is offered.
        assertEquals("gate", SearchMatcher.ranked(games, "gatte").get(0).getId());
    }

    @Test
    public void everyWordMustStillMatch() {
        assertTrue(ids("netflix zzzzzz").isEmpty());
    }

    @Test
    public void blankQueryKeepsTheGivenOrderAndLimit() {
        List<SearchDocument> found = SearchMatcher.ranked(documents, "  ", 2);
        assertEquals(2, found.size());
        assertEquals("app:youtube", found.get(0).getId());
        assertEquals(0, SearchMatcher.ranked(documents, "you", -1).size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void resultsAreReadOnly() {
        SearchMatcher.ranked(documents, "netflix").clear();
    }

    @Test
    public void normalizeKeepsOnlyLettersAndDigits() {
        assertEquals("tv network", SearchMatcher.normalize("  TV & Network!! "));
    }

    @Test
    public void typoDistance() {
        assertEquals(0, Typos.distance("netflix", "netflix"));
        assertEquals(1, Typos.distance("netlfix", "netflix"));
        assertEquals(1, Typos.distance("netflx", "netflix"));
        assertEquals(2, Typos.distance("woriors", "warriors"));
        assertEquals(3, Typos.distance("", "abc"));
    }

    @Test(expected = NullPointerException.class)
    public void documentsRejectNull() {
        new SearchDocument("id", null);
    }
}
