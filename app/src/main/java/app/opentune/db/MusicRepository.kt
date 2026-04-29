package app.opentune.db

import app.opentune.db.entities.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepository @Inject constructor(private val db: MusicDatabase) {

    // Songs
    fun getAllSongs(): Flow<List<Song>> = db.songDao().getAllSongs()
    fun getLikedSongs(): Flow<List<Song>> = db.songDao().getLikedSongs()
    fun getDownloadedSongs(): Flow<List<Song>> = db.songDao().getDownloadedSongs()
    suspend fun getSong(id: String) = db.songDao().getSong(id)
    suspend fun upsertSong(song: Song) = db.songDao().upsert(song)
    suspend fun toggleLike(id: String, liked: Boolean) =
        db.songDao().toggleLike(id, liked, if (liked) System.currentTimeMillis() else null)
    suspend fun setInLibrary(id: String, add: Boolean) =
        db.songDao().setInLibrary(id, if (add) System.currentTimeMillis() else null)
    suspend fun updateDownloadState(id: String, state: Int, path: String? = null) =
        db.songDao().updateDownloadState(id, state, path)
    suspend fun insertEvent(songId: String, playTime: Long) =
        db.songDao().insertEvent(Event(songId = songId, playTime = playTime))

    // Artists
    fun getBookmarkedArtists(): Flow<List<Artist>> = db.artistDao().getBookmarkedArtists()
    suspend fun upsertArtist(artist: Artist) = db.artistDao().upsert(artist)
    suspend fun toggleArtistBookmark(id: String, bookmark: Boolean) =
        db.artistDao().setBookmarked(id, if (bookmark) System.currentTimeMillis() else null)

    // Albums
    fun getBookmarkedAlbums(): Flow<List<Album>> = db.albumDao().getBookmarkedAlbums()
    suspend fun upsertAlbum(album: Album) = db.albumDao().upsert(album)
    suspend fun toggleAlbumBookmark(id: String, bookmark: Boolean) =
        db.albumDao().setBookmarked(id, if (bookmark) System.currentTimeMillis() else null)

    // Playlists
    fun getAllPlaylists(): Flow<List<Playlist>> = db.playlistDao().getAllPlaylists()
    fun getPlaylistSongs(playlistId: String): Flow<List<Song>> = db.playlistDao().getPlaylistSongs(playlistId)
    suspend fun createPlaylist(name: String): String {
        val id = java.util.UUID.randomUUID().toString()
        db.playlistDao().insert(Playlist(id = id, name = name))
        return id
    }
    suspend fun addToPlaylist(playlistId: String, songId: String) {
        val pos = (db.playlistDao().getPlaylistMaxPosition(playlistId) ?: -1) + 1
        db.playlistDao().insertPlaylistSongMap(PlaylistSongMap(playlistId = playlistId, songId = songId, position = pos))
    }
    suspend fun removeFromPlaylist(playlistId: String, songId: String) =
        db.playlistDao().removeFromPlaylist(playlistId, songId)

    // Search history
    fun getSearchHistory(): Flow<List<SearchHistory>> = db.songDao().getSearchHistory()
    suspend fun addSearchHistory(query: String) =
        db.songDao().insertSearchHistory(SearchHistory(query = query))
    suspend fun clearSearchHistory() = db.songDao().clearSearchHistory()

    // Lyrics
    suspend fun getLyrics(id: String): LyricsEntity? = db.lyricsDao().getLyrics(id)
    suspend fun saveLyrics(id: String, lyrics: String) =
        db.lyricsDao().upsertLyrics(LyricsEntity(id = id, lyrics = lyrics))

    // Format cache
    suspend fun getFormat(id: String): FormatEntity? = db.lyricsDao().getFormat(id)
    suspend fun saveFormat(format: FormatEntity) = db.lyricsDao().upsertFormat(format)
}
