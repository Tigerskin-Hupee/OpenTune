package app.opentune.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.db.MusicRepository
import app.opentune.db.entities.Playlist
import app.opentune.db.entities.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val playerController: PlayerController,
) : ViewModel() {

    private val _playlistId = MutableStateFlow<String?>(null)

    val playlist: StateFlow<Playlist?> = _playlistId
        .filterNotNull()
        .flatMapLatest { id -> repository.getAllPlaylists().map { it.find { p -> p.id == id } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val songs: StateFlow<List<Song>> = _playlistId
        .filterNotNull()
        .flatMapLatest { id -> repository.getPlaylistSongs(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun load(playlistId: String) { _playlistId.value = playlistId }

    fun play(song: Song) {
        val list = songs.value
        val index = list.indexOf(song)
        playerController.play(list, if (index >= 0) index else 0)
    }
}
