package app.opentune.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "format")
data class FormatEntity(
    @PrimaryKey val id: String,
    val itag: Int,
    val mimeType: String,
    val codecs: String,
    val bitrate: Long,
    val sampleRate: Int? = null,
    val contentLength: Long? = null,
    val loudnessDb: Double? = null,
    val playbackUrl: String? = null,
    val expiredAt: Long? = null,
)
