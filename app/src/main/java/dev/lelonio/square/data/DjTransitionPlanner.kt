package dev.lelonio.square.data

import kotlin.random.Random

enum class DjTiming(val minPercent: Int, val maxPercent: Int) {
    BALANCED(65, 85),
    EARLY(50, 70),
    LATE(75, 90),
    THREE_QUARTERS(75, 75),
}

data class DjModeSettings(
    val enabled: Boolean = false,
    val timing: DjTiming = DjTiming.BALANCED,
)

/** One transition point per play of a song, kept through pauses and seeks. */
class DjTransitionPlanner(
    private val choosePercent: (IntRange) -> Int = { Random.nextInt(it.first, it.last + 1) },
) {
    private var settings: DjModeSettings? = null
    private var percent: Int? = null
    private var armed = false

    fun reset() {
        settings = null
        percent = null
        armed = false
    }

    fun transitionAt(
        durationMs: Long,
        crossfadeMs: Int,
        positionMs: Long,
        settings: DjModeSettings,
    ): Long? {
        if (this.settings != settings) {
            reset()
            this.settings = settings
        }
        // Short songs play in full. An unset/live duration cannot be scheduled.
        if (!settings.enabled || durationMs < MIN_TRACK_MS || crossfadeMs <= 0) return null
        val chosen = percent ?: choosePercent(settings.timing.minPercent..settings.timing.maxPercent)
            .also { percent = it }
        val target = minOf(durationMs * chosen / 100, durationMs - crossfadeMs)
        if (target <= 0) return null
        // Enabling the mode or resuming beyond the chosen point must not skip
        // a song immediately. It must first have played before that point.
        if (positionMs < target) armed = true
        return target.takeIf { armed }
    }

    companion object {
        const val MIN_TRACK_MS = 90_000L
    }
}
