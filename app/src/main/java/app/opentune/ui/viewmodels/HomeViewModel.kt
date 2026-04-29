package app.opentune.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.db.MusicRepository
import app.opentune.db.entities.Song
import app.opentune.innertube.InnertubeApi
import app.opentune.innertube.models.HomeSection
import app.opentune.innertube.models.MusicItem
import app.opentune.innertube.models.toSongEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val playerController: PlayerController,
    private val innertubeApi: InnertubeApi,
) : ViewModel() {

    val recentSongs: StateFlow<List<Song>> = repository.getAllSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _homeSections = MutableStateFlow<List<HomeSection>>(emptyList())
    val homeSections: StateFlow<List<HomeSection>> = _homeSections.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { loadHomeFeed() }

    fun loadHomeFeed() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            innertubeApi.getHomeSections()
                .onSuccess { _homeSections.value = it }
                .onFailure { _error.value = it.message }
            _loading.value = false
        }
    }

    fun play(song: Song) = playerController.play(listOf(song), 0)

    fun play(item: MusicItem.Song) {
        val song = item.toSongEntity()
        viewModelScope.launch { repository.upsertSong(song) }
        playerController.play(listOf(song), 0)
    }
}
