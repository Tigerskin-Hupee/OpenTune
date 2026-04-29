package app.opentune.playback

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps the yt-dlp binary bundled in the app's private files directory.
 * The binary is downloaded on first launch via YtDlpUpdater.
 */
@Singleton
class YtDlpHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ytDlpBin: File
        get() = File(context.filesDir, "yt-dlp")

    val isInstalled: Boolean get() = ytDlpBin.exists() && ytDlpBin.canExecute()

    suspend fun getStreamUrl(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            check(isInstalled) { "yt-dlp binary not found" }
            val process = ProcessBuilder(
                ytDlpBin.absolutePath,
                "--no-playlist",
                "-f", "bestaudio",
                "--get-url",
                "https://www.youtube.com/watch?v=$videoId"
            )
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            check(exitCode == 0) { "yt-dlp exited with code $exitCode: $output" }
            output.lines().last { it.startsWith("http") }
        }
    }

    suspend fun downloadAudio(videoId: String, outputFile: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            check(isInstalled) { "yt-dlp binary not found" }
            outputFile.parentFile?.mkdirs()
            val process = ProcessBuilder(
                ytDlpBin.absolutePath,
                "--no-playlist",
                "-f", "bestaudio",
                "-o", outputFile.absolutePath,
                "https://www.youtube.com/watch?v=$videoId"
            )
                .redirectErrorStream(true)
                .start()

            process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            check(exitCode == 0) { "yt-dlp download failed with code $exitCode" }
            outputFile
        }
    }

    fun installBinary(sourceBytes: ByteArray) {
        ytDlpBin.writeBytes(sourceBytes)
        ytDlpBin.setExecutable(true, true)
    }
}
