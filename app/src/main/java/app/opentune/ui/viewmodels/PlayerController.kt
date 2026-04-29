package app.opentune.ui.viewmodels

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.opentune.db.entities.Song
import app.opentune.playback.MusicService
import app.opentune.playback.StreamingDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@UnstableApi
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Completes when MediaController has successfully connected to MusicService.
    // All player operations await this so they never execute against a disconnected controller.
    private val controllerDeferred = CompletableDeferred<MediaController>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connectAttempts = 0

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    init {
        // Post to the main looper so MediaController.Builder.buildAsync() is always called on
        // the main thread, regardless of which thread Hilt creates this singleton on.
        Handler(Looper.getMainLooper()).post { connectController() }
    }

    private fun connectController() {
        connectAttempts++
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        // ContextCompat.getMainExecutor ensures the listener runs on the main thread so all
        // MediaController accesses happen on the correct thread (as required by Media3).
        future.addListener({
            try {
                val mc = future.get()
                controllerDeferred.complete(mc)
                mc.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _isPlaying.value = isPlaying
                    }

                    override fun onEvents(player: Player, events: Player.Events) {
                        if (events.containsAny(
                                Player.EVENT_PLAYBACK_STATE_CHANGED,
                                Player.EVENT_POSITION_DISCONTINUITY,
                                Player.EVENT_MEDIA_ITEM_TRANSITION,
                            )
                        ) {
                            _position.value = player.currentPosition
                            _duration.value = player.duration.coerceAtLeast(0L)
                        }
                    }
                })
            } catch (e: Exception) {
                // Service may not be ready yet; retry with exponential back-off (max 10 tries).
                if (connectAttempts < 10 && !controllerDeferred.isCompleted) {
                    scope.launch {
                        delay(500L * connectAttempts)
                        connectController()
                    }
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(StreamingDataSource.streamUri(id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtworkUri(thumbnailUrl?.let { android.net.Uri.parse(it) })
                    .build()
            )
            .build()

    fun play(songs: List<Song>, startIndex: Int) {
        _currentSong.value = songs.getOrNull(startIndex)
        scope.launch {
            val mc = controllerDeferred.await()
            mc.setMediaItems(songs.map { it.toMediaItem() }, startIndex, 0L)
            mc.prepare()
            mc.play()
        }
    }

    fun togglePlayPause() {
        scope.launch {
            val mc = controllerDeferred.await()
            if (mc.isPlaying) mc.pause() else mc.play()
        }
    }

    fun skipNext() {
        scope.launch { controllerDeferred.await().seekToNextMediaItem() }
    }

    fun skipPrevious() {
        scope.launch { controllerDeferred.await().seekToPreviousMediaItem() }
    }

    fun seekTo(ms: Long) {
        scope.launch { controllerDeferred.await().seekTo(ms) }
    }
}

