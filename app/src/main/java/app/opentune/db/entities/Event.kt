package app.opentune.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "event",
    foreignKeys = [
        ForeignKey(entity = Song::class, parentColumns = ["id"], childColumns = ["songId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("songId")],
)
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val songId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val playTime: Long,
)
