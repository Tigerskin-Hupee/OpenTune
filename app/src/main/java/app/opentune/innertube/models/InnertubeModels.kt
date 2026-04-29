package app.opentune.innertube.models

import kotlinx.serialization.Serializable

// ─── Primitive helpers ────────────────────────────────────────────────────────

@Serializable
data class Runs(val runs: List<Run>? = null) {
    @Serializable
    data class Run(
        val text: String = "",
        val navigationEndpoint: NavigationEndpoint? = null,
    )

    val text: String get() = runs?.joinToString("") { it.text } ?: ""

    /** Split by " • " separator runs, as YouTube Music uses for metadata rows. */
    fun splitBySeparator(): List<List<Run>> {
        val result = mutableListOf<MutableList<Run>>()
        var current = mutableListOf<Run>()
        for (run in runs.orEmpty()) {
            if (run.text.trim() == "•" || run.text == " • ") {
                if (current.isNotEmpty()) { result += current; current = mutableListOf() }
            } else {
                current += run
            }
        }
        if (current.isNotEmpty()) result += current
        return result
    }
}

@Serializable
data class Thumbnail(
    val thumbnails: List<ThumbnailItem>? = null,
) {
    @Serializable
    data class ThumbnailItem(val url: String = "", val width: Int? = null, val height: Int? = null)

    val bestUrl: String? get() = thumbnails?.lastOrNull()?.url
}

@Serializable
data class ThumbnailRenderer(
    val musicThumbnailRenderer: MusicThumbnailRenderer? = null,
) {
    @Serializable
    data class MusicThumbnailRenderer(val thumbnail: Thumbnail? = null)

    fun bestUrl(): String? = musicThumbnailRenderer?.thumbnail?.bestUrl
}

// ─── Navigation ───────────────────────────────────────────────────────────────

@Serializable
data class NavigationEndpoint(
    val watchEndpoint: WatchEndpoint? = null,
    val browseEndpoint: BrowseEndpoint? = null,
    val watchPlaylistEndpoint: WatchPlaylistEndpoint? = null,
) {
    @Serializable
    data class WatchEndpoint(val videoId: String? = null, val playlistId: String? = null)

    @Serializable
    data class WatchPlaylistEndpoint(val playlistId: String? = null, val params: String? = null)

    @Serializable
    data class BrowseEndpoint(
        val browseId: String? = null,
        val browseEndpointContextSupportedConfigs: Config? = null,
    ) {
        @Serializable
        data class Config(val browseEndpointContextMusicConfig: MusicConfig? = null) {
            @Serializable
            data class MusicConfig(val pageType: String? = null)
        }
        val pageType: String? get() =
            browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
    }
}

// ─── Responsive list item (used in search shelves) ────────────────────────────

@Serializable
data class MusicResponsiveListItemRenderer(
    val thumbnail: ThumbnailRenderer? = null,
    val flexColumns: List<FlexColumn>? = null,
    val fixedColumns: List<FixedColumn>? = null,
    val overlay: Overlay? = null,
    val navigationEndpoint: NavigationEndpoint? = null,
) {
    @Serializable
    data class FlexColumn(
        val musicResponsiveListItemFlexColumnRenderer: Renderer? = null,
    ) {
        @Serializable
        data class Renderer(val text: Runs? = null)
    }

    @Serializable
    data class FixedColumn(
        val musicResponsiveListItemFixedColumnRenderer: Renderer? = null,
    ) {
        @Serializable
        data class Renderer(val text: Runs? = null)
    }

    @Serializable
    data class Overlay(val musicItemThumbnailOverlayRenderer: OverlayRenderer? = null) {
        @Serializable
        data class OverlayRenderer(val content: Content? = null) {
            @Serializable
            data class Content(val musicPlayButtonRenderer: PlayButtonRenderer? = null) {
                @Serializable
                data class PlayButtonRenderer(val playNavigationEndpoint: NavigationEndpoint? = null)
            }
        }
    }

    val titleRuns: List<Runs.Run>
        get() = flexColumns?.firstOrNull()
            ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs.orEmpty()

    val subtitleGroups: List<List<Runs.Run>>
        get() = flexColumns?.getOrNull(1)
            ?.musicResponsiveListItemFlexColumnRenderer?.text?.splitBySeparator().orEmpty()

    val videoId: String?
        get() = overlay?.musicItemThumbnailOverlayRenderer
            ?.content?.musicPlayButtonRenderer
            ?.playNavigationEndpoint?.watchEndpoint?.videoId
            ?: titleRuns.firstOrNull()?.navigationEndpoint?.watchEndpoint?.videoId
            ?: navigationEndpoint?.watchEndpoint?.videoId

    val thumbnailUrl: String? get() = thumbnail?.bestUrl()

    val durationText: String?
        get() = fixedColumns?.firstOrNull()
            ?.musicResponsiveListItemFixedColumnRenderer?.text?.text?.trim()
            ?.takeIf { it.isNotBlank() }

    val browseId: String? get() = navigationEndpoint?.browseEndpoint?.browseId
    val pageType: String? get() = navigationEndpoint?.browseEndpoint?.pageType
}

// ─── Two-row item (used in carousel shelves) ──────────────────────────────────

@Serializable
data class MusicTwoRowItemRenderer(
    val title: Runs? = null,
    val subtitle: Runs? = null,
    val thumbnailRenderer: ThumbnailRenderer? = null,
    val navigationEndpoint: NavigationEndpoint? = null,
) {
    val browseId: String? get() = navigationEndpoint?.browseEndpoint?.browseId
    val videoId: String? get() = navigationEndpoint?.watchEndpoint?.videoId
    val pageType: String? get() = navigationEndpoint?.browseEndpoint?.pageType
}

// ─── Section renderers ────────────────────────────────────────────────────────

@Serializable
data class MusicShelfRenderer(
    val title: Runs? = null,
    val contents: List<Content>? = null,
    val continuations: List<Continuation>? = null,
) {
    @Serializable
    data class Content(val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null)
}

@Serializable
data class MusicCarouselShelfRenderer(
    val header: Header? = null,
    val contents: List<Content>? = null,
) {
    @Serializable
    data class Header(val musicCarouselShelfBasicHeaderRenderer: BasicHeader? = null) {
        @Serializable
        data class BasicHeader(val title: Runs? = null, val strapline: Runs? = null)
    }

    @Serializable
    data class Content(
        val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null,
        val musicTwoRowItemRenderer: MusicTwoRowItemRenderer? = null,
    )

    val title: String? get() = header?.musicCarouselShelfBasicHeaderRenderer?.title?.text
}

@Serializable
data class SectionListRenderer(
    val contents: List<SectionContent>? = null,
) {
    @Serializable
    data class SectionContent(
        val musicShelfRenderer: MusicShelfRenderer? = null,
        val musicCarouselShelfRenderer: MusicCarouselShelfRenderer? = null,
        val musicImmersiveCarouselShelfRenderer: MusicCarouselShelfRenderer? = null,
    ) {
        val carousel: MusicCarouselShelfRenderer?
            get() = musicCarouselShelfRenderer ?: musicImmersiveCarouselShelfRenderer
    }
}

@Serializable
data class Continuation(val nextContinuationData: NextData? = null) {
    @Serializable
    data class NextData(val continuation: String? = null)
}

// ─── Top-level responses ──────────────────────────────────────────────────────

@Serializable
data class SearchResponse(val contents: Contents? = null) {
    @Serializable
    data class Contents(val tabbedSearchResultsRenderer: Tabbed? = null) {
        @Serializable
        data class Tabbed(val tabs: List<Tab>? = null) {
            @Serializable
            data class Tab(val tabRenderer: TabRenderer? = null) {
                @Serializable
                data class TabRenderer(val content: Content? = null) {
                    @Serializable
                    data class Content(val sectionListRenderer: SectionListRenderer? = null)
                }
            }
        }
    }

    fun sectionListRenderer(): SectionListRenderer? = contents
        ?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
        ?.tabRenderer?.content?.sectionListRenderer
}

@Serializable
data class BrowseResponse(val contents: Contents? = null) {
    @Serializable
    data class Contents(
        val singleColumnBrowseResultsRenderer: SingleColumn? = null,
        val sectionListRenderer: SectionListRenderer? = null,
    ) {
        @Serializable
        data class SingleColumn(val tabs: List<Tab>? = null) {
            @Serializable
            data class Tab(val tabRenderer: TabRenderer? = null) {
                @Serializable
                data class TabRenderer(val content: Content? = null) {
                    @Serializable
                    data class Content(val sectionListRenderer: SectionListRenderer? = null)
                }
            }
        }
    }

    fun sectionListRenderer(): SectionListRenderer? =
        contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer
            ?: contents?.sectionListRenderer
}

// ─── Request context ──────────────────────────────────────────────────────────

@Serializable
data class InnertubeContext(val client: Client) {
    @Serializable
    data class Client(
        val clientName: String,
        val clientVersion: String,
        val hl: String = "en",
        val gl: String = "US",
        val userAgent: String? = null,
        val androidSdkVersion: Int? = null,
    )

    companion object {
        val WEB_REMIX = InnertubeContext(
            client = Client(clientName = "WEB_REMIX", clientVersion = "1.20240318.01.00"),
        )
        val ANDROID_MUSIC = InnertubeContext(
            client = Client(
                clientName = "ANDROID_MUSIC",
                clientVersion = "6.42.52",
                androidSdkVersion = 30,
                userAgent = "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 11) gzip",
            ),
        )
    }
}

// ─── Request bodies ───────────────────────────────────────────────────────────

@Serializable
data class SearchBody(val context: InnertubeContext, val query: String, val params: String? = null)

@Serializable
data class BrowseBody(val context: InnertubeContext, val browseId: String, val params: String? = null)

// ─── Conversion: MusicResponsiveListItemRenderer → MusicItem ─────────────────

fun MusicResponsiveListItemRenderer.toMusicItem(): MusicItem? {
    val title = titleRuns.joinToString("") { it.text }.trim().takeIf { it.isNotBlank() } ?: return null
    val vid = videoId
    val bid = browseId
    val pt  = pageType

    return when {
        vid != null -> {
            val subtitle = subtitleGroups
            // Group 1 (skip type label at 0) contains artist names
            val artists = subtitle.drop(1)
                .filter { g ->
                    g.firstOrNull()?.navigationEndpoint?.browseEndpoint?.pageType
                        ?.contains("ARTIST", ignoreCase = true) == true
                }
                .joinToString(", ") { g -> g.joinToString("") { r -> r.text } }
                .ifBlank { subtitle.getOrNull(1)?.joinToString("") { it.text } ?: "" }
            val album = subtitle.firstOrNull { g ->
                g.firstOrNull()?.navigationEndpoint?.browseEndpoint?.pageType
                    ?.contains("ALBUM", ignoreCase = true) == true
            }?.joinToString("") { it.text }
            MusicItem.Song(id = vid, title = title, artists = artists, album = album,
                thumbnailUrl = thumbnailUrl, durationText = durationText)
        }
        pt != null && pt.contains("ALBUM", ignoreCase = true) && bid != null -> {
            val subs = subtitleGroups
            MusicItem.Album(
                browseId = bid, title = title,
                artists = subs.getOrNull(1)?.joinToString("") { it.text },
                thumbnailUrl = thumbnailUrl,
                year = subs.lastOrNull { g -> g.firstOrNull()?.navigationEndpoint == null }
                    ?.joinToString("") { it.text }?.takeIf { it.matches(Regex("\\d{4}")) },
            )
        }
        pt != null && (pt.contains("ARTIST", ignoreCase = true) || pt.contains("USER_CHANNEL", ignoreCase = true)) && bid != null ->
            MusicItem.Artist(browseId = bid, name = title, thumbnailUrl = thumbnailUrl)
        pt != null && pt.contains("PLAYLIST", ignoreCase = true) && bid != null ->
            MusicItem.Playlist(
                browseId = bid, title = title,
                author = subtitleGroups.getOrNull(1)?.joinToString("") { it.text },
                thumbnailUrl = thumbnailUrl,
            )
        else -> null
    }
}

fun MusicTwoRowItemRenderer.toMusicItem(): MusicItem? {
    val title = title?.text?.trim().takeIf { !it.isNullOrBlank() } ?: return null
    val bid = browseId
    val vid = videoId
    val pt  = pageType
    val subs = subtitle?.splitBySeparator()

    return when {
        vid != null ->
            MusicItem.Song(id = vid, title = title, artists = subs?.getOrNull(1)?.joinToString("") { it.text } ?: "",
                thumbnailUrl = thumbnailRenderer?.bestUrl())
        bid != null && (pt?.contains("ALBUM", ignoreCase = true) == true || pt?.contains("SINGLE", ignoreCase = true) == true) ->
            MusicItem.Album(browseId = bid, title = title,
                artists = subs?.getOrNull(1)?.joinToString("") { it.text },
                thumbnailUrl = thumbnailRenderer?.bestUrl(),
                year = subs?.lastOrNull()?.joinToString("") { it.text }?.takeIf { it.matches(Regex("\\d{4}")) })
        bid != null && pt?.contains("ARTIST", ignoreCase = true) == true ->
            MusicItem.Artist(browseId = bid, name = title, thumbnailUrl = thumbnailRenderer?.bestUrl())
        bid != null ->
            MusicItem.Playlist(browseId = bid, title = title,
                author = subs?.getOrNull(1)?.joinToString("") { it.text },
                thumbnailUrl = thumbnailRenderer?.bestUrl())
        else -> null
    }
}
