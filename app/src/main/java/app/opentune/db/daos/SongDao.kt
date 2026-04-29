package app.opentune.db.daos

import androidx.room.*
import app.opentune.db.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY inLibrary DESC")
    fun getAllSongs(): Flow<List<Song>>

    @Query("SELECT * FROM song WHERE liked = 1 ORDER BY likedAt DESC")
    fun getLikedSongs(): Flow<List<Song>>

    @Query("SELECT * FROM song WHERE downloadState = 2 ORDER BY dateModified DESC")
    fun getDownloadedSongs(): Flow<List<Song>>

    @Query("SELECT * FROM song WHERE id = :id")
    suspend fun getSong(id: String): Song?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(song: Song)

    @Update
    suspend fun update(song: Song)

    @Upsert
    suspend fun upsert(song: Song)

    @Delete
    suspend fun delete(song: Song)

    @Query("UPDATE song SET liked = :liked, likedAt = :likedAt WHERE id = :id")
    suspend fun toggleLike(id: String, liked: Boolean, likedAt: Long?)

    @Query("UPDATE song SET inLibrary = :inLibrary WHERE id = :id")
    suspend fun setInLibrary(id: String, inLibrary: Long?)

    @Query("UPDATE song SET downloadState = :state, localPath = :path WHERE id = :id")
    suspend fun updateDownloadState(id: String, state: Int, path: String?)

    @Query("SELECT * FROM search_history ORDER BY createdAt DESC LIMIT 20")
    fun getSearchHistory(): Flow<List<SearchHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchHistory(entry: SearchHistory)

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun deleteSearchHistory(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()

    @Query("SELECT * FROM event ORDER BY timestamp DESC LIMIT 100")
    fun getRecentEvents(): Flow<List<Event>>

    @Insert
    suspend fun insertEvent(event: Event)
}
