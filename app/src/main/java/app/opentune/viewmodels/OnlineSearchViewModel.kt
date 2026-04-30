package app.opentune.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.innertube.InnertubeApi
import app.opentune.innertube.models.MusicItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class OnlineSearchViewModel @Inject constructor(
    private val innertubeApi: InnertubeApi,
) : ViewModel() {
    private val TAG = "OnlineSearchViewModel"

    val query = MutableStateFlow("")
    val isLoading = MutableStateFlow(false)

    val result = query
        .debounce(300)
        .distinctUntilChanged()
        .filter { it.isNotBlank() }
        .mapLatest { q ->
            isLoading.value = true
            Log.d(TAG, "Searching online: \"$q\"")
            val items = innertubeApi.search(q)
                .onSuccess { list ->
                    Log.d(TAG, "search(\"$q\") success: ${list.size} results")
                }
                .onFailure { err ->
                    Log.e(TAG, "search(\"$q\") failed: ${err.javaClass.simpleName}: ${err.message}", err)
                }
                .getOrElse { emptyList() }
            isLoading.value = false
            items
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList<MusicItem>())
}
