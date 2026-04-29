package app.opentune.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "album")
data class Album(
    @PrimaryKey val id: String,
    val title: String,
    val year: Int? = null,
    val thumbnailUrl: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val bookmarkedAt: Long? = null,
)
