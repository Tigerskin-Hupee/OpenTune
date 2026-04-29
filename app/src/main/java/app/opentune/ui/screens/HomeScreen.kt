package app.opentune.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import app.opentune.R
import app.opentune.innertube.models.MusicItem
import app.opentune.ui.navigation.Screen
import app.opentune.ui.viewmodels.HomeViewModel
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val recentSongs by viewModel.recentSongs.collectAsState()
    val homeSections by viewModel.homeSections.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (error != null && !loading) {
                        IconButton(onClick = viewModel::loadHomeFeed) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (loading && homeSections.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            // ── Online sections (Quick picks, New releases, …) ──────────────
            if (homeSections.isNotEmpty()) {
                items(homeSections) { section ->
                    HomeSectionRow(section, onSongClick = { viewModel.play(it) })
                }
            } else if (error != null) {
                // Fallback: show local recently played
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Recently Played",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(recentSongs) { song ->
                    SongListItem(
                        title = song.title,
                        subtitle = song.albumName ?: "",
                        thumbnailUrl = song.thumbnailUrl,
                        onClick = { viewModel.play(song) },
                    )
                }
                item {
                    if (recentSongs.isEmpty()) {
                        Text(
                            text = "Search for music to get started",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else if (!loading && homeSections.isEmpty() && recentSongs.isNotEmpty()) {
                item {
                    Text(
                        "Recently Played",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(recentSongs) { song ->
                    SongListItem(
                        title = song.title,
                        subtitle = song.albumName ?: "",
                        thumbnailUrl = song.thumbnailUrl,
                        onClick = { viewModel.play(song) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSectionRow(
    section: app.opentune.innertube.models.HomeSection,
    onSongClick: (MusicItem.Song) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(section.items) { item ->
                when (item) {
                    is MusicItem.Song -> SongCard(item, onClick = { onSongClick(item) })
                    is MusicItem.Album -> MediaCard(
                        title = item.title,
                        subtitle = item.artists ?: item.year ?: "",
                        thumbnailUrl = item.thumbnailUrl,
                        onClick = {},
                    )
                    is MusicItem.Playlist -> MediaCard(
                        title = item.title,
                        subtitle = item.author ?: "",
                        thumbnailUrl = item.thumbnailUrl,
                        onClick = {},
                    )
                    is MusicItem.Artist -> MediaCard(
                        title = item.name,
                        subtitle = "Artist",
                        thumbnailUrl = item.thumbnailUrl,
                        onClick = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun SongCard(item: MusicItem.Song, onClick: () -> Unit) {
    MediaCard(
        title = item.title,
        subtitle = item.artists,
        thumbnailUrl = item.thumbnailUrl,
        onClick = onClick,
    )
}

@Composable
private fun MediaCard(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = thumbnailUrl,
            contentDescription = null,
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
