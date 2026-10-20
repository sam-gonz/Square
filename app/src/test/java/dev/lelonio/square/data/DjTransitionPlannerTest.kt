package dev.lelonio.square.data

import org.junit.Assert.*
import org.junit.Test

class DjTransitionPlannerTest {
    private val enabled = DjModeSettings(enabled = true)

    @Test fun fourMinuteSongAtSeventyFivePercentStartsItsFadeAtThreeMinutes() {
        val planner = DjTransitionPlanner { 75 }
        assertEquals(180_000L, planner.transitionAt(240_000, 6_000, 0, enabled))
        assertEquals(180_000L, planner.transitionAt(240_000, 12_000, 179_000, enabled))
    }

    @Test fun repeatedProgressAndSeeksKeepTheChosenPointUntilTheNextPlay() {
        var choices = 0
        val planner = DjTransitionPlanner { if (choices++ == 0) 65 else 85 }
        assertEquals(156_000L, planner.transitionAt(240_000, 6_000, 0, enabled))
        assertEquals(156_000L, planner.transitionAt(240_000, 6_000, 140_000, enabled))
        assertEquals(156_000L, planner.transitionAt(240_000, 6_000, 20_000, enabled))
        assertEquals(1, choices)
        planner.reset()
        assertEquals(204_000L, planner.transitionAt(240_000, 6_000, 0, enabled))
        assertEquals(2, choices)
    }

    @Test fun disabledCrossfadeUnknownDurationsAndShortSongsNeverSchedule() {
        val planner = DjTransitionPlanner { error("ineligible track sampled") }
        assertNull(planner.transitionAt(240_000, 6_000, 0, DjModeSettings()))
        assertNull(planner.transitionAt(240_000, 0, 0, enabled))
        assertNull(planner.transitionAt(0, 6_000, 0, enabled))
        assertNull(planner.transitionAt(-1, 6_000, 0, enabled))
        assertNull(planner.transitionAt(89_999, 6_000, 0, enabled))
    }

    @Test fun enablingOrResumingLateDoesNotImmediatelySkipTheSong() {
        val planner = DjTransitionPlanner { 75 }
        assertNull(planner.transitionAt(240_000, 6_000, 200_000, enabled))
        assertNull(planner.transitionAt(240_000, 6_000, 210_000, enabled))
        assertEquals(180_000L, planner.transitionAt(240_000, 6_000, 100_000, enabled))
        assertEquals(180_000L, planner.transitionAt(240_000, 6_000, 181_000, enabled))
    }

    @Test fun changingTimingOrDisablingClearsThePreviousSchedule() {
        val planner = DjTransitionPlanner { it.first }
        assertEquals(156_000L, planner.transitionAt(240_000, 6_000, 0, enabled))
        val fixed = enabled.copy(timing = DjTiming.THREE_QUARTERS)
        assertNull(planner.transitionAt(240_000, 6_000, 190_000, fixed))
        assertNull(planner.transitionAt(240_000, 6_000, 190_000, fixed.copy(enabled = false)))
        assertNull(planner.transitionAt(240_000, 6_000, 190_000, fixed))
    }

    @Test fun everyPresetAcceptsItsInclusiveRangeAndReservesTimeForTheFade() {
        for (timing in DjTiming.entries) {
            for (percent in listOf(timing.minPercent, timing.maxPercent)) {
                val planner = DjTransitionPlanner {
                    assertTrue(percent in it)
                    percent
                }
                val target = planner.transitionAt(90_000, 12_000, 0, enabled.copy(timing = timing))!!
                assertTrue(target >= 45_000)
                assertTrue(target + 12_000 <= 90_000)
            }
        }
    }
}
