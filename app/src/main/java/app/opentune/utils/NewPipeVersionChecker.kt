/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.utils

import android.util.Log
import app.opentune.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

object NewPipeVersionChecker {

    private const val TAG = "NewPipeVersionChecker"
    private const val GITHUB_COMMITS_API =
        "https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/commits?sha=dev&per_page=1"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val currentCommit: String get() = BuildConfig.NEWPIPE_EXTRACTOR_COMMIT

    fun fetchLatestDevCommit(): String? {
        return try {
            val response = httpClient.newCall(
                Request.Builder()
                    .url(GITHUB_COMMITS_API)
                    .addHeader("Accept", "application/vnd.github.v3+json")
                    .build()
            ).execute()
            val body = response.body?.string() ?: return null
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub API returned ${response.code}")
                return null
            }
            JSONArray(body).getJSONObject(0).optString("sha").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "fetchLatestDevCommit failed: ${e.message}")
            null
        }
    }

    fun isUpToDate(latestCommit: String): Boolean =
        latestCommit.startsWith(currentCommit) || currentCommit.startsWith(latestCommit)
}
