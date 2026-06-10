/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.opentune.innertube.InnertubeApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val innertubeApi: InnertubeApi,
) : ViewModel() {

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting

    /** null = not yet run; starts with "✓" on success, "✗" on failure */
    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult

    /**
     * Run a live stream resolution test for [videoId].
     * Populates [testResult] and updates DiagnosticsLogger.
     * Uses a known-accessible video by default.
     */
    fun runStreamTest(videoId: String = "jNQXAC9IVRw") {
        if (_isTesting.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isTesting.value = true
            _testResult.value = null
            val start = System.currentTimeMillis()
            val result = try {
                val url = innertubeApi.getAudioStreamUrl(videoId)
                val elapsed = System.currentTimeMillis() - start
                "✓ OK in ${elapsed}ms\n${url.take(100)}…"
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - start
                "✗ FAIL in ${elapsed}ms\n${e.message?.take(300) ?: "unknown error"}"
            }
            _testResult.value = result
            _isTesting.value = false
        }
    }
}
