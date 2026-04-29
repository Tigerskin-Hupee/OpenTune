package app.opentune.playback

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@HiltWorker
class YtDlpDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val manager: YtDlpManager,
) : CoroutineWorker(appContext, params) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // ── GitHub release models ──────────────────────────────────────────────

    @Serializable
    data class Release(
        @SerialName("tag_name") val tagName: String,
        val assets: List<Asset>,
    )

    @Serializable
    data class Asset(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        val size: Long,
    )

    // ── Worker entry point ─────────────────────────────────────────────────

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setProgress(workDataOf(KEY_PROGRESS to 0f))

            // 1. Fetch release metadata from GitHub
            val targetVersion = inputData.getString(KEY_TARGET_VERSION)
            val release = fetchRelease(targetVersion)
            val assetName = ytDlpAssetName()
            val asset = release.assets.firstOrNull { it.name == assetName }
                ?: return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "Asset '$assetName' not found in release ${release.tagName}")
                )

            manager.saveLatestVersion(release.tagName)

            // 2. Skip if already at this version and binary exists
            val installedVersion = manager.installedVersionFlow.first()
            if (installedVersion == release.tagName && manager.isReady) {
                return@withContext Result.success()
            }

            // 3. Download to temp file, then atomic swap
            val tempFile = File(applicationContext.filesDir, "yt-dlp.tmp")
            downloadWithProgress(asset.browserDownloadUrl, asset.size, tempFile)

            val binFile = manager.binFile
            tempFile.renameTo(binFile)
            binFile.setExecutable(true, true)

            // 4. Persist installed version
            manager.saveInstalledVersion(release.tagName)

            Result.success()
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    // ── Network helpers ────────────────────────────────────────────────────

    private fun fetchRelease(version: String?): Release {
        val url = if (version != null)
            "https://api.github.com/repos/yt-dlp/yt-dlp/releases/tags/$version"
        else
            "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"

        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        val body = http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "GitHub API ${resp.code}: ${resp.message}" }
            resp.body?.string() ?: error("Empty GitHub API response")
        }
        return json.decodeFromString(body)
    }

    private suspend fun downloadWithProgress(url: String, totalBytes: Long, dest: File) {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Download failed ${resp.code}" }
            val body = resp.body ?: error("Empty download body")
            val contentLength = if (totalBytes > 0) totalBytes else body.contentLength()

            dest.parentFile?.mkdirs()
            var downloaded = 0L
            var lastReported = -1

            dest.outputStream().use { out ->
                body.byteStream().use { src ->
                    val buf = ByteArray(32_768)
                    var n: Int
                    while (src.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        downloaded += n
                        val pct = if (contentLength > 0)
                            (downloaded * 100 / contentLength).toInt() else 0
                        if (pct != lastReported) {
                            lastReported = pct
                            setProgress(workDataOf(KEY_PROGRESS to pct / 100f))
                        }
                    }
                }
            }
        }
    }

    // ── Static helpers ─────────────────────────────────────────────────────

    companion object {
        const val TAG                = "ytdlp_download"
        const val KEY_TARGET_VERSION = "target_version"
        const val KEY_PROGRESS       = "progress"
        const val KEY_ERROR          = "error"

        fun enqueue(context: Context, targetVersion: String?) {
            val data = workDataOf(KEY_TARGET_VERSION to targetVersion)
            val req = OneTimeWorkRequestBuilder<YtDlpDownloadWorker>()
                .addTag(TAG)
                .setInputData(data)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, req)
        }
    }
}
