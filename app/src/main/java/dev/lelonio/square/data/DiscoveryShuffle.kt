package dev.lelonio.square.data

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Weighted sampling without replacement: every entry appears once, including older music. */
object DiscoveryShuffle {
    data class Signal(val year: Int? = null, val popularRank: Int? = null)

    fun weight(signal: Signal, currentYear: Int): Double {
        val age = signal.year?.takeIf { it in 1900..currentYear + 1 }
            ?.let { (currentYear - it).coerceAtLeast(0) }
        val freshness = age?.let { 0.06 + 8.0 * exp(-it / 4.0) } ?: 1.0
        val popularity = signal.popularRank?.takeIf { it > 0 }?.let { 10.0 / sqrt(it.toDouble()) } ?: 0.0
        return freshness + popularity
    }

    fun order(signals: List<Signal>, currentYear: Int, random: Random = Random.Default): List<Int> =
        signals.indices.map { index ->
            index to -ln(random.nextDouble().coerceAtLeast(1e-12)) / weight(signals[index], currentYear)
        }.sortedBy { it.second }.map { it.first }
}
