package dev.lelonio.square.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricTimelineTest {
    private fun timeline(vararg starts: Long?) = LyricTimeline(
        Lyrics(starts.mapIndexed { index, at -> LyricLine(at, "Line $index") }, synced = true),
    )

    @Test fun highlightsOnlyAfterTheLineStartsAndKeepsTheLastLine() {
        val timeline = timeline(1_000, 3_000, 8_000)
        assertEquals(-1, timeline.activeLineAt(999))
        assertEquals(0, timeline.activeLineAt(1_000))
        assertEquals(0, timeline.activeLineAt(2_999))
        assertEquals(1, timeline.activeLineAt(3_000))
        assertEquals(2, timeline.activeLineAt(Long.MAX_VALUE))
    }

    @Test fun seekingBackwardsAndForwardsDoesNotKeepAStaleLine() {
        val timeline = timeline(0, 2_000, 5_000)
        assertEquals(2, timeline.activeLineAt(6_000))
        assertEquals(0, timeline.activeLineAt(500))
        assertEquals(1, timeline.activeLineAt(2_500))
        assertEquals(-1, timeline.activeLineAt(-1))
    }

    @Test fun simultaneousLinesUseTheLastSourceLine() {
        val timeline = timeline(0, 2_000, 2_000, 4_000)
        assertEquals(0, timeline.activeLineAt(1_999))
        assertEquals(2, timeline.activeLineAt(2_000))
    }

    @Test fun emptyAndUnsyncedLyricsNeverSelectALine() {
        assertEquals(-1, timeline().activeLineAt(1_000))
        val unsynced = LyricTimeline(Lyrics(listOf(LyricLine(0, "Plain text")), synced = false))
        assertEquals(-1, unsynced.activeLineAt(1_000))
    }

    @Test fun missingAndOutOfOrderTimestampsKeepTheExistingSourceOrderSemantics() {
        assertEquals(0, timeline(null, 1_000).activeLineAt(0))
        val unordered = timeline(0, 5_000, 2_000, 8_000)
        assertEquals(0, unordered.activeLineAt(1_000))
        assertEquals(2, unordered.activeLineAt(3_000))
        assertEquals(2, unordered.activeLineAt(6_000))
        assertEquals(3, unordered.activeLineAt(9_000))
    }
}
