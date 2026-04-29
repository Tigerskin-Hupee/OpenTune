package app.opentune.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
    val dynamicColor    by viewModel.dynamicColor.collectAsState()
    val theme           by viewModel.theme.collectAsState()
    val audioQuality    by viewModel.audioQuality.collectAsState()
    val normalizeVolume by viewModel.normalizeVolume.collectAsState()
    val skipSilence     by viewModel.skipSilence.collectAsState()
    val skipDuration    by viewModel.skipDuration.collectAsState()
    val lyricsFontSize  by viewModel.lyricsFontSize.collectAsState()
    val maxCacheSize    by viewModel.maxCacheSize.collectAsState()
    val ytDlpStatus     by viewModel.ytDlpStatus.collectAsState()
    val backupInProgress by viewModel.backupInProgress.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.backupMessage.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importBackup(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {

            // ── Appearance ────────────────────────────────────────────────
            item {
                SectionHeader("Appearance")
                DropdownSettingItem(
                    title = "Theme",
                    description = "App color scheme",
                    selected = theme,
                    options = viewModel.themeOptions,
                    onSelect = viewModel::setTheme,
                )
                SwitchSettingItem(
                    title = "Dynamic Color",
                    description = "Follow wallpaper colors (Android 12+)",
                    checked = dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }

            // ── Playback ──────────────────────────────────────────────────
            item {
                SectionDivider()
                SectionHeader("Playback")
                DropdownSettingItem(
                    title = "Audio Quality",
                    description = "Stream bitrate preference",
                    selected = audioQuality,
                    options = viewModel.audioQualityOptions,
                    onSelect = viewModel::setAudioQuality,
                )
                SwitchSettingItem(
                    title = "Normalize Volume",
                    description = "Adjust volume to match loudness target",
                    checked = normalizeVolume,
                    onCheckedChange = viewModel::setNormalizeVolume,
                )
                SwitchSettingItem(
                    title = "Skip Silent Parts",
                    description = "Automatically skip silence during playback",
                    checked = skipSilence,
                    onCheckedChange = viewModel::setSkipSilence,
                )
                DropdownSettingItem(
                    title = "Skip / Rewind Duration",
                    description = "Seconds to seek on skip button tap",
                    selected = skipDuration,
                    options = viewModel.skipDurationOptions,
                    optionLabel = { "$it s" },
                    onSelect = viewModel::setSkipDuration,
                )
            }

            // ── Lyrics ────────────────────────────────────────────────────
            item {
                SectionDivider()
                SectionHeader("Lyrics")
                DropdownSettingItem(
                    title = "Lyrics Font Size",
                    description = "Text size in the lyrics view",
                    selected = lyricsFontSize,
                    options = viewModel.lyricsFontSizeOptions,
                    onSelect = viewModel::setLyricsFontSize,
                )
            }

            // ── Storage ───────────────────────────────────────────────────
            item {
                SectionDivider()
                SectionHeader("Storage")
                val cacheLabel = viewModel.cacheSizeOptions
                    .firstOrNull { it.first == maxCacheSize }?.second ?: "Unlimited"
                DropdownSettingItem(
                    title = "Max Cache Size",
                    description = "Limit disk space used for audio cache",
                    selected = maxCacheSize,
                    options = viewModel.cacheSizeOptions.map { it.first },
                    optionLabel = { bytes ->
                        viewModel.cacheSizeOptions.firstOrNull { it.first == bytes }?.second ?: "?"
                    },
                    selectedLabel = cacheLabel,
                    onSelect = viewModel::setMaxCacheSize,
                )
            }

            // ── yt-dlp ────────────────────────────────────────────────────
            item {
                SectionDivider()
                SectionHeader("yt-dlp")
                when (ytDlpStatus.state) {
                    YtDlpState.READY -> ListItem(
                        headlineContent = { Text("yt-dlp") },
                        supportingContent = {
                            val v = ytDlpStatus.installedVersion ?: "unknown"
                            val suffix = if (ytDlpStatus.updateAvailable)
                                " · update available (${ytDlpStatus.latestVersion})" else ""
                            Text("Installed: $v$suffix")
                        },
                        leadingContent = {
                            Icon(Icons.Default.CheckCircle, null,
                                tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            if (ytDlpStatus.updateAvailable) {
                                IconButton(onClick = viewModel::checkYtDlpUpdate) {
                                    Icon(Icons.Default.CloudDownload, "Update")
                                }
                            }
                        },
                    )
                    YtDlpState.DOWNLOADING -> ListItem(
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
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        },
                    )
                    YtDlpState.ERROR -> ListItem(
                        headlineContent = { Text("yt-dlp download failed") },
                        supportingContent = { Text(ytDlpStatus.error ?: "Unknown error") },
                        leadingContent = {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                        },
                        trailingContent = {
                            IconButton(onClick = viewModel::retryYtDlpDownload) {
                                Icon(Icons.Default.Refresh, "Retry")
                            }
                        },
                    )
                    YtDlpState.NOT_INSTALLED, YtDlpState.UNKNOWN -> ListItem(
                        headlineContent = { Text("yt-dlp not installed") },
                        supportingContent = { Text("Will download on next network connection") },
                        leadingContent = { Icon(Icons.Default.CloudDownload, null) },
                        trailingContent = {
                            IconButton(onClick = viewModel::retryYtDlpDownload) {
                                Icon(Icons.Default.Refresh, "Download now")
                            }
                        },
                    )
                }
            }

            // ── Data / Backup ─────────────────────────────────────────────
            item {
                SectionDivider()
                SectionHeader("Data")
                ListItem(
                    headlineContent = { Text(stringResource(R.string.backup_restore)) },
                    supportingContent = {
                        Text("Export or import your library data (OuterTune-compatible)")
                    },
                )
                if (backupInProgress) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { exportLauncher.launch(BackupManager.buildFileName()) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Export Backup") }
                        Button(
                            onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Import Backup") }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ── Shared setting-item composables ──────────────────────────────────────────

@Composable
private fun <T> DropdownSettingItem(
    title: String,
    description: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String = { it.toString() },
    selectedLabel: String = optionLabel(selected),
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Box {
                TextButton(onClick = { expanded = true }) { Text(selectedLabel) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(optionLabel(option)) },
                            onClick = { onSelect(option); expanded = false },
                            leadingIcon = {
                                if (option == selected) {
                                    Icon(Icons.Default.Check, null,
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                        )
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable { expanded = true },
    )
}

@Composable
private fun SwitchSettingItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}
