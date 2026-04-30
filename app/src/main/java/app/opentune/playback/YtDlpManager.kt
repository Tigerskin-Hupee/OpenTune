/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.playback

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.yausername.youtubedl_android.YoutubeDL
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class YtDlpState {
    UNKNOWN, INITIALISING, READY, UPDATING, ERROR,
}

data class YtDlpStatus(
    val state: YtDlpState = YtDlpState.UNKNOWN,
    val installedVersion: String? = null,
    val error: String? = null,
)

/**
 * Tracks the lifecycle of the yt-dlp library bundled via youtubedl-android.
 *
 * The library ships yt-dlp inside a Python interpreter compiled as a native
 * library (.so), which Android's SELinux policy permits to execute from
 * `nativeLibraryDir` even on API 30+. On first launch [com.yausername.youtubedl_android.YoutubeDL.init]
 * extracts those binaries; subsequent launches are fast.
 */
@Singleton
class YtDlpManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ready = MutableStateFlow(false)
    private val initError = MutableStateFlow<String?>(null)

    val isReady: Boolean get() = ready.value

    fun onLibraryReady() {
        ready.value = true
        initError.value = null
    }

    fun onLibraryError(t: Throwable) {
        ready.value = false
        initError.value = t.message ?: t.javaClass.simpleName
    }

    val installedVersionFlow: Flow<String?> = ready.map { isReady ->
        if (isReady) runCatching { YoutubeDL.getInstance().version(context) }.getOrNull() else null
    }

    val statusFlow: Flow<YtDlpStatus> = combine(
        ready,
        initError,
        WorkManager.getInstance(context).getWorkInfosByTagFlow(YtDlpDownloadWorker.TAG),
    ) { ready, err, workInfos ->
        val workInfo = workInfos.firstOrNull()
        val version = if (ready) runCatching { YoutubeDL.getInstance().version(context) }.getOrNull() else null
        when {
            err != null -> YtDlpStatus(YtDlpState.ERROR, version, err)
            workInfo?.state == WorkInfo.State.RUNNING -> YtDlpStatus(YtDlpState.UPDATING, version)
            ready -> YtDlpStatus(YtDlpState.READY, version)
            else -> YtDlpStatus(YtDlpState.INITIALISING, version)
        }
    }

    fun scheduleUpdates() {
        YtDlpDownloadWorker.enqueuePeriodic(context)
    }

    fun enqueueUpdate() {
        YtDlpDownloadWorker.enqueueOneShot(context)
    }
}
