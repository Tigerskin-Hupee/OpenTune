package app.opentune.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "song_album_map",
    primaryKeys = ["songId"],
    foreignKeys = [
        ForeignKey(entity = Song::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Album::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("albumId")],
)
data class SongAlbumMap(
    val songId: String,
    val albumId: String,
    val index: Int,
)
