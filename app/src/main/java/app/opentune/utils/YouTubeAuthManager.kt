/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.utils

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import app.opentune.constants.YtOAuthAccessToken
import app.opentune.constants.YtOAuthExpiry
import app.opentune.constants.YtOAuthRefreshToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object YouTubeAuthManager {

    private const val TAG = "YouTubeAuthManager"

    // YouTube TV OAuth credentials — well-known public credentials used by open-source
    // YouTube clients (yt-dlp, LibreTube, etc.) for the device-code flow.
    private const val CLIENT_ID =
        "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com" // NOSONAR
    private const val CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT" // NOSONAR
    private const val SCOPE = "https://www.googleapis.com/auth/youtube"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class DeviceCodeInfo(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresInSeconds: Int,
        val intervalSeconds: Int,
    )

    fun isLoggedIn(context: Context): Boolean =
        context.dataStore[YtOAuthRefreshToken] != null

    /** Returns a valid access token, refreshing it if needed. Null if not logged in. */
    fun getValidAccessToken(context: Context): String? {
        val refreshToken = context.dataStore[YtOAuthRefreshToken] ?: return null
        val expiry = context.dataStore[YtOAuthExpiry] ?: 0L
        val nowSeconds = System.currentTimeMillis() / 1000

        val cachedToken = context.dataStore[YtOAuthAccessToken]
        if (cachedToken != null && expiry - nowSeconds > 120) {
            return cachedToken
        }
        return runBlocking(Dispatchers.IO) { refreshAccessToken(context, refreshToken) }
    }

    private fun refreshAccessToken(context: Context, refreshToken: String): String? {
        return try {
            val body = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .build()
            val response = httpClient.newCall(
                Request.Builder()
                    .url("https://oauth2.googleapis.com/token")
                    .post(body)
                    .build()
            ).execute()
            val rb = response.body?.string() ?: return null
            val json = JSONObject(rb)
            if (json.has("error")) {
                Log.w(TAG, "Token refresh error: ${json.optString("error")}")
                return null
            }
            val accessToken = json.getString("access_token")
            val expiresIn = json.optLong("expires_in", 3600L)
            val newExpiry = System.currentTimeMillis() / 1000 + expiresIn
            runBlocking {
                context.dataStore.edit { prefs ->
                    prefs[YtOAuthAccessToken] = accessToken
                    prefs[YtOAuthExpiry] = newExpiry
                }
            }
            accessToken
        } catch (e: Exception) {
            Log.e(TAG, "refreshAccessToken failed", e)
            null
        }
    }

    /** Step 1 of device code flow: request a device code. Call from IO thread. */
    fun requestDeviceCode(): DeviceCodeInfo {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPE)
            .build()
        val response = httpClient.newCall(
            Request.Builder()
                .url("https://oauth2.googleapis.com/device/code")
                .post(body)
                .build()
        ).execute()
        val rb = response.body?.string() ?: error("Empty device code response")
        val json = JSONObject(rb)
        if (json.has("error")) error("Device code error: ${json.optString("error_description")}")
        return DeviceCodeInfo(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUrl = json.optString("verification_url").ifBlank { "https://www.google.com/device" },
            expiresInSeconds = json.optInt("expires_in", 1800),
            intervalSeconds = json.optInt("interval", 5),
        )
    }

    /**
     * Step 2: poll Google's token endpoint until the user authorizes or the code expires.
     * Returns true when tokens were saved successfully.
     */
    suspend fun pollUntilAuthorized(
        context: Context,
        deviceCodeInfo: DeviceCodeInfo,
        onUpdate: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + deviceCodeInfo.expiresInSeconds * 1000L
        var intervalMs = deviceCodeInfo.intervalSeconds * 1000L

        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            try {
                val body = FormBody.Builder()
                    .add("client_id", CLIENT_ID)
                    .add("client_secret", CLIENT_SECRET)
                    .add("device_code", deviceCodeInfo.deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .build()
                val response = httpClient.newCall(
                    Request.Builder()
                        .url("https://oauth2.googleapis.com/token")
                        .post(body)
                        .build()
                ).execute()
                val rb = response.body?.string() ?: continue
                val json = JSONObject(rb)

                when (json.optString("error")) {
                    "" -> {
                        // Success
                        val accessToken = json.getString("access_token")
                        val refreshToken = json.getString("refresh_token")
                        val expiresIn = json.optLong("expires_in", 3600L)
                        val expiry = System.currentTimeMillis() / 1000 + expiresIn
                        context.dataStore.edit { prefs ->
                            prefs[YtOAuthAccessToken] = accessToken
                            prefs[YtOAuthRefreshToken] = refreshToken
                            prefs[YtOAuthExpiry] = expiry
                        }
                        Log.d(TAG, "OAuth tokens saved, expiry=${expiresIn}s")
                        return@withContext true
                    }
                    "authorization_pending" -> { /* keep polling */ }
                    "slow_down" -> { intervalMs += 5000L }
                    else -> {
                        onUpdate("Error: ${json.optString("error_description")}")
                        return@withContext false
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pollUntilAuthorized: ${e.message}")
            }
        }
        onUpdate("Code expired — please try again")
        false
    }

    fun logout(context: Context) {
        runBlocking {
            context.dataStore.edit { prefs ->
                prefs.remove(YtOAuthAccessToken)
                prefs.remove(YtOAuthRefreshToken)
                prefs.remove(YtOAuthExpiry)
            }
        }
        Log.d(TAG, "Logged out")
    }
}
