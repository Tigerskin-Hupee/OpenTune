/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package app.opentune.ui.screens.settings

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.opentune.BuildConfig
import app.opentune.R
import app.opentune.constants.ENABLE_FFMETADATAEX
import app.opentune.constants.LYRIC_FETCH_TIMEOUT
import app.opentune.constants.MAX_LM_SCANNER_JOBS
import app.opentune.constants.OOBE_VERSION
import app.opentune.constants.SNACKBAR_VERY_SHORT
import app.opentune.constants.TopBarInsets
import app.opentune.ui.component.ColumnWithContentPadding
import app.opentune.ui.component.ContributorCard
import app.opentune.ui.component.ContributorInfo
import app.opentune.ui.component.ContributorType.CUSTOM
import app.opentune.ui.component.PreferenceEntry
import app.opentune.ui.component.SettingsClickToReveal
import app.opentune.ui.component.button.IconButton
import app.opentune.ui.component.button.IconLabelButton
import app.opentune.ui.utils.backToMain
import app.opentune.utils.scanners.FFmpegScanner
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current
    val uriHandler = LocalUriHandler.current

    val showDebugInfo = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "userdebug"

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.launcher_monochrome),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground, BlendMode.SrcIn),
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation))
                .clickable { }
        )

        Row(
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = "OpenTune",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | ${BuildConfig.FLAVOR}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(Modifier.width(4.dp))

            if (showDebugInfo) {
                Spacer(Modifier.width(4.dp))

                Text(
                    text = BuildConfig.BUILD_TYPE.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.secondary,
                            shape = CircleShape
                        )
                        .padding(
                            horizontal = 6.dp,
                            vertical = 2.dp
                        )
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            IconLabelButton(
                text = "GitHub",
                painter = painterResource(R.drawable.github),
                onClick = { uriHandler.openUri("https://github.com/Tigerskin-Hupee/OpenTune") },
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            IconLabelButton(
                text = stringResource(R.string.wiki),
                icon = Icons.Outlined.Info,
                onClick = { uriHandler.openUri("https://github.com/Tigerskin-Hupee/OpenTune/wiki") },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        Spacer(Modifier.height(96.dp))

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.attribution_title)) },
                    onClick = {
                        navController.navigate("settings/about/attribution")
                    }
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.oss_licenses_title)) },
                    onClick = {
                        navController.navigate("settings/about/oss_licenses")
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.help_bug_report_action)) },
                    onClick = {
                        uriHandler.openUri("https://github.com/Tigerskin-Hupee/OpenTune/issues")
                    }
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.help_support_forum)) },
                    onClick = {
                        uriHandler.openUri("https://github.com/Tigerskin-Hupee/OpenTune/discussions")
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingsClickToReveal(stringResource(R.string.app_info_title)) {
                    val info = mutableListOf<String>(
                        "FFMetadataEx: $ENABLE_FFMETADATAEX",
                        "LM scanner concurrency: $MAX_LM_SCANNER_JOBS",
                        "LYRIC_FETCH_TIMEOUT: $LYRIC_FETCH_TIMEOUT",
                        "OOBE_VERSION: $OOBE_VERSION",
                        "LYRIC_FETCH_TIMEOUT: $LYRIC_FETCH_TIMEOUT",
                        "SNACKBAR_VERY_SHORT: $SNACKBAR_VERY_SHORT"
                    )
                    if (ENABLE_FFMETADATAEX) {
                        info.add("FFMetadataEx version: ${FFmpegScanner.VERSION_STRING}")
                        info.add("FFmpeg version: ${FfmpegLibrary.getVersion()}")
                        info.add("FFmpeg isAvailable: ${FfmpegLibrary.isAvailable()}")
                    }

                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        info.forEach {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                SettingsClickToReveal(stringResource(R.string.device_info_title)) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val info = mutableListOf<String>(
                            "Device: ${Build.BRAND} ${Build.DEVICE} (${Build.MODEL})",
                            "Manufacturer: ${Build.MANUFACTURER}",
                            "HW: ${Build.BOARD} (${Build.HARDWARE})",
                            "ABIs: ${Build.SUPPORTED_ABIS.joinToString()})",
                            "Android: ${Build.VERSION.SDK_INT} (${Build.ID})",
                            Build.DISPLAY,
                            Build.PRODUCT,
                            Build.FINGERPRINT,
                            Build.VERSION.SECURITY_PATCH
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            info.add("SOC: ${Build.SOC_MODEL} (${Build.SOC_MANUFACTURER})")
                            info.add("SKU: ${Build.SKU} (${Build.ODM_SKU})")
                        }

                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            info.forEach {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                SettingsClickToReveal("Playback Diagnostics") {
                    val report = app.opentune.utils.DiagnosticsLogger.getReport(context)
                    val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = report,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                clipboardManager.setPrimaryClip(
                                    ClipData.newPlainText("OpenTune Diagnostics", report)
                                )
                            }) {
                                Text("Copy Report")
                            }
                            Button(
                                onClick = { app.opentune.utils.DiagnosticsLogger.clear() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            ) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (ENABLE_FFMETADATAEX) {
                ContributorCard(
                    contributor = ContributorInfo(
                        name = "FFmpeg",
                        description = stringResource(R.string.ffmpeg_lgpl),
                        type = listOf(CUSTOM),
                        url = "https://github.com/Tigerskin-Hupee/OpenTune"
                    )
                )
            }
        }

    }

    TopAppBar(
        title = { Text(stringResource(R.string.about)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}
