package app.opentune.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlist")
data class Playlist(
    @PrimaryKey val id: String,
    val name: String,
    val browseId: String? = null,
    val thumbnailUrl: String? = null,
    val songCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
