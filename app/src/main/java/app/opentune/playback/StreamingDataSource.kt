package app.opentune.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A [DataSource.Factory] that intercepts URIs with the scheme [STREAM_SCHEME].
 *
 * URI format: `opentune://stream/<videoId>`
 *
 * When ExoPlayer encounters such a URI it calls [ResolvingDataSource.Resolver.resolveDataSpec],
 * which blocks on [StreamResolver.getStreamUrl] and rewrites the DataSpec with the real HTTPS URL
 * before handing off to [DefaultDataSource].
 *
 * All other URIs (local files, cached downloads) are passed through unchanged.
 */
@UnstableApi
@Singleton
class StreamingDataSource @Inject constructor(
    private val streamResolver: StreamResolver,
) {
    companion object {
        const val STREAM_SCHEME = "opentune"
        const val STREAM_HOST = "stream"

        fun streamUri(videoId: String): Uri =
            Uri.Builder()
                .scheme(STREAM_SCHEME)
                .authority(STREAM_HOST)
                .appendPath(videoId)
                .build()

        fun videoIdFromUri(uri: Uri): String? =
            if (uri.scheme == STREAM_SCHEME && uri.host == STREAM_HOST)
                uri.pathSegments.firstOrNull()
            else null
    }

    fun createFactory(baseFactory: DefaultDataSource.Factory): DataSource.Factory =
        ResolvingDataSource.Factory(baseFactory, ::resolveSpec)

    private fun resolveSpec(dataSpec: DataSpec): DataSpec {
        val videoId = videoIdFromUri(dataSpec.uri) ?: return dataSpec

        // runBlocking is intentional here — ResolvingDataSource.Resolver is called on
        // ExoPlayer's loading thread, not the main thread, so blocking is acceptable.
        val url = runBlocking {
            streamResolver.getStreamUrl(videoId).getOrThrow()
        }

        return dataSpec.buildUpon().setUri(Uri.parse(url)).build()
    }
}
