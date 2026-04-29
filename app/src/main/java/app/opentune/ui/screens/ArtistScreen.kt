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
import app.opentune.ui.viewmodels.ArtistViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    artistId: String,
    navController: NavController,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    LaunchedEffect(artistId) { viewModel.load(artistId) }

    val artist by viewModel.artist.collectAsState()
    val songs by viewModel.songs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(artist?.name ?: "") },
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
