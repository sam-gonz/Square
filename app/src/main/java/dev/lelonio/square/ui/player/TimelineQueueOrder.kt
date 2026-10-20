package dev.lelonio.square.ui.player

/**
 * Reads one bounded traversal from the player's timeline, preserving real
 * window indices. A seen guard handles malformed or cyclic repeat orders;
 * omitted windows are appended in source order so every media item remains
 * addressable in the queue UI.
 */
internal fun timelineQueueOrder(
    itemCount: Int,
    firstIndex: Int,
    nextWindowIndex: (Int) -> Int,
): List<Int> {
    if (itemCount <= 0) return emptyList()

    val result = ArrayList<Int>(itemCount)
    val seen = BooleanArray(itemCount)
    var index = firstIndex.takeIf { it in 0 until itemCount } ?: 0
    while (index in 0 until itemCount && !seen[index] && result.size < itemCount) {
        seen[index] = true
        result += index
        index = nextWindowIndex(index)
    }

    for (sourceIndex in 0 until itemCount) {
        if (!seen[sourceIndex]) result += sourceIndex
    }
    return result
}

/** Reorders real timeline indices, keeping the supplied move order intact. */
internal fun moveTimelineIndicesBefore(
    order: List<Int>,
    movingIndices: List<Int>,
    beforeIndex: Int?,
): List<Int> {
    val moving = movingIndices.distinct().filter { it in order }
    if (moving.isEmpty() || (beforeIndex != null && beforeIndex in moving)) return order

    val movingSet = moving.toSet()
    val remaining = order.filterNot { it in movingSet }.toMutableList()
    val insertion = beforeIndex?.let(remaining::indexOf)?.takeIf { it >= 0 } ?: remaining.size
    remaining.addAll(insertion, moving)
    return remaining
}

internal data class VisibleQueueIndex(val index: Int, val played: Boolean)

/** One repeat-all cycle, with a short history prefix and no repeated current item. */
internal fun repeatAllVisibleOrder(
    order: List<Int>,
    currentIndex: Int,
    historyCount: Int,
): List<VisibleQueueIndex> {
    val currentPosition = order.indexOf(currentIndex)
    if (currentPosition < 0 || order.isEmpty()) return order.map { VisibleQueueIndex(it, false) }
    val history = if (currentPosition > 0) {
        order.subList((currentPosition - historyCount).coerceAtLeast(0), currentPosition)
    } else {
        order.drop(1).takeLast(historyCount)
    }
    val cycle = listOf(order[currentPosition]) + order.drop(currentPosition + 1) + order.take(currentPosition)
    return history.map { VisibleQueueIndex(it, true) } + cycle.map { VisibleQueueIndex(it, false) }
}
