package app.opentune.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.db.MusicRepository
import app.opentune.db.entities.Album
import app.opentune.db.entities.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val playerController: PlayerController,
) : ViewModel() {

    private val _albumId = MutableStateFlow<String?>(null)

    val album: StateFlow<Album?> = _albumId
        .filterNotNull()
        .flatMapLatest { id -> repository.getBookmarkedAlbums().map { it.find { a -> a.id == id } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val songs: StateFlow<List<Song>> = repository.getAllSongs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun load(albumId: String) { _albumId.value = albumId }

    fun play(songs: List<Song>, startIndex: Int) = playerController.play(songs, startIndex)
}
