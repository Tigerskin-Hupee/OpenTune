package app.opentune.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import app.opentune.R
import app.opentune.innertube.models.MusicItem
import app.opentune.ui.viewmodels.SearchViewModel
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val history by viewModel.searchHistory.collectAsState()
    val searching by viewModel.searching.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::onQueryChanged,
                        placeholder = { Text(stringResource(R.string.search)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotBlank()) {
                                IconButton(onClick = { viewModel.onQueryChanged("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            when {
                query.isBlank() -> {
                    if (history.isNotEmpty()) {
                        item {
                            Text(
                                "Recent searches",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(history) { entry ->
                            ListItem(
                                headlineContent = { Text(entry.query) },
                                leadingContent = {
                                    Icon(Icons.Default.History, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.onQueryChanged(entry.query) },
                            )
                        }
                    } else {
                        item {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 64.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Search for songs, albums, artists and playlists",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                searching -> {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                results.isEmpty() -> {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 64.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No results for \"$query\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                else -> {
                    items(results) { item ->
                        SearchResultItem(item, onSongClick = { viewModel.playSong(it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(item: MusicItem, onSongClick: (MusicItem.Song) -> Unit) {
    when (item) {
        is MusicItem.Song -> SongListItem(
            title = item.title,
            subtitle = buildString {
                if (item.artists.isNotBlank()) append(item.artists)
                item.album?.let { if (isNotEmpty()) append(" · "); append(it) }
                item.durationText?.let { if (isNotEmpty()) append(" · "); append(it) }
            },
            thumbnailUrl = item.thumbnailUrl,
            onClick = { onSongClick(item) },
        )

        is MusicItem.Album -> ListItem(
            headlineContent = { Text(item.title, maxLines = 1) },
            supportingContent = {
                Text(listOfNotNull(item.artists, item.year).joinToString(" · "), maxLines = 1)
            },
            leadingContent = {
                AsyncImage(
                    model = item.thumbnailUrl, contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                )
            },
            trailingContent = {
                Text("Album", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
        )

        is MusicItem.Artist -> ListItem(
            headlineContent = { Text(item.name, maxLines = 1) },
            leadingContent = {
                AsyncImage(
                    model = item.thumbnailUrl, contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(CircleShape),
                )
            },
            trailingContent = {
                Text("Artist", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
        )

        is MusicItem.Playlist -> ListItem(
            headlineContent = { Text(item.title, maxLines = 1) },
            supportingContent = { item.author?.let { Text(it, maxLines = 1) } },
            leadingContent = {
                AsyncImage(
                    model = item.thumbnailUrl, contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                )
            },
            trailingContent = {
                Text("Playlist", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
        )
    }
}
