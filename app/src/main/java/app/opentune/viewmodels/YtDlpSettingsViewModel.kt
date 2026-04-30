package app.opentune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.playback.YtDlpManager
import app.opentune.playback.YtDlpStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class YtDlpSettingsViewModel @Inject constructor(
    private val manager: YtDlpManager,
) : ViewModel() {

    val status: StateFlow<YtDlpStatus> = manager.statusFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, YtDlpStatus())

    fun checkForUpdate() {
        manager.enqueueUpdate(version = null)
    }
}
