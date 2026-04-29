package app.opentune.playback

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import app.opentune.db.MusicRepository
import app.opentune.db.entities.Song
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MusicRepository,
    private val ytDlpHelper: YtDlpHelper,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val videoId = inputData.getString(KEY_VIDEO_ID) ?: return Result.failure()

        repository.updateDownloadState(videoId, Song.DownloadState.DOWNLOADING)

        val outputFile = File(applicationContext.getExternalFilesDir("music"), "$videoId.opus")
        return ytDlpHelper.downloadAudio(videoId, outputFile).fold(
            onSuccess = {
                repository.updateDownloadState(videoId, Song.DownloadState.DOWNLOADED, it.absolutePath)
                Result.success()
            },
            onFailure = {
                repository.updateDownloadState(videoId, Song.DownloadState.NOT_DOWNLOADED)
                Result.retry()
            },
        )
    }

    companion object {
        const val KEY_VIDEO_ID = "video_id"

        fun enqueue(context: Context, videoId: String) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_VIDEO_ID to videoId))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "download_$videoId",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
