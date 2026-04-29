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

    val dynamicColor: StateFlow<Boolean> = dataStore.data
        .map { it[AppPreferences.DYNAMIC_COLOR] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val audioQuality: StateFlow<String> = dataStore.data
        .map { it[AppPreferences.AUDIO_QUALITY] ?: "Best" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Best")

    val ytDlpStatus: StateFlow<YtDlpStatus> = ytDlpManager.statusFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), YtDlpStatus())

    private val _backupMessage = Channel<String>(Channel.BUFFERED)
    val backupMessage: Flow<String> = _backupMessage.receiveAsFlow()

    private val _backupInProgress = MutableStateFlow(false)
    val backupInProgress: StateFlow<Boolean> = _backupInProgress.asStateFlow()

    val audioQualityOptions: List<String> = listOf("Auto", "Low", "Medium", "High", "Best")

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[AppPreferences.DYNAMIC_COLOR] = enabled } }
    }

    fun setAudioQuality(quality: String) {
        viewModelScope.launch { dataStore.edit { it[AppPreferences.AUDIO_QUALITY] = quality } }
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

    // Keep for backward compat (StreamResolver previously referenced these)
    companion object {
        val KEY_DYNAMIC_COLOR = AppPreferences.DYNAMIC_COLOR
        val KEY_AUDIO_QUALITY = AppPreferences.AUDIO_QUALITY
    }
}
