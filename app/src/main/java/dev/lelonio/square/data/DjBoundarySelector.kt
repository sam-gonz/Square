package dev.lelonio.square.data

import kotlin.math.abs
import kotlin.math.sqrt

/** Low-rate measurements of decoded audio; no PCM is retained after measurement. */
data class DjAudioFrame(val timeMs: Long, val rms: Double)

/** Classical signal analysis: sustained dips first, a reliable beat near the deadline second. */
object DjBoundarySelector {
    enum class Reason { QUIET_BOUNDARY, LYRIC_PAUSE, BEAT, DEADLINE }
    data class Choice(val timeMs: Long, val reason: Reason)

    fun choose(frames: List<DjAudioFrame>, range: DjWindow, lyrics: Lyrics? = null): Choice {
        val fallback = Choice(range.endMs, Reason.DEADLINE)
        val audio = frames.filter { it.rms.isFinite() && it.rms >= 0 }.sortedBy { it.timeMs }
        if (audio.size < 40 || range.endMs <= range.startMs) return fallback
        val levels = audio.map { it.rms }.sorted()
        val reference = levels[(levels.lastIndex * 0.75).toInt()]
        if (reference < 0.0001) return fallback
        val words = lyrics?.takeIf { it.synced }?.lines.orEmpty().flatMap { it.words }
            .filter { it.endMs >= range.startMs - 100 && it.startMs <= range.endMs + 100 }
        val pauses = lyrics?.takeIf { it.synced }?.lines.orEmpty().mapNotNull { line ->
            line.startTimeMs?.takeIf { line.text.isBlank() }
        }.toMutableList()
        val lines = lyrics?.takeIf { it.synced }?.lines.orEmpty()
        lines.zipWithNext().forEach { (line, next) ->
            val end = line.words.maxOfOrNull { it.endMs }
            val nextStart = next.startTimeMs
            if (end != null && nextStart != null && nextStart - end >= 500) pauses += end + 200
        }
        val prefix = DoubleArray(audio.size + 1)
        audio.forEachIndexed { i, frame -> prefix[i + 1] = prefix[i] + frame.rms }
        fun average(from: Int, to: Int) = (prefix[to] - prefix[from]) / (to - from).coerceAtLeast(1)
        var best: Choice? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 40 until audio.size - 5) {
            val frame = audio[i]
            if (frame.timeMs !in range.startMs..range.endMs) continue
            // A breath in a word is not a musical boundary. Word timings are
            // optional; without them the same audio-only test still works.
            if (words.any { frame.timeMs in (it.startMs - 100)..(it.endMs + 100) }) continue
            // Line-only timing does not tell us its end. At least protect the
            // opening of every sung line rather than mistaking it for a pause.
            if (lines.any { it.text.isNotBlank() && it.startTimeMs?.let { start ->
                    frame.timeMs in start..(start + 900)
                } == true }) continue
            val quiet = average(i - 4, i + 5)
            val before = average(i - 40, i - 8)
            if (quiet >= reference * 0.52 || before < reference * 0.65 || quiet >= before * 0.58) continue
            val lyricPause = pauses.any { abs(it - frame.timeMs) <= 450 }
            val depth = 1.0 - quiet / reference
            val drop = 1.0 - quiet / before
            val score = depth + drop + if (lyricPause) 1.5 else 0.0
            if (score > bestScore) {
                bestScore = score
                best = Choice(frame.timeMs, if (lyricPause) Reason.LYRIC_PAUSE else Reason.QUIET_BOUNDARY)
            }
        }
        best?.let { return it }

        // Positive energy changes approximate attacks. Only use their timing
        // when several successive intervals agree, rather than guessing a beat.
        val attacks = mutableListOf<Long>()
        for (i in 3 until audio.size - 2) {
            val rise = audio[i].rms - audio[i - 3].rms
            if (rise < reference * 0.2 || audio[i].rms < reference * 0.6) continue
            if (audio[i].rms < audio[i + 1].rms || audio[i].rms < audio[i - 1].rms) continue
            if (attacks.isEmpty() || audio[i].timeMs - attacks.last() >= 250) attacks += audio[i].timeMs
        }
        val intervals = attacks.zipWithNext { a, b -> b - a }.filter { it in 300..1000 }
        if (intervals.size < 6) return fallback
        val median = intervals.sorted()[intervals.size / 2].toDouble()
        val consistent = intervals.count { abs(it - median) < median * 0.15 }
        val deviation = sqrt(intervals.sumOf { (it - median) * (it - median) } / intervals.size)
        if (consistent < intervals.size * 0.8 || deviation > median * 0.2) return fallback
        val beat = attacks.lastOrNull { it in range.startMs..range.endMs && range.endMs - it <= median * 1.5 }
            ?: return fallback
        return Choice(beat, Reason.BEAT)
    }
}
