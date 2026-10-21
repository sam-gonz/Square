package dev.lelonio.square.data

import org.junit.Assert.*
import org.junit.Test

class DjTransitionPlannerTest {
    private val enabled = DjModeSettings(enabled = true)

    @Test fun fixedSeventyFivePercentPublishesThreeMinutesForFourMinuteSong() {
        val planner = DjTransitionPlanner()
        val fixed = enabled.copy(timing = DjTiming.THREE_QUARTERS)
        assertEquals(180_000L, planner.transitionAt(240_000, 6_000, 0, fixed))
        assertEquals(180_000L, planner.transitionAt(240_000, 12_000, 179_000, fixed))
    }

    @Test fun musicalPointStaysFixedAcrossProgressSeeksAndLaterAnalysis() {
        val planner = DjTransitionPlanner()
        val chosen = DjWindow(165_000, 165_000)
        assertEquals(chosen, planner.window(240_000, 6_000, 0, enabled, 165_000))
        assertEquals(chosen, planner.window(240_000, 6_000, 140_000, enabled, 190_000))
        assertEquals(chosen, planner.window(240_000, 6_000, 20_000, enabled, 200_000))
        planner.reset()
        assertEquals(DjWindow(190_000, 190_000), planner.window(240_000, 6_000, 0, enabled, 190_000))
    }

    @Test fun disabledCrossfadeUnknownDurationsAndShortSongsNeverSchedule() {
        val planner = DjTransitionPlanner()
        assertNull(planner.transitionAt(240_000, 6_000, 0, DjModeSettings()))
        assertNull(planner.transitionAt(240_000, 0, 0, enabled))
        assertNull(planner.transitionAt(0, 6_000, 0, enabled))
        assertNull(planner.transitionAt(-1, 6_000, 0, enabled))
        assertNull(planner.transitionAt(89_999, 6_000, 0, enabled))
    }

    @Test fun enablingLateDoesNotImmediatelySkipTheSong() {
        val planner = DjTransitionPlanner()
        assertNull(planner.transitionAt(240_000, 6_000, 200_000, enabled))
        assertNull(planner.transitionAt(240_000, 6_000, 210_000, enabled))
        assertEquals(204_000L, planner.transitionAt(240_000, 6_000, 100_000, enabled))
    }

    @Test fun changingTimingOrDisablingClearsThePreviousSchedule() {
        val planner = DjTransitionPlanner()
        assertEquals(204_000L, planner.transitionAt(240_000, 6_000, 0, enabled))
        val fixed = enabled.copy(timing = DjTiming.THREE_QUARTERS)
        assertNull(planner.transitionAt(240_000, 6_000, 190_000, fixed))
        assertNull(planner.transitionAt(240_000, 6_000, 190_000, fixed.copy(enabled = false)))
        assertNull(planner.transitionAt(240_000, 6_000, 190_000, fixed))
    }

    @Test fun everyPresetReservesTheEntireFadeBeforeTrackEnd() {
        for (timing in DjTiming.entries) {
            val planner = DjTransitionPlanner()
            val range = planner.range(90_000, 20_000, 0, enabled.copy(timing = timing))!!
            assertTrue(range.startMs >= 45_000)
            assertTrue(range.startMs <= range.endMs)
            assertTrue(range.endMs + 20_000 <= 90_000)
        }
    }

    @Test fun stalledAnalysisCannotWaitPastPreparationDeadlineOrMoveFallbackLater() {
        val planner = DjTransitionPlanner()
        assertNull(planner.window(240_000, 6_000, 0, enabled, analysisPending = true))
        val fallback = DjWindow(204_000, 204_000)
        assertEquals(fallback, planner.window(240_000, 6_000, 148_000, enabled, analysisPending = true))
        assertEquals(fallback, planner.window(240_000, 6_000, 149_000, enabled, 170_000))
    }

    @Test fun invalidOrTooLateProposalsUseTheUpperLimit() {
        val planner = DjTransitionPlanner()
        assertEquals(DjWindow(204_000, 204_000), planner.window(240_000, 6_000, 0, enabled, 300_000))
        planner.reset()
        assertNull(planner.window(240_000, 6_000, 0, enabled, analysisPending = true))
        assertEquals(DjWindow(204_000, 204_000), planner.window(240_000, 6_000, 150_000, enabled, 156_000))
    }
}
