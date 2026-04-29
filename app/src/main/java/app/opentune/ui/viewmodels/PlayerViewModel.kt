package app.opentune.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.db.MusicRepository
import app.opentune.db.entities.Song
import app.opentune.lyrics.LrcLine
import app.opentune.lyrics.LrcParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val playerController: PlayerController,
) : ViewModel() {

    val currentSong: StateFlow<Song?> = playerController.currentSong
    val isPlaying: StateFlow<Boolean> = playerController.isPlaying
    val position: StateFlow<Long> = playerController.position
    val duration: StateFlow<Long> = playerController.duration

    private val _showLyrics = MutableStateFlow(false)
    val showLyrics: StateFlow<Boolean> = _showLyrics.asStateFlow()

    private val rawLyrics: StateFlow<String?> = currentSong
        .filterNotNull()
        .flatMapLatest { song -> flow { emit(repository.getLyrics(song.id)?.lyrics) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Parsed LRC lines when lyrics use the [mm:ss.xx] format
    val lrcLines: StateFlow<List<LrcLine>?> = rawLyrics
        .map { raw -> if (raw != null && LrcParser.isLrc(raw)) LrcParser.parse(raw) else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Plain-text lyrics for non-LRC content
    val lyrics: StateFlow<String?> = rawLyrics
        .combine(lrcLines) { raw, lrc -> if (lrc != null) null else raw }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Index of the active LRC line based on current playback position
    val currentLyricIndex: StateFlow<Int> = combine(lrcLines, position) { lines, pos ->
        if (lines.isNullOrEmpty()) -1
        else maxOf(0, lines.indexOfLast { it.timeMs <= pos })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    fun togglePlayPause() = playerController.togglePlayPause()
    fun skipNext() = playerController.skipNext()
    fun skipPrevious() = playerController.skipPrevious()
    fun seekTo(ms: Long) = playerController.seekTo(ms)
    fun toggleLyrics() { _showLyrics.value = !_showLyrics.value }
}
