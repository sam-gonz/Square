package dev.lelonio.square.backend

import androidx.media3.common.Player
import dev.lelonio.square.data.CatalogPlaylist
import dev.lelonio.square.data.CatalogTrack
import dev.lelonio.square.data.SearchResults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistShuffleTest {

    private fun fakeTrack(id: String, name: String, artist: String = "Test Artist") = CatalogTrack(
        uri = "ytmusic:track:$id",
        name = name,
        artist = artist,
        album = "Album",
        durationMs = 180000L,
    )

    private open class BaseFakeBackend : MusicBackend {
        override val id: BackendId = BackendId.YOUTUBE_MUSIC
        override val authState: StateFlow<BackendAuthState> = MutableStateFlow(BackendAuthState.LoggedOut)
        override val isReady: Boolean = true
        override suspend fun refreshAuth() {}
        override suspend fun logOut() {}
        override suspend fun search(query: String, labels: SearchLabels, offset: Int): SearchResults = SearchResults()
        override suspend fun playlists(): List<CatalogPlaylist> = emptyList()
        override suspend fun tracksOf(uri: String): List<CatalogTrack> = emptyList()
        override fun owns(uri: String): Boolean = true
        override fun createPlayer(host: PlaybackHost): Player = throw UnsupportedOperationException()
    }

    @Test
    fun defaultBackendEmitsDiscographyInOneBatch() = runBlocking {
        val tracks = listOf(fakeTrack("1", "Song 1"), fakeTrack("2", "Song 2"))
        val backend = object : BaseFakeBackend() {
            override suspend fun tracksOf(uri: String): List<CatalogTrack> = tracks
        }

        val emissions = backend.artistShuffleTracks("ytmusic:artist:test").toList()
        assertEquals(1, emissions.size)
        assertEquals(tracks, emissions.first())
    }

    @Test
    fun progressiveShuffleStartsFastAndStreamsBatches() = runBlocking {
        val initialBatch = listOf(fakeTrack("1", "Recent 1"), fakeTrack("2", "Top 1"))
        val secondBatch = listOf(fakeTrack("3", "Album track 1"), fakeTrack("4", "Album track 2"))
        val thirdBatch = listOf(fakeTrack("5", "Older track"))

        val streamBackend = object : BaseFakeBackend() {
            override fun artistShuffleTracks(artistUri: String): Flow<List<CatalogTrack>> = flow {
                emit(initialBatch)
                emit(secondBatch)
                emit(thirdBatch)
            }
        }

        val emissions = streamBackend.artistShuffleTracks("ytmusic:artist:test").toList()
        assertEquals(3, emissions.size)
        assertEquals(initialBatch, emissions[0])
        assertEquals(secondBatch, emissions[1])
        assertEquals(thirdBatch, emissions[2])
    }

    @Test
    fun deduplicationFiltersOutAlreadyEmittedTracks() {
        val seen = mutableSetOf<String>()
        val initial = listOf(fakeTrack("1", "Song 1"), fakeTrack("2", "Song 2"))
            .filter { seen.add(it.uri) }
        val subsequent = listOf(fakeTrack("2", "Song 2 duplicate"), fakeTrack("3", "Song 3"))
            .filter { seen.add(it.uri) }

        assertEquals(2, initial.size)
        assertEquals(1, subsequent.size)
        assertEquals("ytmusic:track:3", subsequent[0].uri)
    }
}
