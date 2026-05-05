package app.opentune.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.constants.PlaylistSongSortDescendingKey
import app.opentune.constants.PlaylistSongSortType
import app.opentune.constants.PlaylistSongSortTypeKey
import app.opentune.db.MusicDatabase
import app.opentune.db.entities.ArtistEntity
import app.opentune.db.entities.PlaylistSongMap
import app.opentune.db.entities.SongArtistMap
import app.opentune.db.entities.SongEntity
import app.opentune.extensions.reversed
import app.opentune.extensions.toEnum
import app.opentune.innertube.InnertubeApi
import app.opentune.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalPlaylistViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val database: MusicDatabase,
    private val api: InnertubeApi,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val playlistId = savedStateHandle.get<String>("playlistId")!!
    val playlistWithSongs = combine(
        database.playlist(playlistId),
        database.playlistSongs(playlistId),
        context.dataStore.data
            .map {
                it[PlaylistSongSortTypeKey].toEnum(PlaylistSongSortType.CUSTOM) to
                        (it[PlaylistSongSortDescendingKey] ?: true)
            }
            .distinctUntilChanged()
    ) { playlist, songs, (sortType, sortDescending) ->
        val sortedSongs = when (sortType) {
            PlaylistSongSortType.CUSTOM -> songs
            PlaylistSongSortType.NAME -> songs.sortedBy { it.song.song.title.lowercase() }
            PlaylistSongSortType.ARTIST -> songs.sortedBy { song ->
                song.song.artists.joinToString { it.name }.lowercase()
            }
            PlaylistSongSortType.ADDED_DATE -> songs.sortedBy { it.song.song.inLibrary }
            PlaylistSongSortType.MODIFIED_DATE -> songs.sortedBy { it.song.song.dateModified }
            PlaylistSongSortType.RELEASE_DATE -> songs.sortedBy { it.song.song.getDateLong() }
        }.reversed(sortDescending && sortType != PlaylistSongSortType.CUSTOM)

        Pair(playlist, sortedSongs)
    }.stateIn(viewModelScope, SharingStarted.Lazily, Pair(null, emptyList()))

    init {
        // Fix playlist song order
        viewModelScope.launch(Dispatchers.IO) {
            val sortedSongs = playlistWithSongs.first().second.sortedWith(compareBy({ it.map.position }, { it.map.id }))
            database.transaction {
                sortedSongs.forEachIndexed { index, song ->
                    if (song.map.position != index) {
                        update(song.map.copy(position = index))
                    }
                }
            }
        }

        // Auto-sync bookmarked YouTube playlists in the background.
        // Uses a DB transaction so the UI jumps directly from old → new songs
        // without a brief empty flash.
        viewModelScope.launch(Dispatchers.IO) {
            val playlist = playlistWithSongs.first().first?.playlist ?: return@launch
            if (playlist.isLocal || playlist.bookmarkedAt == null) return@launch
            try {
                val songs = api.getPlaylistSongs(playlistId)
                if (songs.isEmpty()) return@launch
                database.transaction {
                    clearPlaylist(playlistId)
                    songs.forEachIndexed { index, track ->
                        insert(SongEntity(id = track.videoId, title = track.title, thumbnailUrl = track.thumbnailUrl, localPath = null))
                        if (track.artistName.isNotBlank()) {
                            val artistId = "YTA_${track.artistName.hashCode()}"
                            insert(ArtistEntity(id = artistId, name = track.artistName))
                            insert(SongArtistMap(songId = track.videoId, artistId = artistId, position = 0))
                        }
                        insert(PlaylistSongMap(playlistId = playlistId, songId = track.videoId, position = index))
                    }
                }
                Log.d("LocalPlaylistVM", "Auto-synced '$playlistId': ${songs.size} songs")
            } catch (e: Exception) {
                Log.w("LocalPlaylistVM", "Auto-sync failed for '$playlistId': ${e.message}")
            }
        }
    }
}
