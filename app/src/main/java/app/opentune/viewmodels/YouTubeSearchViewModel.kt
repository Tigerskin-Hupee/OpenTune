/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.innertube.InnertubeApi
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

@OptIn(FlowPreview::class)
@HiltViewModel
class YouTubeSearchViewModel @Inject constructor(
    private val api: InnertubeApi,
) : ViewModel() {

    val query = MutableStateFlow("")
    val results: StateFlow<List<YtMusicTrack>> get() = _results
    val isLoading: StateFlow<Boolean> get() = _isLoading
    val error: StateFlow<String?> get() = _error

    private val _results = MutableStateFlow<List<YtMusicTrack>>(emptyList())
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
                _results.value = api.search(q)
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }
}
