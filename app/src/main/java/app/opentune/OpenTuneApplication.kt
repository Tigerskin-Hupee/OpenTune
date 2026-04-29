package app.opentune

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.opentune.playback.YtDlpManager
import app.opentune.playback.YtDlpUpdateChecker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OpenTuneApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var ytDlpManager: YtDlpManager
    @Inject lateinit var ytDlpUpdateChecker: YtDlpUpdateChecker

    override fun onCreate() {
        super.onCreate()
        // Ensure binary is present; no-op if already installed
        ytDlpManager.initialize()
        // Silently check for updates in background (non-blocking)
        ytDlpUpdateChecker.checkInBackground()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
