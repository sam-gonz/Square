package dev.lelonio.square.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DjModeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("square_dj_mode", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(
        DjModeSettings(
            enabled = prefs.getBoolean("enabled", false),
            smart = prefs.getBoolean("smart", true),
            timing = DjTiming.entries.firstOrNull { it.name == prefs.getString("timing", null) }
                ?: DjTiming.BALANCED,
        ),
    )
    val settings = _settings.asStateFlow()

    fun setEnabled(enabled: Boolean) = save(_settings.value.copy(enabled = enabled))

    fun setTiming(timing: DjTiming) = save(_settings.value.copy(timing = timing))

    fun setSmart(smart: Boolean) = save(_settings.value.copy(smart = smart))

    private fun save(settings: DjModeSettings) {
        _settings.value = settings
        prefs.edit().putBoolean("enabled", settings.enabled).putString("timing", settings.timing.name)
            .putBoolean("smart", settings.smart).apply()
    }
}
