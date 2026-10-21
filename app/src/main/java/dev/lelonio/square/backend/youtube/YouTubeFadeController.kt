package dev.lelonio.square.backend.youtube

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.lelonio.square.data.CrossfadeStore
import dev.lelonio.square.data.DjSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dissolves one YouTube song into the next, the way the Spotify engine does.
 *
 * The main player owns the queue and moves to the next song as the dissolve
 * begins. The song it leaves carries on in a second player, the tail, which has
 * been playing the same song held back in the [PcmMixer]; the mixer swaps the
 * two at the very sample, so the handover cannot be heard, and the two songs
 * are then summed into one speaker on an equal-power curve.
 */
@UnstableApi
class YouTubeFadeController(
    private val player: ExoPlayer,
    private val crossfadeStore: CrossfadeStore?,
    private val mixer: PcmMixer,
    private val mainSlot: MixerSlot,
    private val tailSlot: MixerSlot,
    private val djModeStore: dev.lelonio.square.data.DjModeStore? = null,
    private val analyze: (suspend (MediaItem, dev.lelonio.square.data.DjWindow) -> Long?)? = null,
    private val createOutgoing: () -> ExoPlayer,
) : Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var transition: Job? = null
    private var outgoing: ExoPlayer? = null
    private var preparedItem: MediaItem? = null
    private var changing = false
    private var internalChange = false
    private val djPlanner = dev.lelonio.square.data.DjTransitionPlanner()
    private var analysisJob: Job? = null
    private var analysisKey: String? = null
    private var analysisGeneration = 0
    private var analysisPoint: Long? = null
    private var analysisDone = false
    private var pendingUndo: DjSession.Undo? = null
    private var undoExpiry: Job? = null
    private val undoHandler: (DjSession.Undo) -> Unit = { undo ->
        val index = undo.index.takeIf {
            it in 0 until player.mediaItemCount && player.getMediaItemAt(it).mediaId == undo.mediaId
        } ?: (0 until player.mediaItemCount).firstOrNull {
            player.getMediaItemAt(it).mediaId == undo.mediaId
        }
        if (index != null) {
            cancelOverlap()
            undoExpiry?.cancel()
            player.seekTo(index, undo.positionMs)
            player.prepare()
            player.play()
        }
    }

    /** Which transition owns the state; bumped by every new one and every cancel. */
    private var generations = 0

    init {
        player.addListener(this)
        DjSession.undoHandler = undoHandler
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        // Buffering the incoming song must not interrupt the outgoing audio.
        syncTailPlayback()
        if (!isPlaying) {
            ticker?.cancel()
            ticker = null
        }
        if (isPlaying && ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive) {
                    checkEnd()
                    delay(40)
                }
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (!internalChange && (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED)) {
            cancelOverlap()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = syncTailPlayback()

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = syncTailPlayback()

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        outgoing?.playbackParameters = playbackParameters
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        djPlanner.reset()
        cancelAnalysis()
        DjSession.publishZone(null)
        if (DjSession.kept.value != mediaItem?.mediaId) DjSession.setKept(null)
        undoExpiry?.cancel()
        val undo = pendingUndo
        pendingUndo = null
        DjSession.publishUndo(undo)
        if (undo != null) undoExpiry = scope.launch {
            delay(15_000)
            if (DjSession.undo.value == undo) DjSession.publishUndo(null)
        }
        if (!internalChange) cancelOverlap()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (!internalChange && reason == Player.DISCONTINUITY_REASON_SEEK) cancelOverlap()
    }

    private fun syncTailPlayback() {
        outgoing?.playWhenReady = changing && player.playWhenReady && player.playbackSuppressionReason == 0
    }

    private fun prepareOutgoing(): ExoPlayer? {
        val item = player.currentMediaItem ?: return null
        val tail = outgoing ?: createOutgoing().also { instance ->
            outgoing = instance
            instance.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.w(TAG, "outgoing stream unavailable", error)
                    cancelOverlap()
                }
            })
        }
        if (preparedItem != item) {
            preparedItem = item
            tail.pause()
            tail.repeatMode = Player.REPEAT_MODE_OFF
            tail.playbackParameters = player.playbackParameters
            tail.setMediaItem(item, player.currentPosition)
            tail.prepare()
        }
        return tail
    }

    private fun cancelAnalysis() {
        analysisGeneration++
        analysisJob?.cancel()
        analysisJob = null
        analysisKey = null
        analysisPoint = null
        analysisDone = false
    }

    private fun requestAnalysis(range: dev.lelonio.square.data.DjWindow) {
        val item = player.currentMediaItem ?: return
        val key = "${item.mediaId}:${range.startMs}:${range.endMs}"
        if (key == analysisKey) return
        cancelAnalysis()
        analysisKey = key
        val generation = analysisGeneration
        if (analyze == null || range.startMs == range.endMs) {
            analysisDone = true
            return
        }
        analysisJob = scope.launch {
            try {
                analysisPoint = withTimeoutOrNull(16_000) { analyze.invoke(item, range) }
                if (analysisPoint == null) android.util.Log.i(TAG, "DJ analysis unavailable; deadline ${range.endMs}ms")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w(TAG, "DJ analysis unavailable; using deadline", error)
            } finally {
                if (generation == analysisGeneration) analysisDone = true
            }
        }
    }

    private fun checkEnd() {
        if (changing || !player.isPlaying) return
        // No crossfade while a sleep timer waits for the end of this song: the
        // fade moved to the next song seconds early, the timer took that for
        // a skip, and the music never paused. The song now plays to its end.
        if (dev.lelonio.square.playback.SleepTimer.atTrackEnd.value) {
            if (analysisKey != null) cancelAnalysis()
            djPlanner.reset()
            DjSession.publishZone(null)
            return
        }
        val fadeMs = crossfadeStore?.durationMs() ?: 0
        if (fadeMs <= 0) {
            if (analysisKey != null) cancelAnalysis()
            djPlanner.reset()
            DjSession.publishZone(null)
            if (preparedItem != null) cancelOverlap()
            return
        }
        val duration = player.duration
        val position = player.currentPosition
        if (duration <= fadeMs || position >= duration || player.isCurrentMediaItemLive) {
            DjSession.publishZone(null)
            return
        }
        val mediaId = player.currentMediaItem?.mediaId
        val kept = mediaId != null && DjSession.kept.value == mediaId
        val next = if (player.repeatMode == Player.REPEAT_MODE_ONE) player.currentMediaItemIndex
            else player.nextMediaItemIndex
        if (next < 0) {
            cancelAnalysis()
            DjSession.publishZone(null)
            return
        }
        val settings = djModeStore?.settings?.value ?: dev.lelonio.square.data.DjModeSettings()
        val range = if (player.repeatMode != Player.REPEAT_MODE_ONE && !kept) {
            djPlanner.range(duration, fadeMs, position, settings)
        } else null
        val djWindow = if (range != null) {
            requestAnalysis(range)
            djPlanner.window(duration, fadeMs, position, settings, analysisPoint, analysisPending = !analysisDone)
        } else {
            if (analysisKey != null) cancelAnalysis()
            djPlanner.reset()
            null
        }
        if (djWindow != null && !analysisDone) {
            // Once its displayed point is committed, late analysis cannot move it.
            analysisJob?.cancel()
            analysisDone = true
        }
        val untilTransition = (djWindow?.startMs ?: (duration - fadeMs)) - position
        DjSession.publishZone(if (djWindow != null && mediaId != null) {
            DjSession.Zone(mediaId, djWindow.startMs, djWindow.endMs)
        } else null)
        // Resolve the outgoing stream early; begin its sample alignment before
        // the published DJ point, then hold the actual queue change until that point.
        if (untilTransition <= 8_000) prepareOutgoing()
        val scheduledAt = djWindow?.startMs
        val due = untilTransition <= 0
        val canPrepareEarly = scheduledAt != null && untilTransition in 1..4_000 &&
            outgoing?.playbackState == Player.STATE_READY && mainSlot.mixed
        if (due || canPrepareEarly) {
            val undo = if (djWindow != null && mediaId != null && position < duration - fadeMs) {
                DjSession.Undo(mediaId, player.currentMediaItemIndex, scheduledAt ?: position)
            } else null
            changeWith(fadeMs, automatic = true, scheduledAtMs = scheduledAt) {
                val actualNext = if (player.repeatMode == Player.REPEAT_MODE_ONE) player.currentMediaItemIndex
                    else player.nextMediaItemIndex
                if (actualNext >= 0) {
                    if (djWindow != null) android.util.Log.i(TAG,
                        "DJ switch scheduled=${djWindow.startMs}ms actual=${player.currentPosition}ms")
                    pendingUndo = undo
                    player.seekToDefaultPosition(actualNext)
                }
            }
        }
    }

    fun changeWith(fadeMs: Int, automatic: Boolean = false, scheduledAtMs: Long? = null, change: () -> Unit) {
        if (changing) cancelOverlap()
        if (fadeMs <= 0 || !player.isPlaying) {
            cancelOverlap()
            change()
            return
        }
        val tail = outgoing?.takeIf {
            preparedItem == player.currentMediaItem && it.playbackState == Player.STATE_READY
        }
        // A skip far from the end has no tail buffered, and building one would delay
        // the skip by a whole stream resolution: what the mixer already holds of
        // the song is enough for the listener's short fade.
        if (tail == null || !mainSlot.mixed) {
            if (!crossWith(fadeMs, change)) dip(fadeMs, change)
            return
        }
        if (scheduledAtMs != null && player.currentPosition >= scheduledAtMs) {
            if (!crossWith(fadeMs, change)) dip(fadeMs, change)
            return
        }
        changing = true
        val generation = ++generations
        val scheduledSettings = djModeStore?.settings?.value
        transition = scope.launch {
            try {
                tailSlot.fade = 0f
                tailSlot.held = true
                tail.playbackParameters = player.playbackParameters
                // A little ahead of what is heard, so that by the time the tail has
                // piled up its first frames the main player has reached them.
                tail.seekTo(player.currentPosition + TAIL_LEAD_MS)
                tail.play()
                val handed = withTimeoutOrNull(HANDOVER_WAIT_MS) {
                    suspendCancellableCoroutine { continuation ->
                        continuation.invokeOnCancellation { mixer.cancelHandOver() }
                        mixer.handOver(mainSlot, tailSlot) { ok ->
                            scope.launch { if (continuation.isActive) continuation.resume(ok) }
                        }
                    }
                } ?: false
                if (!handed) {
                    // An early alignment failure must not move the published transition.
                    // The ticker can retry while time remains, or use the buffered path at the point.
                    if (scheduledAtMs != null && player.currentPosition < scheduledAtMs) return@launch
                    android.util.Log.w(TAG, "no handover, crossing what is buffered instead")
                    retire(tail)
                    val short = fadeMs.coerceAtMost(MAX_BUFFERED_FADE_MS)
                    if (mixer.detach(mainSlot, short)) {
                        mainSlot.fade = 0f
                        internalChange = true
                        try { change() } finally { internalChange = false }
                        rise(short)
                    } else {
                        dipNow(fadeMs, change)
                    }
                    return@launch
                }
                // The aligned tail still carries this song while its main clock advances.
                // Pause/resume follows syncTailPlayback, and a seek cancels this job.
                if (scheduledAtMs != null) {
                    while (player.currentPosition < scheduledAtMs) {
                        if (djModeStore?.settings?.value != scheduledSettings ||
                            crossfadeStore?.durationMs() != fadeMs ||
                            dev.lelonio.square.playback.SleepTimer.atTrackEnd.value ||
                            DjSession.kept.value == player.currentMediaItem?.mediaId ||
                            player.repeatMode == Player.REPEAT_MODE_ONE) return@launch
                        delay(16)
                    }
                }
                // The mixer has silenced the main player and opened the tail.
                internalChange = true
                try { change() } finally { internalChange = false }
                android.util.Log.i(TAG, "overlap started: ${tail.currentMediaItem?.mediaId} -> ${player.currentMediaItem?.mediaId}, ${fadeMs}ms")
                var elapsed = 0L
                var previous = player.currentPosition
                while (elapsed < fadeMs) {
                    delay(20)
                    if (!player.playWhenReady || player.playbackSuppressionReason != 0) {
                        tail.pause()
                        previous = player.currentPosition
                        continue
                    }
                    tail.play()
                    val position = player.currentPosition
                    if (player.isPlaying) elapsed += (position - previous).coerceAtLeast(0)
                    previous = position
                    val x = (elapsed.toFloat() / fadeMs).coerceIn(0f, 1f)
                    mainSlot.fade = sin(x * PI.toFloat() / 2)
                    tailSlot.fade = cos(x * PI.toFloat() / 2)
                }
                android.util.Log.i(TAG, "overlap completed")
            } finally {
                // A cancelled job reaches here only after whatever cancelled it has moved
                // on, possibly into a new overlap: that one owns the state now.
                if (generation == generations) {
                    transition = null
                    changing = false
                    retire(tail)
                    setMainFade(1f)
                }
            }
        }
    }

    /**
     * The engine's change of song: the outgoing one fades out of what the mixer
     * holds of it while the player moves on at once, and the incoming one rises
     * on its own clock, from when it is actually heard.
     */
    private fun crossWith(fadeMs: Int, change: () -> Unit): Boolean {
        if (!mainSlot.mixed || !mixer.detach(mainSlot, fadeMs)) return false
        changing = true
        val generation = ++generations
        mainSlot.fade = 0f
        internalChange = true
        try { change() } finally { internalChange = false }
        transition = scope.launch {
            try {
                rise(fadeMs)
            } finally {
                if (generation == generations) {
                    transition = null
                    changing = false
                    setMainFade(1f)
                }
            }
        }
        return true
    }

    /** Brings the main player up over [fadeMs] of its own playback. */
    private suspend fun rise(fadeMs: Int) {
        var elapsed = 0L
        var previous = player.currentPosition
        val deadline = System.currentTimeMillis() + RISE_GIVE_UP_MS
        while (elapsed < fadeMs && System.currentTimeMillis() < deadline) {
            delay(16)
            val position = player.currentPosition
            if (player.isPlaying) elapsed += (position - previous).coerceIn(0, 100)
            previous = position
            mainSlot.fade = sin((elapsed.toFloat() / fadeMs).coerceIn(0f, 1f) * PI.toFloat() / 2)
        }
    }

    /** Out, change, in, on the main player alone: half of [fadeMs] each way. */
    private fun dip(fadeMs: Int, change: () -> Unit) {
        changing = true
        val generation = ++generations
        transition = scope.launch {
            try {
                dipNow(fadeMs, change)
            } finally {
                if (generation == generations) {
                    transition = null
                    changing = false
                    setMainFade(1f)
                }
            }
        }
    }

    private suspend fun dipNow(fadeMs: Int, change: () -> Unit) {
        val half = (fadeMs / 2).coerceIn(MIN_HALF_MS, MAX_HALF_MS)
        ramp(half) { x -> setMainFade(cos(x * PI.toFloat() / 2)) }
        setMainFade(0f)
        internalChange = true
        try { change() } finally { internalChange = false }
        ramp(half) { x -> setMainFade(sin(x * PI.toFloat() / 2)) }
    }

    /** Through the mixer when the main player is in it, on the player itself otherwise. */
    private fun setMainFade(value: Float) {
        if (mainSlot.mixed) {
            mainSlot.fade = value
            if (player.volume != 1f) player.volume = 1f
        } else {
            mainSlot.fade = 1f
            player.volume = value
        }
    }

    private suspend fun ramp(durationMs: Int, step: (Float) -> Unit) {
        var elapsed = 0
        while (elapsed < durationMs) {
            delay(16)
            elapsed += 16
            step((elapsed.toFloat() / durationMs).coerceIn(0f, 1f))
        }
    }

    /**
     * Silences the tail and holds it where it is.
     *
     * Not stopped: releasing its decoder while the main player is changing song
     * aborts inside MediaCodec (`ubsan: sub-overflow` in `updateMediametrics`) on
     * this hardware. The decoder goes when the tail is next given a song, seconds
     * before an end, with the main player steady.
     */
    private fun retire(tail: ExoPlayer) {
        tailSlot.fade = 0f
        tailSlot.held = true
        tail.pause()
        preparedItem = null
    }

    private fun cancelOverlap() {
        generations++
        transition?.cancel()
        transition = null
        changing = false
        mixer.cancelHandOver()
        outgoing?.let(::retire)
        setMainFade(1f)
    }

    fun release() {
        cancelAnalysis()
        if (DjSession.undoHandler === undoHandler) {
            DjSession.undoHandler = null
            DjSession.publishZone(null)
            DjSession.publishUndo(null)
            DjSession.setKept(null)
        }
        player.removeListener(this)
        scope.cancel()
        outgoing?.release()
        outgoing = null
    }

    /** After the main player is gone too: it writes into the mixer until then. */
    fun releaseMixer() = mixer.release()
}

private const val TAG = "SquareYTFade"

/** Shorter than this and a dip is a click with a delay in front of it. */
private const val MIN_HALF_MS = 120

/** A skip is late by this much at most: it is the listener's, not the song's end. */
private const val MAX_HALF_MS = 400

/** The most of a song the mixer holds ahead, with a margin: see PcmMixer. */
private const val MAX_BUFFERED_FADE_MS = 1_500

/** A song that will not start is not waited on with the volume down. */
private const val RISE_GIVE_UP_MS = 20_000L

/** How far ahead of the heard position the tail starts piling up. */
private const val TAIL_LEAD_MS = 300L

/** Longer than the mixer's own timeout, which is the one meant to fire. */
private const val HANDOVER_WAIT_MS = 4_000L
