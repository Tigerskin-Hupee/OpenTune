package app.opentune.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_history")
data class SearchHistory(
    @PrimaryKey val query: String,
    val createdAt: Long = System.currentTimeMillis(),
)
