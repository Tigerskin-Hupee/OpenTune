package app.opentune.playback

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// ── Public state model ──────────────────────────────────────────────────────

enum class YtDlpState {
    UNKNOWN,       // app just launched, not yet checked
    NOT_INSTALLED, // no binary on disk
    DOWNLOADING,   // WorkManager job in progress
    READY,         // binary present and executable
    ERROR,         // last download attempt failed
}

data class YtDlpStatus(
    val state: YtDlpState = YtDlpState.UNKNOWN,
    val installedVersion: String? = null,
    val latestVersion: String? = null,
    val downloadProgress: Float = 0f,
    val error: String? = null,
) {
    val updateAvailable: Boolean
        get() = state == YtDlpState.READY
                && latestVersion != null
                && latestVersion != installedVersion
}

// ── ABI → asset name mapping ─────────────────────────────────────────────────

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

// ── Manager ──────────────────────────────────────────────────────────────────

@Singleton
class YtDlpManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) {
    val binFile: File get() = File(context.filesDir, "yt-dlp")

    val isReady: Boolean get() = binFile.exists() && binFile.canExecute()

    // Stored installed version from DataStore
    val installedVersionFlow: Flow<String?> = dataStore.data
        .map { it[KEY_VERSION] }

    // Combined status from DataStore + WorkManager work state
    val statusFlow: Flow<YtDlpStatus> = combine(
        dataStore.data,
        WorkManager.getInstance(context).getWorkInfosByTagFlow(YtDlpDownloadWorker.TAG),
    ) { prefs, workInfos ->
        val stored = prefs[KEY_VERSION]
        val latest = prefs[KEY_LATEST_VERSION]
        val workInfo = workInfos.firstOrNull()

        when {
            workInfo?.state == WorkInfo.State.RUNNING ||
            workInfo?.state == WorkInfo.State.ENQUEUED -> {
                val progress = workInfo.progress.getFloat(YtDlpDownloadWorker.KEY_PROGRESS, 0f)
                YtDlpStatus(
                    state = YtDlpState.DOWNLOADING,
                    installedVersion = stored,
                    latestVersion = latest,
                    downloadProgress = progress,
                )
            }
            workInfo?.state == WorkInfo.State.FAILED -> YtDlpStatus(
                state = YtDlpState.ERROR,
                installedVersion = stored,
                latestVersion = latest,
                error = workInfo.outputData.getString(YtDlpDownloadWorker.KEY_ERROR),
            )
            isReady -> YtDlpStatus(
                state = YtDlpState.READY,
                installedVersion = stored,
                latestVersion = latest,
            )
            else -> YtDlpStatus(
                state = YtDlpState.NOT_INSTALLED,
                installedVersion = stored,
                latestVersion = latest,
            )
        }
    }

    /** Called from Application.onCreate — triggers download if not installed. */
    fun initialize() {
        if (!isReady) {
            YtDlpDownloadWorker.enqueue(context, targetVersion = null)
        }
    }

    /** Enqueue an update to a specific version (null = latest). */
    fun enqueueUpdate(version: String? = null) {
        YtDlpDownloadWorker.enqueue(context, targetVersion = version)
    }

    suspend fun saveInstalledVersion(version: String) {
        dataStore.edit { it[KEY_VERSION] = version }
    }

    suspend fun saveLatestVersion(version: String) {
        dataStore.edit { it[KEY_LATEST_VERSION] = version }
    }

    companion object {
        val KEY_VERSION         = stringPreferencesKey("ytdlp_version")
        val KEY_LATEST_VERSION  = stringPreferencesKey("ytdlp_latest_version")
    }
}
