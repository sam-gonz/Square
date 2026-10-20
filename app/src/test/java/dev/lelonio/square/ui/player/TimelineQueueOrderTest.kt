package dev.lelonio.square.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineQueueOrderTest {
    @Test
    fun followsTimelineShuffleOrderAndKeepsActualIndices() {
        val next = mapOf(3 to 1, 1 to 4, 4 to 0, 0 to 2, 2 to -1)

        assertEquals(listOf(3, 1, 4, 0, 2), timelineQueueOrder(5, 3) { next.getValue(it) })
    }

    @Test
    fun boundsCyclicRepeatTraversalToOneQueueCycle() {
        val next = mapOf(2 to 0, 0 to 1, 1 to 2)

        assertEquals(listOf(2, 0, 1), timelineQueueOrder(3, 2) { next.getValue(it) })
    }

    @Test
    fun appendsUnreachableWindowsWithoutInventingShuffle() {
        assertEquals(listOf(2, 0, 1, 3), timelineQueueOrder(4, 2) { -1 })
        assertEquals(listOf(0, 1, 2), timelineQueueOrder(3, 99) { -1 })
        assertEquals(emptyList<Int>(), timelineQueueOrder(0, 0) { -1 })
    }

    @Test
    fun movesSelectedIndicesBeforeTargetAndHandlesEnd() {
        val order = listOf(4, 1, 3, 0, 2)

        assertEquals(order, moveTimelineIndicesBefore(order, listOf(1, 3), 0))
        assertEquals(listOf(4, 0, 1, 3, 2), moveTimelineIndicesBefore(order, listOf(1, 3), 2))
        assertEquals(listOf(4, 0, 2, 1, 3), moveTimelineIndicesBefore(order, listOf(1, 3), null))
        assertEquals(order, moveTimelineIndicesBefore(order, listOf(1), 1))
    }

    @Test
    fun repeatAllWrapsOnceAndDoesNotDuplicateCurrentInHistory() {
        assertEquals(
            listOf(
                VisibleQueueIndex(0, false),
                VisibleQueueIndex(1, false),
                VisibleQueueIndex(2, false),
                VisibleQueueIndex(3, false),
                VisibleQueueIndex(4, false),
            ),
            repeatAllVisibleOrder(listOf(0, 1, 2, 3, 4), 0, historyCount = 20),
        )
        assertEquals(
            listOf(
                VisibleQueueIndex(0, true),
                VisibleQueueIndex(1, true),
                VisibleQueueIndex(2, false),
                VisibleQueueIndex(3, false),
                VisibleQueueIndex(4, false),
                VisibleQueueIndex(0, false),
                VisibleQueueIndex(1, false),
            ),
            repeatAllVisibleOrder(listOf(0, 1, 2, 3, 4), 2, historyCount = 2),
        )
    }

    @Test
    fun startOfQueueHasNoPlayedHistory() {
        val result = repeatAllVisibleOrder(listOf(3, 1, 4, 0, 2), 3, historyCount = 5)
        assertEquals(listOf(3, 1, 4, 0, 2), result.map { it.index })
        assertEquals(listOf(false, false, false, false, false), result.map { it.played })
    }
}
