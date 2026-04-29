package app.opentune.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import app.opentune.MainActivity
import app.opentune.db.MusicRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class MusicService : MediaLibraryService() {

    @Inject lateinit var repository: MusicRepository
    @Inject lateinit var streamingDataSource: StreamingDataSource

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(OkHttpClient())
        val defaultDataSourceFactory = DefaultDataSource.Factory(this, okHttpDataSourceFactory)
        val resolvingFactory = streamingDataSource.createFactory(defaultDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .build()

        val activityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaLibrarySession.Builder(this, player, BrowseCallback())
            .setSessionActivity(activityIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        mediaSession

    override fun onDestroy() {
        mediaSession.release()
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    // ── Android Auto browse tree ──────────────────────────────────────────────

    private inner class BrowseCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(
                browsableItem(id = "root", title = "OpenTune"),
                params,
            )
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                val items: List<MediaItem> = when (parentId) {
                    "root" -> listOf(
                        browsableItem("recently_played", "Recently Played"),
                        browsableItem("liked_songs", "Liked Songs"),
                        browsableItem("all_songs", "All Songs"),
                    )
                    "recently_played" ->
                        repository.getAllSongs().first().take(50).map { it.toAutoMediaItem() }
                    "liked_songs" ->
                        repository.getLikedSongs().first().take(50).map { it.toAutoMediaItem() }
                    "all_songs" ->
                        repository.getAllSongs().first().take(100).map { it.toAutoMediaItem() }
                    else -> emptyList()
                }
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            return future
        }
    }

    private fun browsableItem(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

    private fun app.opentune.db.entities.Song.toAutoMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri("opentune://stream/$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(albumName)
                    .setArtworkUri(thumbnailUrl?.let { android.net.Uri.parse(it) })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()
}
