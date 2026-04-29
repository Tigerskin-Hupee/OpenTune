package app.opentune.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "artist")
data class Artist(
    @PrimaryKey val id: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val isLocal: Boolean = false,
    val bookmarkedAt: Long? = null,
)
