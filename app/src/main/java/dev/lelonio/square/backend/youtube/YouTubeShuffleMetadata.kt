package dev.lelonio.square.backend.youtube

import androidx.media3.common.MediaItem
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.ArtistPage
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.DiscoveryShuffle
import dev.lelonio.square.ui.EXTRA_ALBUM_URI
import dev.lelonio.square.ui.EXTRA_ARTIST_URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Session cache of actual release years and the artist's featured songs, never inferred from titles. */
internal object YouTubeShuffleMetadata {
    private class Cache<V> : LinkedHashMap<String, V>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > 2048
    }
    private val albumYears = Cache<Int?>()
    private val popular = Cache<Int>()
    private val artists = Cache<Boolean>()

    @Synchronized fun rememberArtist(id: String, page: ArtistPage) {
        artists[id] = true
        page.sections.firstOrNull { section -> section.items.any { it is SongItem } }
            ?.items.orEmpty().filterIsInstance<SongItem>().distinctBy { it.id }
            .forEachIndexed { rank, song -> popular[song.id] = rank + 1 }
        page.sections.flatMap { it.items }.filterIsInstance<AlbumItem>().forEach { album ->
            album.year?.let { albumYears[album.browseId] = it }
        }
    }

    @Synchronized fun knownYear(albumId: String?): Int? = albumId?.let { albumYears[it] }

    @Synchronized fun rememberAlbum(id: String, year: Int?) { albumYears[id] = year }

    @Synchronized private fun signal(uri: String, year: Int?, albumUri: String?): DiscoveryShuffle.Signal {
        val videoId = uri.substringAfterLast(':')
        val albumId = albumUri?.substringAfterLast(':')
        return DiscoveryShuffle.Signal(year ?: albumId?.let { albumYears[it] }, popular[videoId])
    }

    fun signal(item: MediaItem) = signal(item.mediaId, item.mediaMetadata.releaseYear,
        item.mediaMetadata.extras?.getString(EXTRA_ALBUM_URI))

    fun signal(track: CatalogTrack) = signal(track.uri, track.year.toIntOrNull(), track.albumUri)

    fun firstIndex(tracks: List<CatalogTrack>, preferred: Boolean, random: kotlin.random.Random = kotlin.random.Random.Default): Int {
        if (tracks.isEmpty()) return 0
        return if (preferred && tracks.first().uri.startsWith(YouTubeBackend.URI_SCHEME)) {
            DiscoveryShuffle.order(tracks.map(::signal), java.time.Year.now().value, random).first()
        } else tracks.indices.random(random)
    }

    /** Bounded background metadata work. Missing facts leave neutral weights and never block playback. */
    suspend fun enrich(items: List<MediaItem>): Unit = withContext(Dispatchers.IO) {
        val candidates = items.filter { it.mediaId.startsWith(YouTubeBackend.URI_SCHEME) }.take(160)
        val artistIds = candidates.mapNotNull { it.mediaMetadata.extras?.getString(EXTRA_ARTIST_URI) }
            .filter { it.startsWith(YouTubeBackend.ARTIST_PREFIX) }.map { it.removePrefix(YouTubeBackend.ARTIST_PREFIX) }.distinct()
        val albumIds = candidates.mapNotNull { it.mediaMetadata.extras?.getString(EXTRA_ALBUM_URI) }
            .filter { it.startsWith(YouTubeBackend.ALBUM_PREFIX) }.map { it.removePrefix(YouTubeBackend.ALBUM_PREFIX) }.distinct()
        val gate = Semaphore(2)
        withTimeoutOrNull(12_000) {
            coroutineScope {
                val artistJobs = artistIds.map { id -> async {
                    gate.withPermit {
                        if (synchronized(this@YouTubeShuffleMetadata) { artists.containsKey(id) }) return@withPermit
                        try { rememberArtist(id, YouTube.artist(id).getOrThrow()) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* Normal shuffle remains available when metadata fails. */ }
                    }
                } }
                val albumJobs = albumIds.map { id -> async {
                    gate.withPermit {
                        if (synchronized(this@YouTubeShuffleMetadata) { albumYears.containsKey(id) }) return@withPermit
                        try { rememberAlbum(id, YouTube.album(id).getOrThrow().album.year) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { }
                    }
                } }
                (artistJobs + albumJobs).awaitAll()
            }
        }
        Unit
    }
}
