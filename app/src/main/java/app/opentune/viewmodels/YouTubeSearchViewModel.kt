/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.innertube.InnertubeApi
import app.opentune.innertube.YtMusicAlbum
import app.opentune.innertube.YtMusicArtist
import app.opentune.innertube.YtMusicTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchTab { SONGS, ARTISTS, ALBUMS }

@OptIn(FlowPreview::class)
@HiltViewModel
class YouTubeSearchViewModel @Inject constructor(
    private val api: InnertubeApi,
) : ViewModel() {

    val query = MutableStateFlow("")
    val selectedTab = MutableStateFlow(SearchTab.SONGS)

    val songResults: StateFlow<List<YtMusicTrack>> get() = _songResults
    val artistResults: StateFlow<List<YtMusicArtist>> get() = _artistResults
    val albumResults: StateFlow<List<YtMusicAlbum>> get() = _albumResults
    val isLoading: StateFlow<Boolean> get() = _isLoading
    val error: StateFlow<String?> get() = _error

    private val _songResults = MutableStateFlow<List<YtMusicTrack>>(emptyList())
    private val _artistResults = MutableStateFlow<List<YtMusicArtist>>(emptyList())
    private val _albumResults = MutableStateFlow<List<YtMusicAlbum>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

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
                _songResults.value = api.search(q)
                _artistResults.value = api.searchArtists(q)
                _albumResults.value = api.searchAlbums(q)
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadPlaylistSongs(playlistUrl: String, onResult: (List<YtMusicTrack>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val songs = api.getPlaylistSongs(playlistUrl)
            onResult(songs)
        }
    }
}
