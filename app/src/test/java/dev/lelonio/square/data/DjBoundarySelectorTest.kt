package dev.lelonio.square.data

import org.junit.Assert.*
import org.junit.Test

class DjBoundarySelectorTest {
    private fun frames(endMs: Long = 12_000, level: (Long) -> Double) =
        (0L..endMs step 25).map { DjAudioFrame(it, level(it)) }

    @Test fun clearDipIsChosenInsideMixingWindowWithoutLyrics() {
        val audio = frames { if (it in 8_000..8_800) 0.05 else 1.0 }
        val choice = DjBoundarySelector.choose(audio, DjWindow(7_000, 10_000))
        assertEquals(DjBoundarySelector.Reason.QUIET_BOUNDARY, choice.reason)
        assertTrue(choice.timeMs in 8_000..8_800)
    }

    @Test fun silenceAndFlatAudioHaveAnUnambiguousDeadline() {
        for (level in listOf(0.0, 1.0)) {
            assertEquals(DjBoundarySelector.Choice(10_000, DjBoundarySelector.Reason.DEADLINE),
                DjBoundarySelector.choose(frames { level }, DjWindow(7_000, 10_000)))
        }
    }

    @Test fun dipsOutsideTheWindowDoNotCauseAnEarlySkip() {
        val audio = frames { if (it in 3_000..4_000) 0.01 else 1.0 }
        assertEquals(10_000L, DjBoundarySelector.choose(audio, DjWindow(7_000, 10_000)).timeMs)
    }

    @Test fun steadyAttacksChooseARealBeatNearTheLimitWhenNoDipExists() {
        val audio = frames { if (it % 500 == 0L) 2.0 else 1.4 }
        val choice = DjBoundarySelector.choose(audio, DjWindow(7_000, 9_875))
        assertEquals(DjBoundarySelector.Reason.BEAT, choice.reason)
        assertEquals(9_500L, choice.timeMs)
    }

    @Test fun wordTimingProtectsAQuietButStillSungWord() {
        val audio = frames { if (it in 8_000..8_800) 0.05 else 1.0 }
        val lyrics = Lyrics(listOf(LyricLine(7_000, "singing", words = listOf(LyricWord(7_000, 9_000, "singing")))), true)
        val choice = DjBoundarySelector.choose(audio, DjWindow(7_000, 10_000), lyrics)
        assertEquals(DjBoundarySelector.Reason.DEADLINE, choice.reason)
    }

    @Test fun lyricPauseWinsBetweenTwoComparableAudioDips() {
        val audio = frames { if (it in 4_000..4_700 || it in 8_000..8_700) 0.05 else 1.0 }
        val lyrics = Lyrics(listOf(LyricLine(8_100, "")), true)
        val choice = DjBoundarySelector.choose(audio, DjWindow(3_000, 10_000), lyrics)
        assertEquals(DjBoundarySelector.Reason.LYRIC_PAUSE, choice.reason)
        assertTrue(choice.timeMs in 8_000..8_700)
    }

    @Test fun lineOnlyTimingsProtectTheBeginningOfASungLine() {
        val audio = frames { if (it in 8_000..8_700) 0.05 else 1.0 }
        val lyrics = Lyrics(listOf(LyricLine(7_900, "a new phrase begins")), true)
        assertEquals(DjBoundarySelector.Reason.DEADLINE,
            DjBoundarySelector.choose(audio, DjWindow(7_000, 10_000), lyrics).reason)
    }

    @Test fun missingCorruptAndFixedPointAudioUseTheDeadline() {
        val range = DjWindow(7_000, 10_000)
        assertEquals(10_000L, DjBoundarySelector.choose(emptyList(), range).timeMs)
        assertEquals(10_000L, DjBoundarySelector.choose(frames { Double.NaN }, range).timeMs)
        assertEquals(7_500L, DjBoundarySelector.choose(frames { 1.0 }, DjWindow(7_500, 7_500)).timeMs)
    }
}
