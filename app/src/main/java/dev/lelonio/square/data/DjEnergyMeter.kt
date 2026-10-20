package dev.lelonio.square.data

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.math.log10

/** Short/recent/song energy envelopes; measures the PCM already being mixed, without another decoder. */
internal class DjEnergyMeter {
    private val resetGeneration = AtomicInteger()
    private var measuredGeneration = 0
    private var now = 0.0
    private var recent = 0.0
    private var song = 0.0
    private var seconds = 0.0
    @Volatile var easingOff = false
        private set

    // The player requests a reset; only the audio thread mutates the envelopes.
    // This avoids waiting for the player thread from an urgent audio callback.
    fun reset() {
        resetGeneration.incrementAndGet()
        easingOff = false
    }

    fun measure(sumOfSquares: Double, frames: Int, sampleRate: Int) {
        if (frames <= 0 || sampleRate <= 0) return
        val generation = resetGeneration.get()
        if (measuredGeneration != generation) {
            now = 0.0
            recent = 0.0
            song = 0.0
            seconds = 0.0
            measuredGeneration = generation
        }
        val value = sumOfSquares / frames
        val dt = frames.toDouble() / sampleRate
        fun follow(average: Double, span: Double) =
            if (seconds == 0.0) value else average + (value - average) * (1.0 - exp(-dt / span))
        now = follow(now, 0.4)
        recent = follow(recent, 4.0)
        song = follow(song, 30.0)
        seconds += dt
        fun db(square: Double) = 10 * log10(square.coerceAtLeast(1e-9))
        easingOff = resetGeneration.get() == generation && seconds >= 20 && (db(now) < db(song) - 5 || db(now) < db(recent) - 8)
    }
}
