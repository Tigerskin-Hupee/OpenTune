package app.opentune.db.daos

import androidx.room.*
import app.opentune.db.entities.FormatEntity
import app.opentune.db.entities.LyricsEntity

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE id = :id")
    suspend fun getLyrics(id: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLyrics(lyrics: LyricsEntity)

    @Query("SELECT * FROM format WHERE id = :id")
    suspend fun getFormat(id: String): FormatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFormat(format: FormatEntity)

    @Query("DELETE FROM format WHERE expiredAt < :now")
    suspend fun deleteExpiredFormats(now: Long = System.currentTimeMillis())
}
