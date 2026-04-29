package app.opentune.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around the yt-dlp binary managed by [YtDlpManager].
 *
 * If the binary is not yet installed (still downloading) the functions return
 * a descriptive [Result.failure] so [StreamResolver] can fall back to Innertube
 * without any special-casing at the call site.
 */
@Singleton
class YtDlpHelper @Inject constructor(
    private val manager: YtDlpManager,
) {
    private val bin: File get() = manager.binFile

    val isInstalled: Boolean get() = manager.isReady

    suspend fun getStreamUrl(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            check(isInstalled) { "yt-dlp not ready (still downloading or not installed)" }
            val process = ProcessBuilder(
                bin.absolutePath,
                "--no-playlist",
                "-f", "bestaudio[ext=webm]/bestaudio",
                "--get-url",
                "https://www.youtube.com/watch?v=$videoId",
            )
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            check(exitCode == 0) { "yt-dlp exited $exitCode: ${output.take(200)}" }
            output.lines().last { it.startsWith("http") }
        }
    }

    suspend fun downloadAudio(videoId: String, outputFile: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            check(isInstalled) { "yt-dlp not ready" }
            outputFile.parentFile?.mkdirs()
            val process = ProcessBuilder(
                bin.absolutePath,
                "--no-playlist",
                "-f", "bestaudio[ext=webm]/bestaudio",
                "-o", outputFile.absolutePath,
                "https://www.youtube.com/watch?v=$videoId",
            )
                .redirectErrorStream(true)
                .start()

            process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            check(exitCode == 0) { "yt-dlp download failed with exit code $exitCode" }
            outputFile
        }
    }
}
