package app.opentune.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import app.opentune.LocalPlayerAwareWindowInsets
import app.opentune.LocalPlayerConnection
import app.opentune.constants.ThumbnailCornerRadius
import app.opentune.innertube.models.MusicItem
import app.opentune.innertube.models.asMediaMetadata
import app.opentune.playback.queues.ListQueue
import app.opentune.ui.component.button.IconButton
import app.opentune.viewmodels.OnlineBrowseViewModel
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineBrowseScreen(
    navController: NavController,
    viewModel: OnlineBrowseViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val items by viewModel.items.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val songs = items.filterIsInstance<MusicItem.Song>()
    val typeLabel = viewModel.browseType.replaceFirstChar { it.uppercase() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
    ) {
        TopAppBar(
            title = { Text(typeLabel) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                }
            },
            windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        )

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Failed to load: $error",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
            else -> LazyColumn {
                items(items) { item ->
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
                        is MusicItem.Artist -> ""
                        is MusicItem.Playlist -> item.author.orEmpty()
                    }
                    val isArtist = item is MusicItem.Artist
                    val shape = if (isArtist) CircleShape else RoundedCornerShape(ThumbnailCornerRadius)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (item is MusicItem.Song && songs.isNotEmpty()) {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = typeLabel,
                                            items = songs.map { it.asMediaMetadata() },
                                            startIndex = songs.indexOfFirst { it.id == item.id }
                                                .coerceAtLeast(0)
                                        )
                                    )
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        AsyncImage(
                            model = thumbUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .width(48.dp)
                                .aspectRatio(1f)
                                .clip(shape)
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
}
