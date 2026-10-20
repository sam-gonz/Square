package dev.lelonio.square.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** State published by the playback scheduler, shared with the player without a second UI timer. */
object DjSession {
    data class Zone(val mediaId: String, val startMs: Long, val endMs: Long) {
        fun fractionFor(currentId: String?, durationMs: Long): ClosedFloatingPointRange<Float>? {
            if (mediaId != currentId || durationMs <= 0) return null
            val start = (startMs.toFloat() / durationMs).coerceIn(0f, 1f)
            return start..(endMs.toFloat() / durationMs).coerceIn(start, 1f)
        }
    }

    data class Undo(val mediaId: String, val index: Int, val positionMs: Long)

    private val _zone = MutableStateFlow<Zone?>(null)
    val zone = _zone.asStateFlow()
    private val _kept = MutableStateFlow<String?>(null)
    val kept = _kept.asStateFlow()
    private val _undo = MutableStateFlow<Undo?>(null)
    val undo = _undo.asStateFlow()
    var undoHandler: ((Undo) -> Unit)? = null

    fun publishZone(zone: Zone?) { _zone.value = zone }
    fun publishUndo(undo: Undo?) { _undo.value = undo }
    fun setKept(mediaId: String?) { _kept.value = mediaId }
    fun toggleKept(mediaId: String?) {
        if (mediaId != null) _kept.value = mediaId.takeUnless { it == _kept.value }
    }
    fun undo() {
        val item = _undo.value ?: return
        val handler = undoHandler ?: return
        _undo.value = null
        _kept.value = item.mediaId
        handler(item)
    }
}
