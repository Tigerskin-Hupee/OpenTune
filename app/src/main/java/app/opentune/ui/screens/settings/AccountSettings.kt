/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.opentune.constants.TopBarInsets
import app.opentune.ui.component.ColumnWithContentPadding
import app.opentune.ui.component.PreferenceEntry
import app.opentune.ui.component.button.IconButton
import app.opentune.ui.utils.backToMain
import app.opentune.utils.YouTubeAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var isLoggedIn by remember { mutableStateOf(YouTubeAuthManager.isLoggedIn(context)) }
    var showSignInDialog by remember { mutableStateOf(false) }
    var deviceCodeInfo by remember { mutableStateOf<YouTubeAuthManager.DeviceCodeInfo?>(null) }
    var statusMessage by remember { mutableStateOf("") }
    var isPolling by remember { mutableStateOf(false) }

    LaunchedEffect(showSignInDialog) {
        if (!showSignInDialog) return@LaunchedEffect
        statusMessage = "Requesting code…"
        deviceCodeInfo = null
        isPolling = false
        withContext(Dispatchers.IO) {
            try {
                val info = YouTubeAuthManager.requestDeviceCode()
                deviceCodeInfo = info
                statusMessage = ""
                isPolling = true
            } catch (e: Exception) {
                statusMessage = "Failed to get code: ${e.message?.take(80)}"
            }
        }
    }

    LaunchedEffect(isPolling, deviceCodeInfo) {
        val info = deviceCodeInfo ?: return@LaunchedEffect
        if (!isPolling) return@LaunchedEffect
        val authorized = YouTubeAuthManager.pollUntilAuthorized(context, info) { msg ->
            statusMessage = msg
        }
        isPolling = false
        if (authorized) {
            isLoggedIn = true
            showSignInDialog = false
            statusMessage = ""
        }
    }

    if (showSignInDialog) {
        val info = deviceCodeInfo
        AlertDialog(
            onDismissRequest = {
                showSignInDialog = false
                isPolling = false
            },
            title = { Text("Sign in to YouTube") },
            text = {
                Column {
                    if (info == null) {
                        Text(statusMessage.ifBlank { "Connecting…" })
                    } else {
                        Text("1. Open this URL on your phone or computer:")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = info.verificationUrl,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("2. Enter this code:")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = info.userCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (statusMessage.isNotBlank()) {
                            Text(statusMessage, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("Waiting for authorization…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                if (info != null) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(info.userCode))
                    }) { Text("Copy code") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSignInDialog = false
                    isPolling = false
                }) { Text("Cancel") }
            },
        )
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            if (isLoggedIn) {
                PreferenceEntry(
                    title = { Text("YouTube: Signed in") },
                    description = "Playback uses your Google account",
                    icon = { Icon(Icons.Rounded.AccountCircle, null) },
                    onClick = {},
                )
                PreferenceEntry(
                    title = { Text("Sign out") },
                    icon = { Icon(Icons.Rounded.Logout, null) },
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            YouTubeAuthManager.logout(context)
                            withContext(Dispatchers.Main) { isLoggedIn = false }
                        }
                    },
                )
            } else {
                PreferenceEntry(
                    title = { Text("Sign in to YouTube") },
                    description = "Required for music playback",
                    icon = { Icon(Icons.Rounded.AccountCircle, null) },
                    onClick = { showSignInDialog = true },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "YouTube now requires a Google account to play music. Sign in once — the app will automatically refresh your session.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    TopAppBar(
        title = { Text("Account") },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior,
    )
}
