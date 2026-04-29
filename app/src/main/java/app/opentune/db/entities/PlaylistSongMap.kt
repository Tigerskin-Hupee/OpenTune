package app.opentune.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "playlist_song_map",
    foreignKeys = [
        ForeignKey(entity = Playlist::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Song::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("playlistId"), Index("songId")],
)
data class PlaylistSongMap(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val playlistId: String,
    val songId: String,
    val position: Int,
)
