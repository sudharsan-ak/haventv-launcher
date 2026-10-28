package com.aktvhome.launcher.ui.watch

import com.aktvhome.launcher.data.Game
import com.aktvhome.launcher.data.GameState
import com.aktvhome.launcher.data.LiveGroup
import com.aktvhome.launcher.data.PRIME
import com.aktvhome.launcher.data.Team
import com.aktvhome.launcher.data.TvItem
import com.aktvhome.launcher.data.YOUTUBE_TV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WatchOptionsTest {
    private fun game(network: String) = Game(
        "game-1", Instant.parse("2026-10-27T23:00:00Z"),
        Team("BKN", "Nets", "90", "000000", null),
        Team("BOS", "Celtics", "91", "000000", null),
        GameState.LIVE, "Q4", network,
    )

    @Test fun primeGameUsesDirectPublishedGameLinkInsteadOfAppResume() {
        val item = TvItem(PRIME, "Brooklyn Nets vs Boston Celtics", "NBA live", null, true,
            "https://example.invalid/live-asset")
        var launched = ""
        val opts = watchOptions(game("Prime Video"), WatchSources(
            labelOf = { if (it == PRIME) "Prime Video" else null },
            localStations = emptyMap(), live = emptyList(),
            appRows = listOf(LiveGroup(PRIME, "Prime Video", listOf(item))),
            openItem = { launched = it.intentUri.orEmpty() }, openApp = { launched = "app:$it" },
            openGame = {},
        ))
        val prime = opts.first { it.app == "Prime Video" }
        assertTrue(prime.label.contains("game"))
        prime.open()
        assertEquals(item.intentUri, launched)
    }

    @Test fun youTubeTvPrefersDirectLiveChannelToUnlinkedPreview() {
        val old = TvItem(YOUTUBE_TV, "ESPN", "", null, false, null)
        val live = TvItem(YOUTUBE_TV, "ESPN", "", null, true, "https://example.invalid/live")
        var launched: TvItem? = null
        val opts = watchOptions(game("ESPN"), WatchSources(
            labelOf = { if (it == YOUTUBE_TV) "YouTube TV" else null },
            localStations = emptyMap(),
            live = listOf(LiveGroup(YOUTUBE_TV, "YouTube TV", listOf(old, live))),
            appRows = emptyList(), openItem = { launched = it },
            openApp = {}, openGame = {},
        ))
        opts.first { it.app == "YouTube TV" }.open()
        assertEquals(live, launched)
    }


    @Test fun includesNbcOverflowFeedWhenListedBroadcasterIsPeacock() {
        val regular = TvItem(YOUTUBE_TV, "NBC", "", null, true, "content://example/nbc")
        val overflow = TvItem(YOUTUBE_TV, "NBCSN Extra", "", null, true, "content://example/extra")
        val other = TvItem(YOUTUBE_TV, "ESPN2", "", null, true, "content://example/espn")
        val out = youtubeTvChannelCandidates(
            game("Peacock, NBC"), listOf(LiveGroup(YOUTUBE_TV, "YouTube TV", listOf(regular, overflow, other))),
            emptyMap(),
        )
        assertEquals(listOf("NBC", "NBCSN Extra"), out.map { it.title })
    }

    @Test fun peacockOnlyStillShowsKnownNbcAndOverflowAlternatives() {
        val regular = TvItem(YOUTUBE_TV, "NBC", "", null, false, "content://example/nbc")
        val overflow = TvItem(YOUTUBE_TV, "NBCSN Extra 3", "", null, false, "content://example/extra")
        val out = youtubeTvChannelCandidates(
            game("Peacock"), listOf(LiveGroup(YOUTUBE_TV, "YouTube TV", listOf(regular, overflow))),
            emptyMap(),
        )
        assertEquals(listOf("NBC", "NBCSN Extra 3"), out.map { it.title })
    }

    @Test fun discoversEspnFamilyWithoutInventingUndiscoveredLinks() {
        val channels = listOf(
            TvItem(YOUTUBE_TV, "ESPN", "", null, true, "content://example/espn"),
            TvItem(YOUTUBE_TV, "ESPN2", "", null, true, "content://example/espn2"),
            TvItem(YOUTUBE_TV, "ESPNU", "", null, false, null),
            TvItem(YOUTUBE_TV, "NBCSN Extra", "", null, true, "content://example/nbc"),
        )
        val out = youtubeTvChannelCandidates(
            game("ESPN"), listOf(LiveGroup(YOUTUBE_TV, "YouTube TV", channels)), emptyMap(),
        )
        assertEquals(listOf("ESPN", "ESPN2"), out.map { it.title })
    }

    @Test fun youtubeTvAlternativeAvailableForPeacockWithoutAChannelMapping() {
        assertTrue(youtubeTvMayCarry(game("Peacock")))
        assertTrue(youtubeTvChannelCandidates(game("Peacock"), emptyList(), emptyMap()).isEmpty())
    }

    @Test fun appOnlyFallbackIsNotMisrepresentedAsDirectStream() {
        val options = watchOptions(game("Prime Video"), WatchSources(
            labelOf = { if (it == PRIME) "Prime Video" else null },
            localStations = emptyMap(), live = emptyList(), appRows = emptyList(),
            openItem = {}, openApp = {}, openGame = {},
        ))
        assertEquals("Prime Video", options.first { it.app == "Prime Video" }.label)
    }
}
