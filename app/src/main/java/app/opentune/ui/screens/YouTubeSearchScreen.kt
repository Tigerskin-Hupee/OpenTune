/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import app.opentune.LocalPlayerConnection
import app.opentune.R
import app.opentune.innertube.YtMusicAlbum
import app.opentune.innertube.YtMusicArtist
import app.opentune.innertube.YtMusicTrack
import app.opentune.models.MediaMetadata
import app.opentune.playback.queues.ListQueue
import app.opentune.viewmodels.SearchTab
import app.opentune.viewmodels.YouTubeSearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeSearchScreen(
    navController: NavController,
    initialQuery: String = "",
    viewModel: YouTubeSearchViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current
    val songResults by viewModel.songResults.collectAsState()
    val artistResults by viewModel.artistResults.collectAsState()
    val albumResults by viewModel.albumResults.collectAsState()
    val playlistResults by viewModel.playlistResults.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    var queryText by remember { mutableStateOf(initialQuery) }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            viewModel.query.value = initialQuery
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
    ) {
        TopAppBar(
            title = {
                OutlinedTextField(
                    value = queryText,
                    onValueChange = {
                        queryText = it
                        viewModel.query.value = it
                    },
                    placeholder = { Text(stringResource(R.string.search)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50),
                )
            },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                }
            }
        )

        val tabs = listOf(
            stringResource(R.string.songs),
            stringResource(R.string.artists),
            stringResource(R.string.albums),
            stringResource(R.string.playlists),
        )
        TabRow(selectedTabIndex = selectedTab.ordinal) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab.ordinal == index,
                    onClick = { viewModel.selectedTab.value = SearchTab.entries[index] },
                    text = { Text(title) },
                )
            }
        }

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Search failed: $error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
            else -> when (selectedTab) {
                SearchTab.SONGS -> {
                    if (songResults.isEmpty() && queryText.isNotBlank()) {
                        EmptyResults()
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(songResults) { track ->
                                YouTubeTrackItem(
                                    track = track,
                                    onClick = {
                                        keyboard?.hide()
                                        playerConnection?.playQueue(
                                            ListQueue(
                                                title = "YouTube Search",
                                                items = songResults.map { it.toMediaMetadata() },
                                                startIndex = songResults.indexOf(track),
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                SearchTab.ARTISTS -> {
                    if (artistResults.isEmpty() && queryText.isNotBlank()) {
                        EmptyResults()
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(artistResults) { artist ->
                                YouTubeArtistItem(
                                    artist = artist,
                                    onClick = {
                                        keyboard?.hide()
                                        navController.navigate(
                                            "youtube_search?q=${java.net.URLEncoder.encode(artist.name, "UTF-8")}"
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                SearchTab.ALBUMS -> {
                    if (albumResults.isEmpty() && queryText.isNotBlank()) {
                        EmptyResults()
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(albumResults) { album ->
                                YouTubeAlbumItem(
                                    album = album,
                                    onClick = {
                                        keyboard?.hide()
                                        viewModel.loadPlaylistSongs(album.playlistId, "${album.title} ${album.artistName}".trim()) { songs, error ->
                                            if (songs.isNotEmpty()) {
                                                playerConnection?.playQueue(
                                                    ListQueue(
                                                        title = album.title,
                                                        items = songs.map { it.toMediaMetadata() },
                                                    )
                                                )
                                            } else {
                                                Toast.makeText(context, error ?: "No songs found", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                SearchTab.PLAYLISTS -> {
                    if (playlistResults.isEmpty() && queryText.isNotBlank()) {
                        EmptyResults()
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(playlistResults) { playlist ->
                                YouTubeAlbumItem(
                                    album = playlist,
                                    onClick = {
                                        keyboard?.hide()
                                        viewModel.loadPlaylistSongs(playlist.playlistId, "${playlist.title} ${playlist.artistName}".trim()) { songs, error ->
                                            if (songs.isNotEmpty()) {
                                                playerConnection?.playQueue(
                                                    ListQueue(
                                                        title = playlist.title,
                                                        items = songs.map { it.toMediaMetadata() },
                                                    )
                                                )
                                            } else {
                                                Toast.makeText(context, error ?: "No songs found", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyResults() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.no_results_found),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun YouTubeTrackItem(track: YtMusicTrack, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (track.artistName.isNotBlank()) {
                Text(
                    track.artistName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun YouTubeArtistItem(artist: YtMusicArtist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (artist.thumbnailUrl != null) {
            AsyncImage(
                model = artist.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.size(52.dp).clip(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier.size(52.dp).clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(artist.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun YouTubeAlbumItem(album: YtMusicAlbum, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (album.thumbnailUrl != null) {
            AsyncImage(
                model = album.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Album, contentDescription = null, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(album.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (album.artistName.isNotBlank()) {
                Text(
                    album.artistName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.Album,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun YtMusicTrack.toMediaMetadata() = MediaMetadata(
    id = videoId,
    title = title,
    artists = listOf(MediaMetadata.Artist(id = null, name = artistName)),
    duration = 0,
    thumbnailUrl = thumbnailUrl,
    genre = null,
)
