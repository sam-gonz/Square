package dev.lelonio.square.data

import dev.lelonio.square.backend.youtube.EnergyCollector
import org.junit.Assert.*
import org.junit.Test

class DjSampleClockTest {
    @Test fun rendererOffsetDoesNotEndAnalysisBeforeScanningAudio() {
        val clock = DjSampleClock(115_809)
        val collector = EnergyCollector(115_809, 161_904)
        var completed = false
        collector.onComplete = { completed = true }
        // Timestamp observed on the phone: renderer origin plus the song position.
        val originUs = 1_000_115_831_000L
        val rate = 44_100
        var decodedFrames = 0L
        repeat(2_000) {
            val start = clock.bufferStartUs(originUs + decodedFrames * 1_000_000 / rate)
            repeat(1_024) { frame ->
                collector.sample((start + frame * 1_000_000L / rate) / 1_000, 0.25, rate)
            }
            clock.advance(start, 1_024, rate)
            decodedFrames += 1_024
            if (it == 0) assertFalse(completed)
        }
        assertTrue(completed)
        assertTrue(collector.snapshot().size > 1_800)
        assertEquals(115_809L, collector.firstMs)
        assertTrue(collector.snapshot().all { it.timeMs in 115_809 until 161_904 })
    }

    @Test fun missingTimestampsContinueFromDecodedFrames() {
        val clock = DjSampleClock(60_000)
        val first = clock.bufferStartUs(null)
        assertEquals(60_000_000L, first)
        clock.advance(first, 48_000, 48_000)
        val second = clock.bufferStartUs(1_000_061_000_000)
        assertEquals(61_000_000L, second)
        clock.advance(second, 24_000, 48_000)
        assertEquals(61_500_000L, clock.bufferStartUs(null))
    }
}
