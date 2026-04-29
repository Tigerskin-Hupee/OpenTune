package app.opentune.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "song")
data class Song(
    @PrimaryKey val id: String,
    val title: String,
    val duration: Int = -1,
    val thumbnailUrl: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val liked: Boolean = false,
    val likedAt: Long? = null,
    val inLibrary: Long? = null,
    val dateModified: Long = System.currentTimeMillis(),
    val downloadState: Int = DownloadState.NOT_DOWNLOADED,
    val localPath: String? = null,
) {
    object DownloadState {
        const val NOT_DOWNLOADED = 0
        const val DOWNLOADING = 1
        const val DOWNLOADED = 2
    }
}
