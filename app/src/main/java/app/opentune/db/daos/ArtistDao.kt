package app.opentune.db.daos

import androidx.room.*
import app.opentune.db.entities.Artist
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artist WHERE bookmarkedAt IS NOT NULL ORDER BY bookmarkedAt DESC")
    fun getBookmarkedArtists(): Flow<List<Artist>>

    @Query("SELECT * FROM artist WHERE id = :id")
    suspend fun getArtist(id: String): Artist?

    @Upsert
    suspend fun upsert(artist: Artist)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(artist: Artist)

    @Query("UPDATE artist SET bookmarkedAt = :bookmarkedAt WHERE id = :id")
    suspend fun setBookmarked(id: String, bookmarkedAt: Long?)

    @Delete
    suspend fun delete(artist: Artist)
}
