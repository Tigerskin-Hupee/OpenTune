package app.opentune.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import app.opentune.ui.viewmodels.AlbumViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    albumId: String,
    navController: NavController,
    viewModel: AlbumViewModel = hiltViewModel(),
) {
    LaunchedEffect(albumId) { viewModel.load(albumId) }

    val album by viewModel.album.collectAsState()
    val songs by viewModel.songs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(album?.title ?: "") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) { Text("<") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(songs.withIndex().toList()) { (index, song) ->
                SongListItem(
                    title = "${index + 1}. ${song.title}",
                    subtitle = formatDuration(song.duration.toLong() * 1000),
                    thumbnailUrl = null,
                    onClick = { viewModel.play(songs, index) },
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
