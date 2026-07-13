/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.opentune.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub Releases for a newer APK and installs it via DownloadManager.
 *
 * Flow: [check] on app start → user confirms in a dialog →
 * [downloadAndInstall] downloads the release APK and fires the system
 * package-installer prompt (release signing is consistent, so it installs
 * over the existing app without data loss).
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/Tigerskin-Hupee/OpenTune/releases/latest"

    // Only releases marked critical in their notes trigger the in-app prompt
    // (e.g. playback-breaking fixes). Regular releases stay silent.
    private val CRITICAL_MARKERS = listOf("[critical]", "[重大]")

    data class ReleaseInfo(
        val version: String,   // e.g. "2.0.5" (tag without leading "v")
        val apkUrl: String,
        val notes: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** Returns the latest release if it is newer than the installed version, else null. */
    suspend fun check(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "check: HTTP ${resp.code}")
                    return@withContext null
                }
                val json = JSONObject(resp.body!!.string())
                val remote = json.getString("tag_name").removePrefix("v")
                if (!isNewer(remote, BuildConfig.VERSION_NAME)) {
                    Log.d(TAG, "check: up to date ($remote <= ${BuildConfig.VERSION_NAME})")
                    return@withContext null
                }
                val body = json.optString("body")
                if (CRITICAL_MARKERS.none { body.contains(it, ignoreCase = true) }) {
                    Log.d(TAG, "check: $remote available but not marked critical; staying silent")
                    return@withContext null
                }
                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        return@withContext ReleaseInfo(
                            version = remote,
                            apkUrl = asset.getString("browser_download_url"),
                            notes = body.take(500),
                        )
                    }
                }
                Log.w(TAG, "check: release $remote has no APK asset")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "check failed [${e.javaClass.simpleName}]: ${e.message}")
            null
        }
    }

    /** True if [remote] ("2.0.5") is a higher semantic version than [local]. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    /**
     * Downloads the APK to public Downloads (with a progress notification),
     * then launches the system installer when the download completes.
     */
    fun downloadAndInstall(context: Context, info: ReleaseInfo) {
        val appContext = context.applicationContext
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(info.apkUrl.toUri())
            .setTitle("OpenTune ${info.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "OpenTune-${info.version}.apk",
            )
        val downloadId = dm.enqueue(request)
        Log.i(TAG, "downloadAndInstall: enqueued id=$downloadId url=${info.apkUrl}")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                appContext.unregisterReceiver(this)
                val uri = dm.getUriForDownloadedFile(downloadId)
                if (uri == null) {
                    Log.w(TAG, "downloadAndInstall: download failed (no file uri)")
                    return
                }
                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(install)
            }
        }
        // ACTION_DOWNLOAD_COMPLETE is a system broadcast → receiver must be exported (API 33+).
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
}
