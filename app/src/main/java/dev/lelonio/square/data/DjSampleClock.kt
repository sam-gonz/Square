package dev.lelonio.square.data

/** Converts decoder timestamps (which may have a renderer offset) to the requested song segment. */
internal class DjSampleClock(private val scanStartMs: Long) {
    private var originUs: Long? = null
    private var nextUs = 0L

    fun bufferStartUs(timestampUs: Long?): Long {
        if (timestampUs == null) return scanStartMs * 1_000 + nextUs
        val origin = originUs ?: (timestampUs - nextUs).also { originUs = it }
        return scanStartMs * 1_000 + (timestampUs - origin).coerceAtLeast(nextUs)
    }

    fun advance(bufferStartUs: Long, frames: Int, sampleRate: Int) {
        nextUs = bufferStartUs - scanStartMs * 1_000 + frames * 1_000_000L / sampleRate
    }
}
