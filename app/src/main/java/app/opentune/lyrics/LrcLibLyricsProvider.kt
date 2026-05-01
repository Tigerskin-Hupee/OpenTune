package app.opentune.lyrics

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object LrcLibLyricsProvider : LyricsProvider {
    override val name = "LrcLib"
    override fun isEnabled(context: Context) = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
    ): Result<String> {
        return try {
            val url = buildString {
                append("https://lrclib.net/api/get")
                append("?track_name=").append(encode(title))
                append("&artist_name=").append(encode(artist))
                if (duration > 0) append("&duration=").append(duration)
            }
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "OpenTune/0.11 (https://github.com/tigerskin-hupee/opentune)")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                return Result.failure(Exception("HTTP ${resp.code}"))
            }
            val body = resp.body?.string() ?: return Result.failure(Exception("empty body"))
            val json = JSONObject(body)
            val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() }
            val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() }
            val lyrics = synced ?: plain ?: return Result.failure(Exception("no lyrics in response"))
            Log.d("LrcLib", "Found ${if (synced != null) "synced" else "plain"} lyrics for '$title'")
            Result.success(lyrics)
        } catch (e: Exception) {
            Log.w("LrcLib", "getLyrics('$title') failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun encode(s: String) =
        java.net.URLEncoder.encode(s, "UTF-8")
}
