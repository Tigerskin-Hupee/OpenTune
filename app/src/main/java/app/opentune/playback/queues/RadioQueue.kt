package app.opentune.playback.queues

import app.opentune.innertube.InnertubeApi
import app.opentune.innertube.YtMusicTrack
import app.opentune.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Infinite radio queue seeded from a YouTube video's related items.
 * Continuously fetches more related songs when nextPage() is called.
 */
class RadioQueue(
    private val seedTrack: MediaMetadata,
    private val api: InnertubeApi,
) : Queue {
    override val preloadItem: MediaMetadata = seedTrack
    override val startShuffled: Boolean = false

    // Tracks the last videoId used so nextPage() fetches different related songs
    private var lastId: String = seedTrack.id

    override suspend fun getInitialStatus(): Queue.Status {
        val related = withContext(Dispatchers.IO) {
            api.getRelatedSongs(seedTrack.id)
        }
        if (related.isNotEmpty()) lastId = related.last().videoId
        val items = mutableListOf(seedTrack)
        items.addAll(related.map { it.toMediaMetadata() })
        return Queue.Status(
            title = "Radio: ${seedTrack.title}",
            items = items,
            mediaItemIndex = 0,
        )
    }

    override fun hasNextPage(): Boolean = true

    override suspend fun nextPage(): List<MediaMetadata> {
        val related = withContext(Dispatchers.IO) {
            api.getRelatedSongs(lastId)
        }
        if (related.isNotEmpty()) lastId = related.last().videoId
        return related.map { it.toMediaMetadata() }
    }

    private fun YtMusicTrack.toMediaMetadata() = MediaMetadata(
        id = videoId,
        title = title,
        artists = listOf(MediaMetadata.Artist(id = null, name = artistName)),
        duration = 0,
        thumbnailUrl = thumbnailUrl,
        genre = null,
    )
}
