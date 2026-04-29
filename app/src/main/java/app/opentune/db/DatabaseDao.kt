package app.opentune.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import app.opentune.db.daos.AlbumsDao
import app.opentune.db.daos.ArtistsDao
import app.opentune.db.daos.PlaylistsDao
import app.opentune.db.daos.QueueDao
import app.opentune.db.daos.SongsDao
import app.opentune.db.entities.AlbumEntity
import app.opentune.db.entities.ArtistEntity
import app.opentune.db.entities.Event
import app.opentune.db.entities.EventWithSong
import app.opentune.db.entities.FormatEntity
import app.opentune.db.entities.GenreEntity
import app.opentune.db.entities.LyricsEntity
import app.opentune.db.entities.QueueEntity
import app.opentune.db.entities.QueueSongMap
import app.opentune.db.entities.SearchHistory
import app.opentune.db.entities.Song
import app.opentune.db.entities.SongAlbumMap
import app.opentune.db.entities.SongArtistMap
import app.opentune.db.entities.SongEntity
import app.opentune.db.entities.SongGenreMap
import app.opentune.extensions.toSQLiteQuery
import app.opentune.models.MediaMetadata
import app.opentune.models.MultiQueueObject
import kotlinx.coroutines.flow.Flow

@Dao
interface DatabaseDao : SongsDao, AlbumsDao, ArtistsDao, PlaylistsDao, QueueDao {

    // TODO: random selection or algorithm     fun quickPicks(now: Long = System.currentTimeMillis()): Flow<List<Song>>
    @Transaction
    @Query("SELECT * FROM song LIMIT 20")
    fun quickPicks(): Flow<List<Song>>

    @Query("SELECT * FROM format WHERE id = :id")
    fun format(id: String?): Flow<FormatEntity?>

    @Query("SELECT * FROM lyrics WHERE id = :id")
    fun lyrics(id: String?): Flow<LyricsEntity?>

    @Transaction
    @Query("SELECT * FROM event ORDER BY rowId DESC")
    fun events(): Flow<List<EventWithSong>>

    @Query("DELETE FROM event")
    fun clearListenHistory()

    @Query("SELECT * FROM search_history WHERE `query` LIKE :query || '%' ORDER BY id DESC")
    fun searchHistory(query: String = ""): Flow<List<SearchHistory>>

    @Query("DELETE FROM search_history")
    fun clearSearchHistory()

    @Query("""
        SELECT * FROM genre
        ORDER BY genre.title ASC
    LIMIT :previewSize""")
    fun allgenresByName(previewSize: Int = Int.MAX_VALUE): List<GenreEntity>

    @Query("SELECT * FROM genre WHERE id = :id")
    fun genreById(id: String): GenreEntity?

    @Query("SELECT * FROM genre WHERE title = :name")
    fun genreByName(name: String): GenreEntity?

    @Query("SELECT * FROM genre WHERE title LIKE '%' || :query || '%' LIMIT :previewSize")
    fun genreByNameFuzzy(query: String, previewSize: Int = Int.MAX_VALUE): List<GenreEntity>

    @Transaction
    @Query("UPDATE song_genre_map SET genreId = :newId WHERE genreId = :oldId")
    fun updateSongGenreMap(oldId: String, newId: String)

    @Query(
        """
        DELETE FROM genre
        WHERE NOT EXISTS (
            SELECT 1
            FROM song_genre_map
            WHERE song_genre_map.genreId = :genreId
        )
        AND id = :genreId
    """
    )
    fun safeDeleteGenre(genreId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(genre: GenreEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: SongGenreMap)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(searchHistory: SearchHistory)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(event: Event)

    @Transaction
    fun insert(mediaMetadata: MediaMetadata, block: (SongEntity) -> SongEntity = { it }) {
        if (insert(mediaMetadata.toSongEntity().let(block)) == -1L) return
        mediaMetadata.artists.forEachIndexed { index, artist ->
            val artistId = artist.id ?: artistByName(artist.name)?.id ?: ArtistEntity.generateArtistId()
            insert( // TODO: use upsert???
                ArtistEntity(
                    id = artistId,
                    name = artist.name,
                )
            )
            insert(
                SongArtistMap(
                    songId = mediaMetadata.id,
                    artistId = artistId,
                    position = index
                )
            )
        }
        mediaMetadata.genre?.forEachIndexed { index, genre ->
            val genreId = genreByName(genre.title)?.id ?: GenreEntity.generateGenreId()
            insert( // TODO: use upsert???
                GenreEntity(
                    id = genreId,
                    title = genre.title,
                )
            )
            insert(
                SongGenreMap(
                    songId = mediaMetadata.id,
                    genreId = genreId,
                    index = index
                )
            )
        }

        mediaMetadata.album?.let {
            val album = albumsByName(it.title)
            val albumId = album?.id ?: GenreEntity.generateGenreId()
            upsert(
                AlbumEntity(
                    id = albumId,
                    title = it.title,
                    thumbnailUrl = album?.thumbnailUrl?: mediaMetadata.thumbnailUrl,
                    songCount = 1,
                    duration = (album?.duration ?: 0) + mediaMetadata.duration,
                )
            )
            insert(
                SongAlbumMap(
                    songId = mediaMetadata.id,
                    albumId = albumId,
                    index = album?.songCount ?: 0
                )
            )
        }
    }

    @Upsert
    fun upsert(lyrics: LyricsEntity)

    @Upsert
    fun upsert(format: FormatEntity)

    @Delete
    fun delete(lyrics: LyricsEntity)

    @Query("DELETE FROM lyrics where id = :id")
    fun deleteLyricById(id: String)

    @Delete
    fun delete(searchHistory: SearchHistory)

    @Delete
    fun delete(event: Event)


    /**
     * WARNING: This removes all queue song data and re-adds the queue. Did you mean to use updateQueue()?
     */
    @Transaction
    fun saveQueue(mq: MultiQueueObject) {
        if (mq.queue.isEmpty()) {
            return
        }

        insert(
            QueueEntity(
                id = mq.id,
                title = mq.title,
                shuffled = mq.shuffled,
                queuePos = mq.queuePos,
                lastSongPos = mq.lastSongPos,
                index = mq.index,
            )
        )

        deleteAllQueueSongs(mq.id)
        // insert songs

        // why does kotlin not have for i loop???
        var i = 0
        while (i < mq.getSize()) {
            insert(mq.queue[i]) // make sure song exists
            insert(
                QueueSongMap(
                    queueId = mq.id,
                    songId = mq.queue[i].id,
                    index = i.toLong(),
                    shuffledIndex = mq.queue[i].shuffleIndex.toLong()
                )
            )
            i ++
        }
    }

    /**
     * Nukes
     */

    @Transaction
    @Query("DELETE FROM genre")
    fun nukeLocalGenre()

    @Transaction
    @Query("""
DELETE FROM format 
WHERE format.id IS NOT NULL 
AND NOT EXISTS (
    SELECT 1 FROM song WHERE song.id = format.id
);
    """)
    fun nukeDanglingFormatEntities()

    @Transaction
    @Query("DELETE FROM lyrics WHERE lyrics.id IN (SELECT song.id FROM song WHERE song.isLocal)")
    fun nukeLocalLyrics()

    @Transaction
    @Query("DELETE FROM lyrics WHERE lyrics.id NOT IN (SELECT song.id FROM song)")
    fun nukeDanglingLyrics()

    @Transaction
    @Query("DELETE FROM playlist WHERE isLocal = 0")
    fun nukeRemotePlaylists()

    @Transaction
    fun nukeLocalData() {
        nukeLocalSongs()
        nukeArtists()
        nukeAlbums()
        nukeLocalGenre()
    }

    @RawQuery
    fun raw(supportSQLiteQuery: SupportSQLiteQuery): Int

    fun checkpoint() {
        raw("PRAGMA wal_checkpoint(FULL)".toSQLiteQuery())
    }
}
