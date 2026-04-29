package app.opentune.ui.viewmodels

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.backup.BackupManager
import app.opentune.playback.YtDlpManager
import app.opentune.playback.YtDlpStatus
import app.opentune.prefs.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val ytDlpManager: YtDlpManager,
    private val backupManager: BackupManager,
) : ViewModel() {

    // ── Appearance ────────────────────────────────────────────────────────────

    val dynamicColor: StateFlow<Boolean> = dataStore.data
        .map { it[AppPreferences.DYNAMIC_COLOR] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val theme: StateFlow<String> = dataStore.data
        .map { it[AppPreferences.THEME] ?: "System" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "System")

    // ── Playback ──────────────────────────────────────────────────────────────

    val audioQuality: StateFlow<String> = dataStore.data
        .map { it[AppPreferences.AUDIO_QUALITY] ?: "Best" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Best")

    val normalizeVolume: StateFlow<Boolean> = dataStore.data
        .map { it[AppPreferences.NORMALIZE_VOLUME] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val skipSilence: StateFlow<Boolean> = dataStore.data
        .map { it[AppPreferences.SKIP_SILENCE] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val skipDuration: StateFlow<Int> = dataStore.data
        .map { it[AppPreferences.SKIP_DURATION] ?: 10 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 10)

    // ── Lyrics ────────────────────────────────────────────────────────────────

    val lyricsFontSize: StateFlow<String> = dataStore.data
        .map { it[AppPreferences.LYRICS_FONT_SIZE] ?: "Medium" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Medium")

    // ── Storage ───────────────────────────────────────────────────────────────

    val maxCacheSize: StateFlow<Long> = dataStore.data
        .map { it[AppPreferences.MAX_CACHE_SIZE] ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    // ── yt-dlp ────────────────────────────────────────────────────────────────

    val ytDlpStatus: StateFlow<YtDlpStatus> = ytDlpManager.statusFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), YtDlpStatus())

    // ── Backup ────────────────────────────────────────────────────────────────

    private val _backupMessage = Channel<String>(Channel.BUFFERED)
    val backupMessage: Flow<String> = _backupMessage.receiveAsFlow()

    private val _backupInProgress = MutableStateFlow(false)
    val backupInProgress: StateFlow<Boolean> = _backupInProgress.asStateFlow()

    // ── Option lists ──────────────────────────────────────────────────────────

    val themeOptions: List<String> = listOf("System", "Dark", "Light")
    val audioQualityOptions: List<String> = listOf("Auto", "Low", "Medium", "High", "Best")
    val lyricsFontSizeOptions: List<String> = listOf("Small", "Medium", "Large")
    val skipDurationOptions: List<Int> = listOf(5, 10, 15, 30)

    /** (bytes → label) pairs; 0 = unlimited. */
    val cacheSizeOptions: List<Pair<Long, String>> = listOf(
        0L                  to "Unlimited",
        128L * 1024 * 1024  to "128 MB",
        256L * 1024 * 1024  to "256 MB",
        512L * 1024 * 1024  to "512 MB",
        1024L * 1024 * 1024 to "1 GB",
        2048L * 1024 * 1024 to "2 GB",
    )

    // ── Setters ───────────────────────────────────────────────────────────────

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[AppPreferences.DYNAMIC_COLOR] = enabled } }
    }

    fun setTheme(value: String) {
        viewModelScope.launch { dataStore.edit { it[AppPreferences.THEME] = value } }
    }

    fun setAudioQuality(quality: String) {
        viewModelScope.launch { dataStore.edit { it[AppPreferences.AUDIO_QUALITY] = quality } }
    }

    fun setNormalizeVolume(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[AppPreferences.NORMALIZE_VOLUME] = enabled } }
    }

    fun setSkipSilence(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[AppPreferences.SKIP_SILENCE] = enabled } }
    }

    fun setSkipDuration(seconds: Int) {
        viewModelScope.launch { dataStore.edit { it[AppPreferences.SKIP_DURATION] = seconds } }
    }

    fun setLyricsFontSize(size: String) {
        viewModelScope.launch { dataStore.edit { it[AppPreferences.LYRICS_FONT_SIZE] = size } }
    }

    fun setMaxCacheSize(bytes: Long) {
        viewModelScope.launch { dataStore.edit { it[AppPreferences.MAX_CACHE_SIZE] = bytes } }
    }

    fun retryYtDlpDownload() = ytDlpManager.enqueueUpdate()
    fun checkYtDlpUpdate() = ytDlpManager.enqueueUpdate(version = null)

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            _backupInProgress.value = true
            runCatching { backupManager.exportBackup(uri) }
                .onSuccess { _backupMessage.send("Backup exported successfully") }
                .onFailure { _backupMessage.send("Export failed: ${it.message}") }
            _backupInProgress.value = false
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            _backupInProgress.value = true
            runCatching { backupManager.importBackup(uri) }
                .onFailure {
                    _backupInProgress.value = false
                    _backupMessage.send("Import failed: ${it.message}")
                }
        }
    }

    companion object {
        val KEY_DYNAMIC_COLOR = AppPreferences.DYNAMIC_COLOR
        val KEY_AUDIO_QUALITY = AppPreferences.AUDIO_QUALITY
    }
}
