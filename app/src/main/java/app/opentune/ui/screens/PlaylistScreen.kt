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
import app.opentune.ui.viewmodels.PlaylistViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    playlistId: String,
    navController: NavController,
    viewModel: PlaylistViewModel = hiltViewModel(),
) {
    LaunchedEffect(playlistId) { viewModel.load(playlistId) }

    val playlist by viewModel.playlist.collectAsState()
    val songs by viewModel.songs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        // back arrow via text fallback
                        Text("<")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(songs) { song ->
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
