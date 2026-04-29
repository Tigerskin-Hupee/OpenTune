package app.opentune.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks GitHub for a newer yt-dlp release and silently enqueues an update
 * if the installed version differs. Failures are swallowed — the existing
 * binary continues to work regardless.
 */
@Singleton
class YtDlpUpdateChecker @Inject constructor(
    private val manager: YtDlpManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class LatestRelease(@SerialName("tag_name") val tagName: String)

    fun checkInBackground() {
        scope.launch {
            runCatching {
                val latest = fetchLatestTag()
                manager.saveLatestVersion(latest)

                val installed = manager.installedVersionFlow.first()
                if (installed != latest) {
                    manager.enqueueUpdate(version = latest)
                }
            }
            // Failure silently ignored — old binary stays usable
        }
    }

    private fun fetchLatestTag(): String {
        val req = Request.Builder()
            .url("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val body = http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "GitHub API ${resp.code}" }
            resp.body?.string() ?: error("Empty body")
        }
        return json.decodeFromString<LatestRelease>(body).tagName
    }
}
