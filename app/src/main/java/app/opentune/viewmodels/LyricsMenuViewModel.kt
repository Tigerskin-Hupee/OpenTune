package app.opentune.viewmodels

import androidx.lifecycle.ViewModel
import app.opentune.constants.LYRIC_FETCH_TIMEOUT
import app.opentune.db.MusicDatabase
import app.opentune.lyrics.LyricsHelper
import app.opentune.lyrics.LyricsResult
import app.opentune.models.MediaMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.akanework.gramophone.logic.utils.SemanticLyrics
import javax.inject.Inject

@HiltViewModel
class LyricsMenuViewModel @Inject constructor(
    private val lyricsHelper: LyricsHelper,
    val database: MusicDatabase,
) : ViewModel() {
    val results = MutableStateFlow(emptyList<LyricsResult>())
    val isLoading = MutableStateFlow(false)

    fun refetchLyrics(mediaMetadata: MediaMetadata, onDone: (SemanticLyrics?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            database.deleteLyricById(mediaMetadata.id)
            withTimeoutOrNull(LYRIC_FETCH_TIMEOUT) {
                val lyrics = lyricsHelper.getLyrics(mediaMetadata)
                onDone(lyrics)
            }
        }
    }
}
