package com.aktvhome.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatcherTest {
    private val documents = listOf(
        SearchDocument("warriors", "Golden State Warriors", "NBA team", keywords = "GS GSW Warriors favorite team"),
        SearchDocument("settings", "Favorite team", "Watching & alerts", keywords = "NBA Warriors"),
        SearchDocument("channel", "ESPN", "YouTube TV · Live channel", keywords = "basketball sports"),
        SearchDocument("app", "Netflix", "Installed app", keywords = "movies streaming"),
    )

    @Test fun exactNameRanksFirst() {
        assertEquals("app", SearchMatcher.ranked(documents, "Netflix").first().id)
    }

    @Test fun nicknameFindsTeam() {
        assertEquals("warriors", SearchMatcher.ranked(documents, "Warriors").first().id)
    }

    @Test fun abbreviationFindsTeam() {
        assertEquals("warriors", SearchMatcher.ranked(documents, "GSW").first().id)
    }

    @Test fun everyWordMustMatch() {
        assertEquals(listOf("warriors"), SearchMatcher.ranked(documents, "Golden Warriors").map { it.id })
    }

    @Test fun unrelatedQueryIsEmpty() {
        assertTrue(SearchMatcher.ranked(documents, "Formula One").isEmpty())
    }

    @Test fun duplicateResultIdsAreRemovedBeforeFiltering() {
        val duplicate = SearchDocument("tv:disney:Nikki Glaser", "Nikki Glaser: Good Girl")
        assertEquals(1, SearchMatcher.ranked(listOf(duplicate, duplicate), "Nikki").size)
    }
}
