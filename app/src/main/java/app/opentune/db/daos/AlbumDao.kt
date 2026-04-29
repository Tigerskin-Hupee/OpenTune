package app.opentune.db.daos

import androidx.room.*
import app.opentune.db.entities.Album
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("SELECT * FROM album WHERE bookmarkedAt IS NOT NULL ORDER BY bookmarkedAt DESC")
    fun getBookmarkedAlbums(): Flow<List<Album>>

    @Query("SELECT * FROM album WHERE id = :id")
    suspend fun getAlbum(id: String): Album?

    @Upsert
    suspend fun upsert(album: Album)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(album: Album)

    @Query("UPDATE album SET bookmarkedAt = :bookmarkedAt WHERE id = :id")
    suspend fun setBookmarked(id: String, bookmarkedAt: Long?)

    @Delete
    suspend fun delete(album: Album)
}
