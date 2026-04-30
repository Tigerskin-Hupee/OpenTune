/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.playback

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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

/**
 * Downloads (and updates) the yt-dlp binary from the official GitHub releases.
 *
 * Skips work when the installed version already matches the requested release —
 * so it is safe to enqueue on every cold start and on a periodic 24h schedule.
 */
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

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setProgress(workDataOf(KEY_PROGRESS to 0f))

            val targetVersion = inputData.getString(KEY_TARGET_VERSION)
            val release = fetchRelease(targetVersion)
            val assetName = ytDlpAssetName()
            val asset = release.assets.firstOrNull { it.name == assetName }
                ?: return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "Asset '$assetName' missing from ${release.tagName}")
                )

            manager.saveLatestVersion(release.tagName)

            val installed = manager.installedVersionFlow.first()
            if (installed == release.tagName && manager.isReady) {
                return@withContext Result.success()
            }

            val tmp = File(applicationContext.filesDir, "yt-dlp.tmp")
            downloadWithProgress(asset.browserDownloadUrl, asset.size, tmp)

            val bin = manager.binFile
            tmp.renameTo(bin)
            bin.setExecutable(true, true)

            manager.saveInstalledVersion(release.tagName)
            Result.success()
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

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
            val len = if (totalBytes > 0) totalBytes else body.contentLength()

            dest.parentFile?.mkdirs()
            var sent = 0L
            var lastPct = -1

            dest.outputStream().use { out ->
                body.byteStream().use { src ->
                    val buf = ByteArray(32_768)
                    var n: Int
                    while (src.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        sent += n
                        val pct = if (len > 0) (sent * 100 / len).toInt() else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            setProgress(workDataOf(KEY_PROGRESS to pct / 100f))
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val TAG                = "ytdlp_download"
        const val PERIODIC_TAG       = "ytdlp_periodic_update"
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

        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
                requiresBatteryNotLow = true,
            )
            val req = PeriodicWorkRequestBuilder<YtDlpDownloadWorker>(
                repeatInterval = 24, repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 6, flexTimeIntervalUnit = TimeUnit.HOURS,
            )
                .addTag(PERIODIC_TAG)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_TAG, ExistingPeriodicWorkPolicy.KEEP, req,
            )
        }
    }
}
