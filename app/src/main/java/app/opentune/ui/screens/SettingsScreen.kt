package app.opentune.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.opentune.R
import app.opentune.backup.BackupManager
import app.opentune.playback.YtDlpState
import app.opentune.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val dynamicColor by viewModel.dynamicColor.collectAsState()
    val audioQuality by viewModel.audioQuality.collectAsState()
    val ytDlpStatus by viewModel.ytDlpStatus.collectAsState()
    val backupInProgress by viewModel.backupInProgress.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.backupMessage.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    // Export: create a new .backup file
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }

    // Import: open an existing .backup file
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importBackup(it) } }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings)) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── Appearance ────────────────────────────────────────────────
            item {
                SectionHeader("Appearance")
                ListItem(
                    headlineContent = { Text("Dynamic Color") },
                    supportingContent = { Text("Use wallpaper colors (Android 12+)") },
                    trailingContent = {
                        Switch(checked = dynamicColor, onCheckedChange = viewModel::setDynamicColor)
                    },
                )
            }

            // ── Playback ──────────────────────────────────────────────────
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SectionHeader("Playback")
                AudioQualityItem(
                    selected = audioQuality,
                    options = viewModel.audioQualityOptions,
                    onSelect = viewModel::setAudioQuality,
                )
            }

            // ── yt-dlp ────────────────────────────────────────────────────
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SectionHeader("yt-dlp")

                when (ytDlpStatus.state) {
                    YtDlpState.READY -> {
                        ListItem(
                            headlineContent = { Text("yt-dlp") },
                            supportingContent = {
                                val version = ytDlpStatus.installedVersion ?: "unknown"
                                val latest  = ytDlpStatus.latestVersion
                                val suffix  = if (ytDlpStatus.updateAvailable) " · update available ($latest)" else ""
                                Text("Installed: $version$suffix")
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                if (ytDlpStatus.updateAvailable) {
                                    IconButton(onClick = viewModel::checkYtDlpUpdate) {
                                        Icon(Icons.Default.CloudDownload, contentDescription = "Update")
                                    }
                                }
                            },
                        )
                    }

                    YtDlpState.DOWNLOADING -> {
                        ListItem(
                            headlineContent = { Text("Downloading yt-dlp…") },
                            supportingContent = {
                                Column {
                                    Text("${(ytDlpStatus.downloadProgress * 100).toInt()}%")
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { ytDlpStatus.downloadProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            },
                            leadingContent = {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            },
                        )
                    }

                    YtDlpState.ERROR -> {
                        ListItem(
                            headlineContent = { Text("yt-dlp download failed") },
                            supportingContent = { Text(ytDlpStatus.error ?: "Unknown error") },
                            leadingContent = {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = viewModel::retryYtDlpDownload) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Retry")
                                }
                            },
                        )
                    }

                    YtDlpState.NOT_INSTALLED, YtDlpState.UNKNOWN -> {
                        ListItem(
                            headlineContent = { Text("yt-dlp not installed") },
                            supportingContent = { Text("Will download on next network connection") },
                            leadingContent = {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                            },
                            trailingContent = {
                                IconButton(onClick = viewModel::retryYtDlpDownload) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Download now")
                                }
                            },
                        )
                    }
                }
            }

            // ── Data / Backup ─────────────────────────────────────────────
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                SectionHeader("Data")
                ListItem(
                    headlineContent = { Text(stringResource(R.string.backup_restore)) },
                    supportingContent = { Text("Export or import your library data (OuterTune-compatible)") },
                )
                if (backupInProgress) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Spacer(Modifier.height(8.dp))
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { exportLauncher.launch(BackupManager.buildFileName()) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Export Backup")
                        }
                        Button(
                            onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Import Backup")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun AudioQualityItem(
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Audio Quality") },
        supportingContent = { Text(selected) },
        trailingContent = {
            Box {
                TextButton(onClick = { expanded = true }) { Text(selected) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = { onSelect(option); expanded = false },
                            leadingIcon = {
                                if (option == selected) {
                                    Icon(
                                        androidx.compose.material.icons.Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
