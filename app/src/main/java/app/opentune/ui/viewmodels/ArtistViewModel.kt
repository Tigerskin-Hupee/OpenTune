package app.opentune.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.db.MusicRepository
import app.opentune.db.entities.Artist
import app.opentune.db.entities.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val playerController: PlayerController,
) : ViewModel() {

    private val _artistId = MutableStateFlow<String?>(null)

    val artist: StateFlow<Artist?> = _artistId
        .filterNotNull()
        .flatMapLatest { id -> repository.getBookmarkedArtists().map { it.find { a -> a.id == id } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val songs: StateFlow<List<Song>> = repository.getAllSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun load(artistId: String) { _artistId.value = artistId }

    fun play(song: Song) = playerController.play(listOf(song), 0)
}
