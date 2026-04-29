package app.opentune.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.opentune.R
import app.opentune.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val dynamicColor by viewModel.dynamicColor.collectAsState()
    val audioQuality by viewModel.audioQuality.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings)) })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text("Appearance", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
                ListItem(
                    headlineContent = { Text("Dynamic Color") },
                    supportingContent = { Text("Use wallpaper colors (Android 12+)") },
                    trailingContent = {
                        Switch(checked = dynamicColor, onCheckedChange = viewModel::setDynamicColor)
                    },
                )
            }
            item {
                HorizontalDivider()
                Text("Playback", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
                ListItem(
                    headlineContent = { Text("Audio Quality") },
                    supportingContent = { Text(audioQuality) },
                )
            }
            item {
                HorizontalDivider()
                Text("Data", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.backup_restore)) },
                    supportingContent = { Text("Export or import your library data") },
                )
            }
        }
    }
}
