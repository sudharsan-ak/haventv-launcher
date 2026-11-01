package com.aktvhome.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingSortTest {
    private fun item(id: String, kind: PlaybackKind, updated: Long) =
        PlayingMedia(id, "sample.app", id, null, kind, updated, null, null)

    @Test fun playingBeforePausedEvenWhenPausedWasUpdatedMoreRecently() {
        val paused = item("paused", PlaybackKind.PAUSED, 50)
        val playing = item("playing", PlaybackKind.PLAYING, 10)
        assertEquals(listOf(playing, paused), sortMediaSessions(listOf(paused, playing)))
    }

    @Test fun mostRecentlyUpdatedSessionFirstWithinSameState() {
        val old = item("old", PlaybackKind.PAUSED, 10)
        val newer = item("new", PlaybackKind.PAUSED, 20)
        assertEquals(listOf(newer, old), sortMediaSessions(listOf(old, newer)))
    }
}
