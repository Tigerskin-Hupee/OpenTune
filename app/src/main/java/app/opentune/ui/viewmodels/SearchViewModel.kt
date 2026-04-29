package app.opentune.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.db.MusicRepository
import app.opentune.db.entities.SearchHistory
import app.opentune.innertube.InnertubeApi
import app.opentune.innertube.models.MusicItem
import app.opentune.innertube.models.toSongEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val playerController: PlayerController,
    private val innertubeApi: InnertubeApi,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val searchHistory: StateFlow<List<SearchHistory>> = repository.getSearchHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchResults = MutableStateFlow<List<MusicItem>>(emptyList())
    val searchResults: StateFlow<List<MusicItem>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    init {
        viewModelScope.launch {
            _query
                .debounce(400)
                .collectLatest { q ->
                    if (q.isBlank()) { _searchResults.value = emptyList(); return@collectLatest }
                    _searching.value = true
                    innertubeApi.search(q)
                        .onSuccess { _searchResults.value = it }
                        .onFailure { _searchResults.value = emptyList() }
                    _searching.value = false
                }
        }
    }

    fun onQueryChanged(q: String) { _query.value = q }

    fun playSong(item: MusicItem.Song) {
        val song = item.toSongEntity()
        viewModelScope.launch {
            repository.upsertSong(song)
            repository.addSearchHistory(item.title)
            playerController.play(listOf(song), 0)
        }
    }
}
