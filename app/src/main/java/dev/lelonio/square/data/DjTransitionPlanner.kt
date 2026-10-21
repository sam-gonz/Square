package dev.lelonio.square.data

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

data class DjWindow(val startMs: Long, val endMs: Long)

/** One committed transition per play. Analysis can never move it after publication. */
class DjTransitionPlanner {
    private var settings: DjModeSettings? = null
    private var duration = 0L
    private var fade = 0
    private var armed = false
    private var committed: Long? = null

    fun reset() {
        settings = null
        duration = 0
        fade = 0
        armed = false
        committed = null
    }

    fun range(durationMs: Long, crossfadeMs: Int, positionMs: Long, settings: DjModeSettings): DjWindow? {
        if (this.settings != settings || duration != durationMs || fade != crossfadeMs) {
            reset()
            this.settings = settings
            duration = durationMs
            fade = crossfadeMs
        }
        if (!settings.enabled || durationMs < MIN_TRACK_MS || crossfadeMs <= 0) return null
        val end = minOf(durationMs * settings.timing.maxPercent / 100, durationMs - crossfadeMs)
        val start = minOf(durationMs * settings.timing.minPercent / 100, end)
        if (end <= 0) return null
        if (positionMs < start) armed = true
        return DjWindow(start, end).takeIf { armed }
    }

    /** Pending work has until before the window starts; after that, commit its deadline. */
    fun window(
        durationMs: Long, crossfadeMs: Int, positionMs: Long, settings: DjModeSettings,
        suggestionMs: Long? = null, analysisPending: Boolean = false,
    ): DjWindow? {
        val range = range(durationMs, crossfadeMs, positionMs, settings) ?: return null
        committed?.let { return DjWindow(it, it) }
        if (range.startMs == range.endMs) {
            committed = range.endMs
        } else if (suggestionMs != null && suggestionMs in range.startMs..range.endMs &&
            suggestionMs - positionMs >= PREPARE_LEAD_MS) {
            committed = suggestionMs
        } else if (!analysisPending || positionMs >= range.startMs - PREPARE_LEAD_MS) {
            committed = range.endMs
        }
        return committed?.let { DjWindow(it, it) }
    }

    fun transitionAt(durationMs: Long, crossfadeMs: Int, positionMs: Long, settings: DjModeSettings): Long? =
        window(durationMs, crossfadeMs, positionMs, settings)?.startMs

    companion object {
        const val MIN_TRACK_MS = 90_000L
        const val PREPARE_LEAD_MS = 8_000L
    }
}
