package dev.lelonio.square.data

import dev.lelonio.square.backend.youtube.YouTubeShuffleMetadata
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class DiscoveryShuffleTest {
    @Test fun newerReleasesAndOldHitsOutweighOldUnfeaturedMusic() {
        val old = DiscoveryShuffle.Signal(year = 1995)
        val fresh = DiscoveryShuffle.Signal(year = 2026)
        val oldHit = old.copy(popularRank = 1)
        assertTrue(DiscoveryShuffle.weight(fresh, 2026) > DiscoveryShuffle.weight(old, 2026) * 20)
        assertTrue(DiscoveryShuffle.weight(oldHit, 2026) > DiscoveryShuffle.weight(old, 2026) * 20)
        assertTrue(DiscoveryShuffle.weight(old, 2026) > 0)
    }

    @Test fun weightedDrawsFavorNewMusicButKeepEveryEntryOnce() {
        val signals = listOf(DiscoveryShuffle.Signal(1995), DiscoveryShuffle.Signal(2026), DiscoveryShuffle.Signal(2000, 1))
        val starts = IntArray(3)
        val random = Random(42)
        repeat(2000) {
            val order = DiscoveryShuffle.order(signals, 2026, random)
            assertEquals(setOf(0, 1, 2), order.toSet())
            starts[order.first()]++
        }
        assertTrue(starts[1] > starts[0] * 10)
        assertTrue(starts[2] > starts[0] * 10)
    }

    @Test fun missingAndInvalidReleaseYearsRemainNeutral() {
        val neutral = DiscoveryShuffle.weight(DiscoveryShuffle.Signal(), 2026)
        assertEquals(neutral, DiscoveryShuffle.weight(DiscoveryShuffle.Signal(0), 2026), 0.0)
        assertEquals(neutral, DiscoveryShuffle.weight(DiscoveryShuffle.Signal(2099), 2026), 0.0)
        assertEquals(emptyList<Int>(), DiscoveryShuffle.order(emptyList(), 2026))
    }

    @Test fun shuffleStartDoesNotAlwaysPickFirstPlaylistSong() {
        val tracks = (0..9).map { CatalogTrack("ytmusic:track:test$it", "Track $it", "Artist") }
        for (preferred in listOf(false, true)) {
            val random = Random(7)
            val starts = (0 until 100).map { YouTubeShuffleMetadata.firstIndex(tracks, preferred, random) }.toSet()
            assertTrue(starts.size > 5)
            assertTrue(starts.all { it in tracks.indices })
        }
    }
}
