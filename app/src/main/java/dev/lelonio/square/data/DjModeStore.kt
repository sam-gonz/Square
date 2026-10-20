package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DjModeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("square_dj_mode", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(
        DjModeSettings(
            enabled = prefs.getBoolean("enabled", false),
            timing = DjTiming.entries.firstOrNull { it.name == prefs.getString("timing", null) }
                ?: DjTiming.BALANCED,
        ),
    )
    val settings = _settings.asStateFlow()

    fun setEnabled(enabled: Boolean) = save(_settings.value.copy(enabled = enabled))

    fun setTiming(timing: DjTiming) = save(_settings.value.copy(timing = timing))

    private fun save(settings: DjModeSettings) {
        _settings.value = settings
        prefs.edit().putBoolean("enabled", settings.enabled).putString("timing", settings.timing.name).apply()
    }
}
