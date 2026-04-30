package app.opentune.innertube.models

/** Sealed type for all items returned from YouTube Music API calls. */
sealed class MusicItem {
    data class Song(
        val id: String,
        val title: String,
        val artists: String,
        val album: String? = null,
        val thumbnailUrl: String? = null,
        val durationText: String? = null,
    ) : MusicItem()

    data class Album(
        val browseId: String,
        val playlistId: String? = null,
        val title: String,
        val artists: String? = null,
        val thumbnailUrl: String? = null,
        val year: String? = null,
    ) : MusicItem()

    data class Artist(
        val browseId: String,
        val name: String,
        val thumbnailUrl: String? = null,
    ) : MusicItem()

    data class Playlist(
        val browseId: String,
        val title: String,
        val author: String? = null,
        val thumbnailUrl: String? = null,
    ) : MusicItem()
}

data class HomeSection(val title: String, val items: List<MusicItem>)

/** Convert a Song MusicItem to an app.opentune.db.entities.SongEntity for playback. */
fun MusicItem.Song.toSongEntity() = app.opentune.db.entities.SongEntity(
    id = id,
    title = title,
    albumName = album,
    thumbnailUrl = thumbnailUrl,
    duration = durationText?.parseDurationToSeconds() ?: -1,
    localPath = null,
)

private fun String.parseDurationToSeconds(): Int {
    val parts = trim().split(":").map { it.toIntOrNull() ?: 0 }
    return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        else -> 0
    }
}
