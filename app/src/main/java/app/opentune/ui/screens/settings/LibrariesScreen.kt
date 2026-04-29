/*
 * Copyright (C) 2025 OpenTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */
package app.opentune.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import app.opentune.LocalPlayerAwareWindowInsets
import app.opentune.R
import app.opentune.constants.TopBarInsets
import app.opentune.ui.component.button.IconButton
import app.opentune.ui.utils.backToMain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrariesScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.oss_licenses_title))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.oss_licenses_title)) },
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
