package app.opentune.db

import androidx.room.Database
import androidx.room.RoomDatabase
import app.opentune.db.daos.*
import app.opentune.db.entities.*

@Database(
    entities = [
        Song::class,
        Artist::class,
        Album::class,
        Playlist::class,
        SongArtistMap::class,
        SongAlbumMap::class,
        PlaylistSongMap::class,
        SearchHistory::class,
        FormatEntity::class,
        LyricsEntity::class,
        Event::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun lyricsDao(): LyricsDao

    companion object {
        const val DB_NAME = "music.db"
        const val SCHEMA_VERSION = 1  // must match @Database(version = ...)
    }
}
