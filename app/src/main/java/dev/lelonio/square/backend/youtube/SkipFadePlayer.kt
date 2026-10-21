package dev.lelonio.square.backend.youtube

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.ForwardingPlayer
import dev.lelonio.square.data.DiscoveryShuffle
import dev.lelonio.square.data.PreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.android.asCoroutineDispatcher

/** Keeps the public queue on the main player while its outgoing audio overlaps the next song. */
@UnstableApi
class SkipFadePlayer(
    player: Player,
    private val fades: YouTubeFadeController,
    private val preferences: PreferencesStore? = null,
    /** Zero while the listener has the setting off; see PreferencesStore. */
    private val fadeMs: () -> Int,
) : ForwardingPlayer(player) {

    private val shuffleScope = CoroutineScope(SupervisorJob() +
        android.os.Handler(player.applicationLooper).asCoroutineDispatcher())
    private var metadataJob: Job? = null
    private var generation = 0
    private var preferred = preferences?.discoveryShuffle?.value ?: true

    init {
        shuffleScope.launch {
            preferences?.discoveryShuffle?.collect { enabled ->
                if (preferred != enabled) {
                    preferred = enabled
                    reweightUpcoming(keepNext = 0)
                    requestMetadata()
                }
            }
        }
    }

    private fun shuffledIndices(indices: List<Int>): List<Int> = if (!preferred) indices.shuffled() else {
        val signals = indices.map { YouTubeShuffleMetadata.signal(getMediaItemAt(it)) }
        DiscoveryShuffle.order(signals, java.time.Year.now().value).map(indices::get)
    }

    private fun reweightUpcoming(keepNext: Int) {
        if (!shuffleModeEnabled || mediaItemCount == 0) return
        val exo = wrappedPlayer as? androidx.media3.exoplayer.ExoPlayer ?: return
        val timeline = currentTimeline
        val order = mutableListOf<Int>()
        var at = timeline.getFirstWindowIndex(true)
        while (at >= 0 && order.size < mediaItemCount) {
            order += at
            at = timeline.getNextWindowIndex(at, Player.REPEAT_MODE_OFF, true)
        }
        val current = order.indexOf(currentMediaItemIndex)
        val changeable = order.drop((current + 1 + keepNext).coerceAtMost(order.size))
            .filterNot { isQueued(getMediaItemAt(it)) }
        val ranked = shuffledIndices(changeable).iterator()
        val slots = changeable.toSet()
        val result = order.map { if (it in slots) ranked.next() else it }
        exo.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(result.toIntArray(), System.nanoTime()))
    }

    private fun requestMetadata() {
        metadataJob?.cancel()
        val request = ++generation
        if (!preferred || !shuffleModeEnabled || dev.lelonio.square.playback.OfflineMode.active.value) return
        val snapshot = (0 until mediaItemCount).map(::getMediaItemAt)
        val inPlaybackOrder = mutableListOf<MediaItem>()
        val timeline = currentTimeline
        var index = currentMediaItemIndex
        while (index in snapshot.indices && inPlaybackOrder.size < snapshot.size) {
            inPlaybackOrder += snapshot[index]
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_ALL, true)
        }
        metadataJob = shuffleScope.launch {
            YouTubeShuffleMetadata.enrich(inPlaybackOrder)
            if (request == generation && snapshot.map { it.mediaId } ==
                (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }) {
                // Keep the next two tracks stable once shown while enriching the rest in the background.
                reweightUpcoming(keepNext = 2)
            }
        }
    }

    /** Applies the queue panel's desired traversal without changing item indices. */
    fun setQueueOrder(order: List<Int>) {
        ++generation
        metadataJob?.cancel()
        val exo = wrappedPlayer as? androidx.media3.exoplayer.ExoPlayer ?: return
        if (order.size != mediaItemCount || order.toSet() != (0 until order.size).toSet()) return
        exo.setShuffleOrder(
            androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(
                order.toIntArray(),
                System.nanoTime(),
            ),
        )
    }

    private fun changing(change: () -> Unit) = fades.changeWith(fadeMs(), change = change)

    override fun release() {
        shuffleScope.cancel()
        fades.release()
        super.release()
        fades.releaseMixer()
    }

    // "Play next" lands after the song playing and after whatever was queued
    // before it, as on the Spotify side (PlayQueue.insertNext). ExoPlayer on its
    // own appends, which put a queued song behind the rest of the playlist or
    // the radio: queued, and never reached.

    override fun addMediaItem(mediaItem: MediaItem) = addMediaItems(mutableListOf(mediaItem))

    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
        val queued = mediaItems.isNotEmpty() && mediaItems.all(::isQueued)
        val at = if (queued) queueEnd() else mediaItemCount
        super.addMediaItems(at, mediaItems)
        if (shuffleModeEnabled && mediaItems.isNotEmpty()) {
            if (queued) {
                playNextInShuffle(at, mediaItems.size)
            } else {
                appendInShuffle(at, mediaItems.size)
            }
            requestMetadata()
        }
    }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) = addMediaItems(index, mutableListOf(mediaItem))

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        // The controller asks for the end; the queue knows better where that is.
        val queued = mediaItems.isNotEmpty() && mediaItems.all(::isQueued)
        val at = if (queued) queueEnd() else index
        super.addMediaItems(at, mediaItems)
        if (shuffleModeEnabled && mediaItems.isNotEmpty()) {
            if (queued) {
                playNextInShuffle(at, mediaItems.size)
            } else {
                appendInShuffle(at, mediaItems.size)
            }
            requestMetadata()
        }
    }

    /**
     * When songs are appended to the queue with shuffle on (like background
     * discography streaming or adding tracks), ExoPlayer's default
     * cloneAndInsert inserts them at random positions across the entire shuffle
     * order — including before the song currently playing, which would mark
     * them as "played" and prevent them from ever being heard.
     *
     * We rewrite the shuffle order so the pre-existing upcoming sequence remains
     * completely intact and stable, while the newly added tracks are appended
     * (shuffled amongst themselves) at the very end of the upcoming queue.
     */
    private fun appendInShuffle(at: Int, count: Int) {
        val exo = wrappedPlayer as? androidx.media3.exoplayer.ExoPlayer ?: return
        if (!shuffleModeEnabled || count <= 0) return
        val timeline = currentTimeline
        if (timeline.isEmpty) return
        val added = (at until at + count).toSet()
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(true)
        while (index != androidx.media3.common.C.INDEX_UNSET && order.size < timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        order.removeAll(added)
        val newTracksShuffled = shuffledIndices((at until at + count).toList())
        order.addAll(newTracksShuffled)
        exo.setShuffleOrder(
            androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()),
        )
    }

    /**
     * Shuffled, the songs just queued are played next too.
     *
     * Placing them after the song playing in the list is not enough with
     * shuffle on: ExoPlayer plays its own shuffled order, and gave the new
     * songs a random place in it, so "play next" played a random song next.
     * The order is rewritten with them right after the song playing and after
     * anything queued before them, and everything else as it was.
     */
    private fun playNextInShuffle(at: Int, count: Int) {
        val exo = wrappedPlayer as? androidx.media3.exoplayer.ExoPlayer ?: return
        if (!shuffleModeEnabled || count <= 0) return
        val timeline = currentTimeline
        if (timeline.isEmpty) return
        val added = (at until at + count).toSet()
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(true)
        while (index != androidx.media3.common.C.INDEX_UNSET && order.size < timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        order.removeAll(added)
        var place = order.indexOf(currentMediaItemIndex) + 1
        while (place in order.indices && isQueued(getMediaItemAt(order[place]))) place++
        order.addAll(place.coerceIn(0, order.size), (at until at + count).toList())
        exo.setShuffleOrder(
            androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()),
        )
    }

    private fun isQueued(item: MediaItem) =
        item.mediaMetadata.extras?.getBoolean(dev.lelonio.square.ui.EXTRA_PLAY_NEXT) == true

    /** Just past the run of queued songs that follows the one playing. */
    private fun queueEnd(): Int {
        if (mediaItemCount == 0) return 0
        var at = currentMediaItemIndex + 1
        while (at < mediaItemCount && isQueued(getMediaItemAt(at))) at++
        return at
    }

    override fun seekToNext() = changing { super.seekToNext() }

    override fun seekToNextMediaItem() = changing { super.seekToNextMediaItem() }

    override fun seekToPrevious() = changing { super.seekToPrevious() }

    override fun seekToPreviousMediaItem() = changing { super.seekToPreviousMediaItem() }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex == currentMediaItemIndex) {
            super.seekTo(mediaItemIndex, positionMs)
        } else {
            changing { super.seekTo(mediaItemIndex, positionMs) }
        }
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (mediaItemIndex == currentMediaItemIndex) {
            super.seekToDefaultPosition(mediaItemIndex)
        } else {
            changing { super.seekToDefaultPosition(mediaItemIndex) }
        }
    }

    override fun setMediaItem(mediaItem: MediaItem) = changing { super.setMediaItem(mediaItem) }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) =
        changing { super.setMediaItem(mediaItem, startPositionMs) }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) =
        changing { super.setMediaItem(mediaItem, resetPosition) }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        val exo = wrappedPlayer as? androidx.media3.exoplayer.ExoPlayer
        if (exo != null && shuffleModeEnabled && mediaItemCount > 0) {
            val current = currentMediaItemIndex.coerceIn(0, mediaItemCount - 1)
            val rest = shuffledIndices((0 until mediaItemCount).filter { it != current })
            val newOrder = listOf(current) + rest
            exo.setShuffleOrder(
                androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(
                    newOrder.toIntArray(),
                    System.nanoTime(),
                ),
            )
        }
        super.setShuffleModeEnabled(shuffleModeEnabled)
        requestMetadata()
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) =
        setMediaItems(mediaItems, startIndex = 0, startPositionMs = 0L)

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) =
        setMediaItems(mediaItems, startIndex = if (resetPosition) 0 else currentMediaItemIndex, startPositionMs = 0L)

    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) = changing {
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
        val exo = wrappedPlayer as? androidx.media3.exoplayer.ExoPlayer
        if (exo != null && shuffleModeEnabled && mediaItems.isNotEmpty()) {
            val current = startIndex.coerceIn(0, mediaItems.size - 1)
            val rest = shuffledIndices((0 until mediaItems.size).filter { it != current })
            val newOrder = listOf(current) + rest
            exo.setShuffleOrder(
                androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(
                    newOrder.toIntArray(),
                    System.nanoTime(),
                ),
            )
        }
        requestMetadata()
    }
}
