package dev.lelonio.square.data

/** Cached line timestamps; the frequent playback ticks do not scan a sorted song's full lyrics. */
internal class LyricTimeline(lyrics: Lyrics) {
    private val starts = if (lyrics.synced) {
        LongArray(lyrics.lines.size) { lyrics.lines[it].startTimeMs ?: 0L }
    } else {
        LongArray(0)
    }
    private val ordered = (1 until starts.size).all { starts[it - 1] <= starts[it] }

    fun activeLineAt(positionMs: Long): Int {
        // Preserve source order for malformed/out-of-order timestamps instead of reordering the text.
        if (!ordered) return starts.indexOfLast { it <= positionMs }
        var low = 0
        var high = starts.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (starts[middle] <= positionMs) low = middle + 1 else high = middle
        }
        return low - 1
    }
}
