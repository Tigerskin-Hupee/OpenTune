package app.opentune.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import app.opentune.LocalPlayerAwareWindowInsets
import app.opentune.LocalPlayerConnection
import app.opentune.LocalSnackbarHostState
import app.opentune.R
import app.opentune.constants.CONTENT_TYPE_LIST
import app.opentune.constants.ListItemHeight
import app.opentune.constants.ListThumbnailSize
import app.opentune.constants.SwipeToQueueKey
import app.opentune.constants.ThumbnailCornerRadius
import app.opentune.db.entities.Album
import app.opentune.db.entities.Artist
import app.opentune.db.entities.Playlist
import app.opentune.db.entities.Song
import app.opentune.innertube.models.MusicItem
import app.opentune.innertube.models.asMediaMetadata
import app.opentune.models.toMediaMetadata
import app.opentune.playback.queues.ListQueue
import app.opentune.ui.component.ChipsRow
import app.opentune.ui.component.EmptyPlaceholder
import app.opentune.ui.component.LazyColumnScrollbar
import app.opentune.ui.component.items.AlbumListItem
import app.opentune.ui.component.items.ArtistListItem
import app.opentune.ui.component.items.PlaylistListItem
import app.opentune.ui.component.items.SongListItem
import app.opentune.utils.rememberPreference
import app.opentune.viewmodels.LocalFilter
import app.opentune.viewmodels.LocalSearchViewModel
import app.opentune.viewmodels.OnlineSearchViewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

@OptIn(FlowPreview::class)
@Composable
fun LocalSearchScreen(
    query: String,
    navController: NavController,
    onDismiss: () -> Unit,
    viewModel: LocalSearchViewModel = hiltViewModel(),
    onlineViewModel: OnlineSearchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val searchFilter by viewModel.filter.collectAsState()
    val result by viewModel.result.collectAsState()

    val onlineResults by onlineViewModel.result.collectAsState()
    val onlineLoading by onlineViewModel.isLoading.collectAsState()

    LaunchedEffect(query) {
        onlineViewModel.query.value = query
    }

    val lazyListState = rememberLazyListState()
    val snackbarHostState = LocalSnackbarHostState.current

    LaunchedEffect(Unit) {
        snapshotFlow { lazyListState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect {
                keyboardController?.hide()
            }
    }

    LaunchedEffect(query) {
        snapshotFlow { query }.debounce { 300L }.collectLatest {
            viewModel.query.value = query
        }
    }

    Column(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    ) {
        ChipsRow(
            chips = listOf(
                LocalFilter.ALL to stringResource(R.string.filter_all),
                LocalFilter.SONG to stringResource(R.string.filter_songs),
                LocalFilter.ALBUM to stringResource(R.string.filter_albums),
                LocalFilter.ARTIST to stringResource(R.string.filter_artists),
                LocalFilter.PLAYLIST to stringResource(R.string.filter_playlists)
            ),
            currentValue = searchFilter,
            onValueUpdate = { viewModel.filter.value = it }
        )

        LazyColumn(
            state = lazyListState,
//            contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom + WindowInsetsSides.Start).asPaddingValues(),
            modifier = Modifier.weight(1f)
        ) {
            result.map.forEach { (filter, items) ->
                if (result.filter == LocalFilter.ALL) {
                    item(
                        key = filter
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ListItemHeight)
                                .clickable { viewModel.filter.value = filter }
                                .padding(start = 12.dp, end = 18.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    when (filter) {
                                        LocalFilter.SONG -> R.string.filter_songs
                                        LocalFilter.ALBUM -> R.string.filter_albums
                                        LocalFilter.ARTIST -> R.string.filter_artists
                                        LocalFilter.PLAYLIST -> R.string.filter_playlists
                                        LocalFilter.ALL -> error("")
                                    }
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f)
                            )

                            Icon(
                                Icons.AutoMirrored.Rounded.NavigateNext,
                                contentDescription = null
                            )
                        }
                    }
                }

                val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
                items(
                    items = items,
                    key = { it.id },
                    contentType = { CONTENT_TYPE_LIST }
                ) { item ->
                    when (item) {
                        is Song -> {
                            SongListItem(
                                song = item,
                                navController = navController,
                                snackbarHostState = snackbarHostState,

                                isActive = item.id == mediaMetadata?.id,
                                isPlaying = isPlaying,
                                inSelectMode = false,
                                isSelected = false,
                                onSelectedChange = { },
                                swipeEnabled = swipeEnabled,

                                thumbnailSize = thumbnailSize,
                                onPlay = {
                                    val songs = result.map
                                        .getOrDefault(LocalFilter.SONG, emptyList())
                                        .filterIsInstance<Song>()
                                        .map { it.toMediaMetadata() }
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = "${context.getString(R.string.queue_searched_songs_ot)} $query",
                                            items = songs,
                                            startIndex = songs.indexOfFirst { it.id == item.id }
                                        ))
                                },
                                modifier = Modifier.animateItem()
                            )
                        }

                        is Album -> AlbumListItem(
                            album = item,
                            isActive = item.id == mediaMetadata?.album?.id,
                            isPlaying = isPlaying,
                            modifier = Modifier
                                .clickable {
                                    onDismiss()
                                    navController.navigate("album/${item.id}")
                                }
                                .animateItem()
                        )

                        is Artist -> ArtistListItem(
                            artist = item,
                            modifier = Modifier
                                .clickable {
                                    onDismiss()
                                    navController.navigate("artist/${item.id}")
                                }
                                .animateItem()
                        )

                        is Playlist -> PlaylistListItem(
                            playlist = item,
                            modifier = Modifier
                                .clickable {
                                    onDismiss()
                                    navController.navigate("local_playlist/${item.id}")
                                }
                                .animateItem()
                        )
                    }
                }
            }

            if (result.query.isNotEmpty() && result.map.isEmpty()) {
                item(key = "no_result") {
                    EmptyPlaceholder(
                        icon = Icons.Rounded.Search,
                        text = stringResource(R.string.no_results_found),
                        modifier = Modifier.animateItem()
                    )
                }
            }

            // ── Online results from YouTube Music ──────────────────────────────
            if (query.isNotBlank()) {
                item(key = "online_header") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.online_search_results),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (onlineLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }

                val onlineSongs = onlineResults.filterIsInstance<MusicItem.Song>()
                items(
                    items = onlineResults,
                    key = { "online_${when (it) {
                        is MusicItem.Song -> it.id
                        is MusicItem.Album -> it.browseId
                        is MusicItem.Artist -> it.browseId
                        is MusicItem.Playlist -> it.browseId
                    }}" }
                ) { item ->
                    val thumbUrl = when (item) {
                        is MusicItem.Song -> item.thumbnailUrl
                        is MusicItem.Album -> item.thumbnailUrl
                        is MusicItem.Artist -> item.thumbnailUrl
                        is MusicItem.Playlist -> item.thumbnailUrl
                    }
                    val title = when (item) {
                        is MusicItem.Song -> item.title
                        is MusicItem.Album -> item.title
                        is MusicItem.Artist -> item.name
                        is MusicItem.Playlist -> item.title
                    }
                    val subtitle = when (item) {
                        is MusicItem.Song -> item.artists
                        is MusicItem.Album -> item.artists.orEmpty()
                        is MusicItem.Artist -> stringResource(R.string.filter_artists)
                        is MusicItem.Playlist -> item.author.orEmpty()
                    }
                    val isArtist = item is MusicItem.Artist
                    val thumbShape = if (isArtist) CircleShape else RoundedCornerShape(ThumbnailCornerRadius)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (item is MusicItem.Song) {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = "${context.getString(R.string.queue_searched_songs_ot)} $query",
                                            items = onlineSongs.map { it.asMediaMetadata() },
                                            startIndex = onlineSongs.indexOfFirst { it.id == item.id }
                                                .coerceAtLeast(0)
                                        )
                                    )
                                    onDismiss()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .animateItem()
                    ) {
                        AsyncImage(
                            model = thumbUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .width(48.dp)
                                .aspectRatio(1f)
                                .clip(thumbShape)
                        )
                        Column(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .weight(1f)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    LazyColumnScrollbar(
        state = lazyListState,
    )

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                .align(Alignment.BottomCenter)
        )
    }
}