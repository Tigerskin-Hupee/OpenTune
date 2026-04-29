package app.opentune.ui.viewmodels

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.playback.YtDlpManager
import app.opentune.playback.YtDlpStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val ytDlpManager: YtDlpManager,
) : ViewModel() {

    val dynamicColor: StateFlow<Boolean> = dataStore.data
        .map { it[KEY_DYNAMIC_COLOR] ?: true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val audioQuality: StateFlow<String> = dataStore.data
        .map { it[KEY_AUDIO_QUALITY] ?: "Best" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Best")

    val ytDlpStatus: StateFlow<YtDlpStatus> = ytDlpManager.statusFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), YtDlpStatus())

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled } }
    }

    fun setAudioQuality(quality: String) {
        viewModelScope.launch { dataStore.edit { it[KEY_AUDIO_QUALITY] = quality } }
    }

    fun retryYtDlpDownload() {
        ytDlpManager.enqueueUpdate()
    }

    fun checkYtDlpUpdate() {
        ytDlpManager.enqueueUpdate(version = null)
    }

    companion object {
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_AUDIO_QUALITY = stringPreferencesKey("audio_quality")
    }
}
