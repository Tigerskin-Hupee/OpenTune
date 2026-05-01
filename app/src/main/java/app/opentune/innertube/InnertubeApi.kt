/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * Backed by NewPipeExtractor — pure-JVM library that handles PoToken,
 * signature ciphers and the n-parameter throttling. Same library used by
 * NewPipe, InnerTune and OuterTune.
 */
package app.opentune.innertube

import android.util.Log
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import javax.inject.Inject
import javax.inject.Singleton

data class YtMusicTrack(
    val videoId: String,
    val title: String,
    val artistName: String,
    val thumbnailUrl: String?,
    val durationText: String?,
)

@Singleton
class InnertubeApi @Inject constructor() {
    private val tag = "InnertubeApi"

    /** Resolve a video id to a direct audio CDN URL (highest-bitrate audio stream). */
    fun getAudioStreamUrl(videoId: String): String {
        val url = "https://www.youtube.com/watch?v=$videoId"
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val streams = info.audioStreams
            ?: error("no audioStreams for $videoId")
        if (streams.isEmpty()) error("audioStreams empty for $videoId")
        val best = streams.maxByOrNull { s ->
            s.averageBitrate.takeIf { it > 0 } ?: s.bitrate
        } ?: error("no best audio stream for $videoId")
        val streamUrl = best.content ?: error("audio stream had null content url for $videoId")
        Log.d(tag, "getAudioStreamUrl($videoId) ok format=${best.format?.name} bitrate=${best.averageBitrate}")
        return streamUrl
    }

    fun search(query: String): List<YtMusicTrack> {
        return try {
            val info = SearchInfo.getInfo(
                ServiceList.YouTube,
                ServiceList.YouTube.searchQHFactory.fromQuery(
                    query,
                    listOf("music_songs"),
                    "",
                ),
            )
            val tracks = info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toTrack() }
            Log.d(tag, "search('$query') -> ${tracks.size} tracks")
            tracks
        } catch (e: Exception) {
            Log.w(tag, "search('$query') failed [${e.javaClass.simpleName}]: ${e.message}")
            // Fallback: untyped search if music_songs filter fails
            try {
                val info = SearchInfo.getInfo(
                    ServiceList.YouTube,
                    ServiceList.YouTube.searchQHFactory.fromQuery(query),
                )
                info.relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNull { it.toTrack() }
            } catch (e2: Exception) {
                Log.w(tag, "search('$query') fallback also failed: ${e2.message}")
                emptyList()
            }
        }
    }

    /**
     * Recommendation feed. Without OAuth we can't get a personalised home
     * feed, so we use YouTube's globally-curated music chart playlist.
     */
    fun getRecommendations(): List<YtMusicTrack> {
        // YouTube Music — Top 100 Global. Stable, public, no auth required.
        val playlistUrl = "https://music.youtube.com/playlist?list=PL4fGSI1pDJn40WjZ6utkIuj2rNg-7iGsq"
        return try {
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl)
            val items = info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toTrack() }
                .take(30)
            Log.d(tag, "getRecommendations -> ${items.size} tracks (chart playlist)")
            if (items.isNotEmpty()) return items
            // Fallback: a popular search if the playlist disappears
            search("top hits ${java.time.Year.now().value}").take(30)
        } catch (e: Exception) {
            Log.w(tag, "getRecommendations failed [${e.javaClass.simpleName}]: ${e.message}")
            try {
                search("top hits ${java.time.Year.now().value}").take(30)
            } catch (e2: Exception) {
                Log.w(tag, "getRecommendations search fallback also failed: ${e2.message}")
                emptyList()
            }
        }
    }

    private fun StreamInfoItem.toTrack(): YtMusicTrack? {
        val id = videoIdFromUrl(url) ?: return null
        val thumb = thumbnails?.maxByOrNull { it.width }?.url
        val durationText = if (duration > 0) formatDuration(duration) else null
        return YtMusicTrack(
            videoId = id,
            title = name ?: id,
            artistName = uploaderName ?: "",
            thumbnailUrl = thumb,
            durationText = durationText,
        )
    }

    private fun videoIdFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        // youtube.com/watch?v=XXXX
        val watchIdx = url.indexOf("v=")
        if (watchIdx >= 0) {
            val raw = url.substring(watchIdx + 2)
            val end = raw.indexOfAny(charArrayOf('&', '#', '?'))
            return (if (end >= 0) raw.substring(0, end) else raw).takeIf { it.isNotBlank() }
        }
        // youtu.be/XXXX
        val short = "youtu.be/"
        val si = url.indexOf(short)
        if (si >= 0) {
            val raw = url.substring(si + short.length)
            val end = raw.indexOfAny(charArrayOf('&', '#', '?', '/'))
            return (if (end >= 0) raw.substring(0, end) else raw).takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun formatDuration(seconds: Long): String {
        val s = seconds % 60
        val m = (seconds / 60) % 60
        val h = seconds / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
