package app.opentune.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "song_artist_map",
    primaryKeys = ["songId", "artistId"],
    foreignKeys = [
        ForeignKey(entity = Song::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Artist::class, parentColumns = ["id"], childColumns = ["artistId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("artistId")],
)
data class SongArtistMap(
    val songId: String,
    val artistId: String,
    val position: Int,
)
