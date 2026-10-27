package com.aktvhome.launcher.data

import com.aktvhome.launcher.core.search.SearchDocument
import com.aktvhome.launcher.core.search.SearchMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search tests from before the ranking moved to Java (haven-core), run from Kotlin against
 * the Java engine: they pass unchanged, so the move didn't change any result. Java takes no named
 * arguments, so the documents pass their (blank) description positionally.
 */
class SearchMatcherTest {
    private val documents = listOf(
        SearchDocument("warriors", "Golden State Warriors", "NBA team", "", "GS GSW Warriors favorite team"),
        SearchDocument("settings", "Favorite team", "Watching & alerts", "", "NBA Warriors"),
        SearchDocument("channel", "ESPN", "YouTube TV · Live channel", "", "basketball sports"),
        SearchDocument("app", "Netflix", "Installed app", "", "movies streaming"),
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

    @Test fun resultLimitKeepsOnlyHighestRankedMatches() {
        val many = (1..20).map { SearchDocument("app:$it", "Game $it") }
        val results = SearchMatcher.ranked(many, "Game", 15)
        assertEquals(15, results.size)
    }
}
