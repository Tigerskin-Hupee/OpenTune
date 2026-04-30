package app.opentune.viewmodels

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.innertube.InnertubeApi
import app.opentune.innertube.models.MusicItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnlineBrowseViewModel @Inject constructor(
    private val innertubeApi: InnertubeApi,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val TAG = "OnlineBrowseViewModel"

    val browseId: String = savedStateHandle.get<String>("browseId") ?: ""
    val browseType: String = savedStateHandle.get<String>("browseType") ?: "album"

    val items = MutableStateFlow<List<MusicItem>>(emptyList())
    val isLoading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            isLoading.value = true
            error.value = null
            innertubeApi.browseItems(browseId)
                .onSuccess { list ->
                    Log.d(TAG, "browseItems($browseId) success: ${list.size} items")
                    items.value = list
                }
                .onFailure { err ->
                    Log.e(TAG, "browseItems($browseId) failed: ${err.message}", err)
                    error.value = err.message
                }
            isLoading.value = false
        }
    }
}
