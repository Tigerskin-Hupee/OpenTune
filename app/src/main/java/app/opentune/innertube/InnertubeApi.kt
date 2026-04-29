package app.opentune.innertube

import app.opentune.innertube.models.BrowseBody
import app.opentune.innertube.models.BrowseResponse
import app.opentune.innertube.models.InnertubeContext
import app.opentune.innertube.models.MusicItem
import app.opentune.innertube.models.HomeSection
import app.opentune.innertube.models.SearchBody
import app.opentune.innertube.models.SearchResponse
import app.opentune.innertube.models.toMusicItem
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InnertubeApi @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    suspend fun search(query: String): Result<List<MusicItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = http.post(SEARCH_URL) {
                applyWebRemixHeaders()
                setBody(SearchBody(context = InnertubeContext.WEB_REMIX, query = query))
            }.body<SearchResponse>()

            response.sectionListRenderer()
                ?.contents.orEmpty()
                .flatMap { section ->
                    section.musicShelfRenderer?.contents.orEmpty()
                        .mapNotNull { it.musicResponsiveListItemRenderer?.toMusicItem() }
                }
        }
    }

    // ── Home page ─────────────────────────────────────────────────────────────

    suspend fun getHomeSections(): Result<List<HomeSection>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = http.post(BROWSE_URL) {
                applyWebRemixHeaders()
                setBody(BrowseBody(context = InnertubeContext.WEB_REMIX, browseId = "FEmusic_home"))
            }.body<BrowseResponse>()

            response.sectionListRenderer()
                ?.contents.orEmpty()
                .mapNotNull { section ->
                    val carousel = section.carousel ?: return@mapNotNull null
                    val title = carousel.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val items = carousel.contents.orEmpty().mapNotNull { content ->
                        content.musicTwoRowItemRenderer?.toMusicItem()
                            ?: content.musicResponsiveListItemRenderer?.toMusicItem()
                    }
                    if (items.isEmpty()) null else HomeSection(title, items)
                }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun HttpRequestBuilder.applyWebRemixHeaders() {
        contentType(ContentType.Application.Json)
        header("X-Goog-Api-Key", API_KEY)
        header("Referer", "https://music.youtube.com/")
        header("Origin", "https://music.youtube.com")
        header("X-Goog-Api-Format-Version", "1")
        parameter("key", API_KEY)
        parameter("prettyPrint", "false")
    }

    companion object {
        private const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        private const val BASE = "https://music.youtube.com/youtubei/v1"
        private const val SEARCH_URL = "$BASE/search"
        private const val BROWSE_URL = "$BASE/browse"
    }
}
