package app.opentune.lyrics

import android.content.Context
import android.util.LruCache
import app.opentune.constants.LyricSourcePrefKey
import app.opentune.constants.LyricTrimKey
import app.opentune.constants.MultilineLrcKey
import app.opentune.db.MusicDatabase
import app.opentune.models.MediaMetadata
import app.opentune.utils.dataStore
import app.opentune.utils.get
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.utils.LrcUtils
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.parseLrc
import javax.inject.Inject

class LyricsHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase
) {
    private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)

    /**
     * Retrieve lyrics from all sources
     *
     * How lyrics are resolved are determined by PreferLocalLyrics settings key. If this is true, prioritize local lyric
     * files over all cloud providers, true is vice versa.
     *
     * Lyrics stored in the database are fetched first. If this is not available, it is resolved by other means.
     * If local lyrics are preferred, lyrics from the lrc file is fetched, and then resolve by other means.
     *
     * @param mediaMetadata Song to fetch lyrics for
     * @param database MusicDatabase connection. Database lyrics are prioritized over all sources.
     * If no database is provided, the database source is disabled
     */
    suspend fun getLyrics(mediaMetadata: MediaMetadata): SemanticLyrics? {
        val trim = context.dataStore.get(LyricTrimKey, defaultValue = false)
        val multiline = context.dataStore.get(MultilineLrcKey, defaultValue = true)

        val prefLocal = context.dataStore.get(LyricSourcePrefKey, true)

        val cached = cache.get(mediaMetadata.id)?.firstOrNull()
        if (cached != null) {
            return parseLrc(cached.lyrics, trim, multiline)
        }
        val dbLyrics = database.lyrics(mediaMetadata.id).let { it.first()?.lyrics }
        // prefer database lyrics
        if (dbLyrics != null && !prefLocal) {
            return parseLrc(dbLyrics, trim, multiline)
        }

        // otherwise local lyrics are preferred over database
        val localLyrics: SemanticLyrics? =
            getLocalLyrics(mediaMetadata, LrcUtils.LrcParserOptions(trim, multiline, "Unable to parse lyrics"))
        if (localLyrics != null) {
            return localLyrics
        }
        if (dbLyrics != null) {
            return parseLrc(dbLyrics, trim, multiline)
        }

        // Online fallback via lrclib.net (free, no auth required)
        val onlineLyrics = fetchOnlineLyrics(mediaMetadata)
        if (onlineLyrics != null) {
            database.query { upsert(app.opentune.db.entities.LyricsEntity(mediaMetadata.id, onlineLyrics)) }
            return parseLrc(onlineLyrics, trim, multiline)
        }

        return null
    }

    private suspend fun fetchOnlineLyrics(mediaMetadata: MediaMetadata): String? =
        withContext(Dispatchers.IO) {
            LrcLibLyricsProvider.getLyrics(
                id = mediaMetadata.id,
                title = mediaMetadata.title,
                artist = mediaMetadata.artists.firstOrNull()?.name.orEmpty(),
                duration = mediaMetadata.duration,
            ).getOrNull()
        }

    /**
     * Lookup lyrics from local disk (.lrc) file
     */
    private fun getLocalLyrics(
        mediaMetadata: MediaMetadata,
        parserOptions: LrcUtils.LrcParserOptions
    ): SemanticLyrics? {
        if (LocalLyricsProvider.isEnabled(context) && mediaMetadata.localPath != null) {
            return LocalLyricsProvider.getLyricsNew(
                mediaMetadata.localPath,
                parserOptions
            )
        }

        return null
    }

    companion object {
        private const val MAX_CACHE_SIZE = 3
    }
}

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)
