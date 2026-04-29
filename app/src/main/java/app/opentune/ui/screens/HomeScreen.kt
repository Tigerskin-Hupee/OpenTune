package app.opentune.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import app.opentune.R
import app.opentune.ui.viewmodels.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val recentSongs by viewModel.recentSongs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (recentSongs.isEmpty()) {
                item {
                    Text(
                        text = "Search for music to get started",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            } else {
                item {
                    Text(
                        text = "Recently Played",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp),
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
