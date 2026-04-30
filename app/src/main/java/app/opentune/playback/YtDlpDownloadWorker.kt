/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.playback

import android.app.Application
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
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Updates the embedded yt-dlp Python via [YoutubeDL.updateYoutubeDL]. The
 * library bundles a baseline yt-dlp inside the APK; this worker pulls newer
 * versions in-place so playback keeps working after upstream YouTube changes,
 * without an APK release.
 */
@HiltWorker
class YtDlpDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // YoutubeDL.init must already have been called from App.onCreate.
            // updateYoutubeDL is a no-op when already on latest.
            val status = YoutubeDL.getInstance().updateYoutubeDL(applicationContext as Application, UpdateChannel.STABLE)
            Result.success(workDataOf(KEY_STATUS to status?.toString().orEmpty()))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    companion object {
        const val TAG          = "ytdlp_update"
        const val PERIODIC_TAG = "ytdlp_periodic_update"
        const val KEY_STATUS   = "status"
        const val KEY_ERROR    = "error"

        fun enqueueOneShot(context: Context) {
            val req = OneTimeWorkRequestBuilder<YtDlpDownloadWorker>()
                .addTag(TAG)
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
