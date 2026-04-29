package app.opentune.ui.viewmodels

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.opentune.db.entities.Song
import app.opentune.playback.MusicService
import app.opentune.playback.StreamingDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton controller that bridges the UI to MusicService's ExoPlayer instance.
 *
 * Binding lifecycle is driven by MainActivity via [setBinder]. All player operations
 * suspend on [_binder] until the service is connected, so callers never need to
 * worry about timing. This follows ViTune's direct-Binder approach, which is simpler
 * and more reliable than MediaController.buildAsync().
 */
@OptIn(UnstableApi::class)
@Singleton
class PlayerController @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _binder = MutableStateFlow<MusicService.Binder?>(null)

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val playerListener = object : Player.Listener {
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
    }

    /** Called by MainActivity when the service connection state changes. */
    fun setBinder(binder: MusicService.Binder?) {
        _binder.value?.player?.removeListener(playerListener)
        _binder.value = binder
        binder?.player?.addListener(playerListener)
    }

    /** Waits for the service binder if not yet available, then returns it. */
    private suspend fun awaitBinder(): MusicService.Binder =
        _binder.value ?: _binder.first { it != null }!!

    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(StreamingDataSource.streamUri(id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtworkUri(thumbnailUrl?.let { Uri.parse(it) })
                    .build()
            )
            .build()

    fun play(songs: List<Song>, startIndex: Int) {
        _currentSong.value = songs.getOrNull(startIndex)
        scope.launch {
            val player = awaitBinder().player
            player.setMediaItems(songs.map { it.toMediaItem() }, startIndex, 0L)
            player.prepare()
            player.play()
        }
    }

    fun togglePlayPause() {
        scope.launch {
            val player = awaitBinder().player
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    fun skipNext() = scope.launch { awaitBinder().player.seekToNextMediaItem() }
    fun skipPrevious() = scope.launch { awaitBinder().player.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = scope.launch { awaitBinder().player.seekTo(ms) }
}
