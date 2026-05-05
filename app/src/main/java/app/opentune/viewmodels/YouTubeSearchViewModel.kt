/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.db.MusicDatabase
import app.opentune.db.entities.ArtistEntity
import app.opentune.db.entities.PlaylistEntity
import app.opentune.db.entities.PlaylistSongMap
import app.opentune.db.entities.SongArtistMap
import app.opentune.db.entities.SongEntity
import app.opentune.innertube.InnertubeApi
import app.opentune.innertube.YtMusicAlbum
import app.opentune.innertube.YtMusicArtist
import app.opentune.innertube.YtMusicTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page
import java.time.LocalDateTime
import javax.inject.Inject

enum class SearchTab { SONGS, ARTISTS, ALBUMS, PLAYLISTS }

@OptIn(FlowPreview::class)
@HiltViewModel
class YouTubeSearchViewModel @Inject constructor(
    private val api: InnertubeApi,
    private val database: MusicDatabase,
) : ViewModel() {

    val query = MutableStateFlow("")
    val selectedTab = MutableStateFlow(SearchTab.SONGS)

    val songResults: StateFlow<List<YtMusicTrack>> get() = _songResults
    val artistResults: StateFlow<List<YtMusicArtist>> get() = _artistResults
    val albumResults: StateFlow<List<YtMusicAlbum>> get() = _albumResults
    val playlistResults: StateFlow<List<YtMusicAlbum>> get() = _playlistResults
    val isLoading: StateFlow<Boolean> get() = _isLoading
    val isLoadingMore: StateFlow<Boolean> get() = _isLoadingMore
    val error: StateFlow<String?> get() = _error

    private val _songResults = MutableStateFlow<List<YtMusicTrack>>(emptyList())
    private val _artistResults = MutableStateFlow<List<YtMusicArtist>>(emptyList())
    private val _albumResults = MutableStateFlow<List<YtMusicAlbum>>(emptyList())
    private val _playlistResults = MutableStateFlow<List<YtMusicAlbum>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    private val _isLoadingMore = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    private var songNextPage: Page? = null
    private var artistNextPage: Page? = null
    private var albumNextPage: Page? = null
    private var playlistNextPage: Page? = null

    init {
        viewModelScope.launch {
            query
                .debounce(400)
                .distinctUntilChanged()
                .filter { it.isNotBlank() }
                .collect { q -> doSearch(q) }
        }
    }

    private fun doSearch(q: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            try {
                val songs = api.search(q)
                _songResults.value = songs.items
                songNextPage = songs.nextPage

                val artists = api.searchArtists(q)
                _artistResults.value = artists.items
                artistNextPage = artists.nextPage

                val albums = api.searchAlbums(q)
                _albumResults.value = albums.items
                albumNextPage = albums.nextPage

                val playlists = api.searchPlaylists(q)
                _playlistResults.value = playlists.items
                playlistNextPage = playlists.nextPage
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadMore() {
        if (_isLoadingMore.value || _isLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingMore.value = true
            try {
                val q = query.value
                when (selectedTab.value) {
                    SearchTab.SONGS -> songNextPage?.let { next ->
                        val page = api.searchMoreSongs(q, next)
                        _songResults.value = _songResults.value + page.items
                        songNextPage = page.nextPage
                    }
                    SearchTab.ARTISTS -> artistNextPage?.let { next ->
                        val page = api.searchMoreArtists(q, next)
                        _artistResults.value = _artistResults.value + page.items
                        artistNextPage = page.nextPage
                    }
                    SearchTab.ALBUMS -> albumNextPage?.let { next ->
                        val page = api.searchMoreAlbums(q, next)
                        _albumResults.value = _albumResults.value + page.items
                        albumNextPage = page.nextPage
                    }
                    SearchTab.PLAYLISTS -> playlistNextPage?.let { next ->
                        val page = api.searchMorePlaylists(q, next)
                        _playlistResults.value = _playlistResults.value + page.items
                        playlistNextPage = page.nextPage
                    }
                }
            } catch (e: Exception) {
                // silently ignore pagination errors
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun loadPlaylistSongs(playlistId: String, originalUrl: String, onResult: (List<YtMusicTrack>, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val songs = api.getPlaylistSongs(playlistId, originalUrl)
                if (songs.isNotEmpty()) {
                    val existing = database.playlist(playlistId).first()
                    if (existing?.playlist?.bookmarkedAt != null) {
                        syncPlaylistSongs(playlistId, songs)
                    }
                }
                withContext(Dispatchers.Main) { onResult(songs, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(emptyList(), e.javaClass.simpleName + ": " + e.message?.take(100)) }
            }
        }
    }

    private suspend fun syncPlaylistSongs(playlistId: String, songs: List<YtMusicTrack>) {
        database.clearPlaylist(playlistId)
        songs.forEachIndexed { index, track ->
            database.insert(SongEntity(id = track.videoId, title = track.title, thumbnailUrl = track.thumbnailUrl, localPath = null))
            if (track.artistName.isNotBlank()) {
                val artistId = "YTA_${track.artistName.hashCode()}"
                database.insert(ArtistEntity(id = artistId, name = track.artistName))
                database.insert(SongArtistMap(songId = track.videoId, artistId = artistId, position = 0))
            }
            database.insert(PlaylistSongMap(playlistId = playlistId, songId = track.videoId, position = index))
        }
    }

    fun bookmarkStateFlow(playlistId: String): Flow<Boolean> =
        database.playlist(playlistId).map { it?.playlist?.bookmarkedAt != null }

    fun toggleBookmark(album: YtMusicAlbum, songs: List<YtMusicTrack> = emptyList()) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = database.playlist(album.playlistId).first()?.playlist
            if (existing == null) {
                // Insert playlist directly on IO dispatcher (Room allows background-thread DAO calls)
                database.insert(
                    PlaylistEntity(
                        id = album.playlistId,
                        name = album.title,
                        thumbnailUrl = album.thumbnailUrl,
                        bookmarkedAt = LocalDateTime.now(),
                    )
                )
                songs.forEachIndexed { index, track ->
                    database.insert(SongEntity(id = track.videoId, title = track.title, thumbnailUrl = track.thumbnailUrl, localPath = null))
                    if (track.artistName.isNotBlank()) {
                        val artistId = "YTA_${track.artistName.hashCode()}"
                        database.insert(ArtistEntity(id = artistId, name = track.artistName))
                        database.insert(SongArtistMap(songId = track.videoId, artistId = artistId, position = 0))
                    }
                    database.insert(PlaylistSongMap(playlistId = album.playlistId, songId = track.videoId, position = index))
                }
            } else {
                database.update(existing.toggleLike())
            }
        }
    }

}
