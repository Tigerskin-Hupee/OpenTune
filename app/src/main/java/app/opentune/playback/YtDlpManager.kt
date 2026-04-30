/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.playback

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.opentune.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class YtDlpState {
    UNKNOWN, NOT_INSTALLED, DOWNLOADING, READY, ERROR,
}

data class YtDlpStatus(
    val state: YtDlpState = YtDlpState.UNKNOWN,
    val installedVersion: String? = null,
    val latestVersion: String? = null,
    val downloadProgress: Float = 0f,
    val error: String? = null,
) {
    val updateAvailable: Boolean
        get() = state == YtDlpState.READY &&
                latestVersion != null &&
                latestVersion != installedVersion
}

internal fun ytDlpAssetName(): String {
    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    return when {
        abi.contains("arm64") || abi.contains("aarch64") -> "yt-dlp_linux_aarch64"
        abi.contains("armeabi")                          -> "yt-dlp_linux_armv7l"
        abi.contains("x86_64")                           -> "yt-dlp_linux"
        abi.contains("x86")                              -> "yt-dlp_linux_x86"
        else                                             -> "yt-dlp_linux_aarch64"
    }
}

@Singleton
class YtDlpManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val binFile: File get() = File(context.filesDir, "yt-dlp")

    val isReady: Boolean get() = binFile.exists() && binFile.canExecute()

    val installedVersionFlow: Flow<String?> = context.dataStore.data
        .map { it[KEY_VERSION] }

    val statusFlow: Flow<YtDlpStatus> = combine(
        context.dataStore.data,
        WorkManager.getInstance(context).getWorkInfosByTagFlow(YtDlpDownloadWorker.TAG),
    ) { prefs, workInfos ->
        val stored = prefs[KEY_VERSION]
        val latest = prefs[KEY_LATEST_VERSION]
        val workInfo = workInfos.firstOrNull()

        when {
            workInfo?.state == WorkInfo.State.RUNNING ||
            workInfo?.state == WorkInfo.State.ENQUEUED -> {
                val progress = workInfo.progress.getFloat(YtDlpDownloadWorker.KEY_PROGRESS, 0f)
                YtDlpStatus(YtDlpState.DOWNLOADING, stored, latest, progress)
            }
            workInfo?.state == WorkInfo.State.FAILED -> YtDlpStatus(
                YtDlpState.ERROR, stored, latest,
                error = workInfo.outputData.getString(YtDlpDownloadWorker.KEY_ERROR),
            )
            isReady -> YtDlpStatus(YtDlpState.READY, stored, latest)
            else    -> YtDlpStatus(YtDlpState.NOT_INSTALLED, stored, latest)
        }
    }

    /**
     * Trigger update check on every launch + schedule 24h periodic background check.
     * The worker no-ops if installed version already matches latest.
     */
    fun initialize() {
        YtDlpDownloadWorker.enqueue(context, targetVersion = null)
        YtDlpDownloadWorker.enqueuePeriodic(context)
    }

    fun enqueueUpdate(version: String? = null) {
        YtDlpDownloadWorker.enqueue(context, targetVersion = version)
    }

    suspend fun saveInstalledVersion(version: String) {
        context.dataStore.edit { it[KEY_VERSION] = version }
    }

    suspend fun saveLatestVersion(version: String) {
        context.dataStore.edit { it[KEY_LATEST_VERSION] = version }
    }

    companion object {
        val KEY_VERSION        = stringPreferencesKey("ytdlp_version")
        val KEY_LATEST_VERSION = stringPreferencesKey("ytdlp_latest_version")
    }
}
