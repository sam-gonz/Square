package dev.lelonio.square.playback

import android.os.Bundle
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.lelonio.square.ui.EXTRA_ORIGINAL_ARTIST
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A [ForwardingPlayer] that projects synchronized lyrics in real time onto Android Auto
 * and media controllers while preserving the original artist name for the phone's UI.
 *
 * It also intercepts verse selection from Android Auto's browse tree so tapping a lyric
 * line seeks playback to that line's timestamp without re-buffering or disrupting the queue.
 */
@UnstableApi
class AutoLyricsForwardingPlayer(
    player: Player,
    private val isAutoLyricsEnabled: () -> Boolean,
) : ForwardingPlayer(player) {

    private val customListeners = CopyOnWriteArraySet<Player.Listener>()

    @Volatile
    var currentCarLyric: String? = null
        private set

    override fun addListener(listener: Player.Listener) {
        customListeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        customListeners.remove(listener)
        super.removeListener(listener)
    }

    fun updateLyric(line: String?) {
        if (currentCarLyric == line) return
        currentCarLyric = line
        val meta = getMediaMetadata()
        customListeners.forEach { listener ->
            runCatching { listener.onMediaMetadataChanged(meta) }
        }
    }

    override fun getMediaMetadata(): MediaMetadata {
        val base = super.getMediaMetadata()
        val lyric = currentCarLyric
        if (!isAutoLyricsEnabled() || lyric.isNullOrBlank()) {
            return base
        }
        val originalArtist = base.extras?.getString(EXTRA_ORIGINAL_ARTIST)
            ?: base.artist?.toString().orEmpty()
        val newArtist = if (originalArtist.isNotBlank()) "$originalArtist • $lyric" else lyric
        val extras = (base.extras?.let { Bundle(it) } ?: Bundle()).apply {
            if (!containsKey(EXTRA_ORIGINAL_ARTIST)) {
                putString(EXTRA_ORIGINAL_ARTIST, originalArtist)
            }
        }
        return base.buildUpon()
            .setArtist(newArtist)
            .setSubtitle(lyric)
            .setDescription(lyric)
            .setExtras(extras)
            .build()
    }

    override fun getCurrentMediaItem(): MediaItem? {
        val baseItem = super.getCurrentMediaItem() ?: return null
        val lyric = currentCarLyric
        if (!isAutoLyricsEnabled() || lyric.isNullOrBlank()) {
            return baseItem
        }
        val base = baseItem.mediaMetadata
        val originalArtist = base.extras?.getString(EXTRA_ORIGINAL_ARTIST)
            ?: base.artist?.toString().orEmpty()
        val newArtist = if (originalArtist.isNotBlank()) "$originalArtist • $lyric" else lyric
        val extras = (base.extras?.let { Bundle(it) } ?: Bundle()).apply {
            if (!containsKey(EXTRA_ORIGINAL_ARTIST)) {
                putString(EXTRA_ORIGINAL_ARTIST, originalArtist)
            }
        }
        val newMeta = base.buildUpon()
            .setArtist(newArtist)
            .setSubtitle(lyric)
            .setDescription(lyric)
            .setExtras(extras)
            .build()
        return baseItem.buildUpon()
            .setMediaMetadata(newMeta)
            .build()
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        if (handleLyricSeek(mediaItems)) return
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (handleLyricSeek(mediaItems)) return
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        if (handleLyricSeek(listOf(mediaItem))) return
        super.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        if (handleLyricSeek(listOf(mediaItem))) return
        super.setMediaItem(mediaItem, startPositionMs)
    }

    private fun handleLyricSeek(mediaItems: List<MediaItem>): Boolean {
        val single = mediaItems.singleOrNull() ?: return false
        val id = single.mediaId
        if (id.startsWith(MediaBrowseTree.LYRIC_LINE_PREFIX)) {
            val timestampMs = id.removePrefix(MediaBrowseTree.LYRIC_LINE_PREFIX).substringAfter('/').toLongOrNull()
                ?: id.removePrefix(MediaBrowseTree.LYRIC_LINE_PREFIX).toLongOrNull() ?: 0L
            seekTo(timestampMs)
            play()
            return true
        }
        return false
    }
}
