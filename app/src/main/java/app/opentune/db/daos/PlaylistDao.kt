package app.opentune.db.daos

import androidx.room.*
import app.opentune.db.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun getPlaylist(id: String): Playlist?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(playlist: Playlist): Long

    @Update
    suspend fun update(playlist: Playlist)

    @Delete
    suspend fun delete(playlist: Playlist)

    @Query("""
        SELECT s.* FROM song s
        INNER JOIN playlist_song_map m ON s.id = m.songId
        WHERE m.playlistId = :playlistId
        ORDER BY m.position
    """)
    fun getPlaylistSongs(playlistId: String): Flow<List<Song>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistSongMap(map: PlaylistSongMap)

    @Delete
    suspend fun deletePlaylistSongMap(map: PlaylistSongMap)

    @Query("DELETE FROM playlist_song_map WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeFromPlaylist(playlistId: String, songId: String)

    @Query("SELECT MAX(position) FROM playlist_song_map WHERE playlistId = :playlistId")
    suspend fun getPlaylistMaxPosition(playlistId: String): Int?
}
