package dev.lelonio.square.data

import org.junit.Assert.*
import org.junit.Test

class DjWindowTest {
    private val enabled = DjModeSettings(enabled = true)

    @Test fun smartRangeMatchesPublishedTimelineAndReservesFade() {
        val planner = DjTransitionPlanner()
        assertEquals(DjWindow(156_000, 204_000), planner.window(240_000, 6_000, 0, enabled))
        assertEquals(DjWindow(156_000, 204_000), planner.window(240_000, 6_000, 190_000, enabled))
        assertEquals(DjWindow(58_500, 70_000), DjTransitionPlanner().window(90_000, 20_000, 0, enabled))
    }

    @Test fun lateActivationWaitsUntilSongHasPlayedBeforeRange() {
        val planner = DjTransitionPlanner()
        assertNull(planner.window(240_000, 6_000, 190_000, enabled))
        assertNotNull(planner.window(240_000, 6_000, 100_000, enabled))
        planner.reset()
        assertNull(planner.window(240_000, 6_000, 190_000, enabled))
    }

    @Test fun fixedAndNonSmartModesPublishAnExactPoint() {
        assertEquals(DjWindow(180_000, 180_000), DjTransitionPlanner { 75 }
            .window(240_000, 6_000, 0, enabled.copy(smart = false)))
        assertEquals(DjWindow(180_000, 180_000), DjTransitionPlanner { 75 }
            .window(240_000, 6_000, 0, enabled.copy(timing = DjTiming.THREE_QUARTERS)))
    }

    @Test fun zonesOnlyBelongToTheirTrackAndClampToDuration() {
        val zone = DjSession.Zone("song", 65, 85)
        assertEquals(0.65f..0.85f, zone.fractionFor("song", 100))
        assertNull(zone.fractionFor("other", 100))
        assertNull(zone.fractionFor("song", 0))
        assertEquals(1f..1f, zone.fractionFor("song", 50))
    }

    @Test fun energyDropRequiresWarmupAndResetClearsIt() {
        val meter = DjEnergyMeter()
        repeat(19) { meter.measure(48_000.0, 48_000, 48_000) }
        assertFalse(meter.easingOff)
        repeat(5) { meter.measure(48_000.0, 48_000, 48_000) }
        assertFalse(meter.easingOff)
        meter.measure(0.0, 48_000, 48_000)
        assertTrue(meter.easingOff)
        meter.reset()
        assertFalse(meter.easingOff)
        meter.measure(0.0, 48_000, 48_000)
        assertFalse(meter.easingOff)
    }
    @Test fun undoReturnsToPreviousSongAndKeepsItInFull() {
        val undo = DjSession.Undo("previous", 2, 156_000)
        var received: DjSession.Undo? = null
        try {
            DjSession.undoHandler = { received = it }
            DjSession.publishUndo(undo)
            DjSession.undo()
            assertEquals(undo, received)
            assertEquals("previous", DjSession.kept.value)
            assertNull(DjSession.undo.value)
            DjSession.toggleKept("previous")
            assertNull(DjSession.kept.value)
        } finally {
            DjSession.undoHandler = null
            DjSession.publishUndo(null)
            DjSession.setKept(null)
        }
    }

    @Test fun disabledAndShortTracksNeverPublishASmartWindow() {
        val planner = DjTransitionPlanner()
        assertNull(planner.window(240_000, 6_000, 0, enabled.copy(enabled = false)))
        assertNull(planner.window(89_999, 6_000, 0, enabled))
        assertNull(planner.window(240_000, 0, 0, enabled))
    }

}
