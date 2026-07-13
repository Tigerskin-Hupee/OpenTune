/*
 * Copyright (C) 2025 OpenTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.opentune.BuildConfig
import app.opentune.R
import app.opentune.utils.UpdateChecker

/**
 * Checks GitHub Releases once per app start and shows an update dialog when a
 * newer APK is available. Confirming downloads the APK and opens the system
 * installer (see [UpdateChecker.downloadAndInstall]).
 */
@Composable
fun UpdateNotifier() {
    val context = LocalContext.current
    var release by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }

    LaunchedEffect(Unit) {
        release = UpdateChecker.check()
    }

    release?.let { info ->
        AlertDialog(
            onDismissRequest = { release = null },
            title = { Text(stringResource(R.string.update_available_title, info.version)) },
            text = {
                Text(
                    stringResource(
                        R.string.update_available_message,
                        BuildConfig.VERSION_NAME,
                        info.version,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    UpdateChecker.downloadAndInstall(context, info)
                    release = null
                }) {
                    Text(stringResource(R.string.update_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { release = null }) {
                    Text(stringResource(R.string.update_later))
                }
            },
        )
    }
}
