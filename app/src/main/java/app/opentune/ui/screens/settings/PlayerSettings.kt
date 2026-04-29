/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package app.opentune.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.NoCell
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.navigation.NavController
import app.opentune.R
import app.opentune.constants.AudioDecoderKey
import app.opentune.constants.ENABLE_FFMETADATAEX
import app.opentune.constants.KeepAliveKey
import app.opentune.constants.StopMusicOnTaskClearKey
import app.opentune.constants.TopBarInsets
import app.opentune.ui.component.ColumnWithContentPadding
import app.opentune.ui.component.ListPreference
import app.opentune.ui.component.PreferenceGroupTitle
import app.opentune.ui.component.SettingsClickToReveal
import app.opentune.ui.component.SwitchPreference
import app.opentune.ui.component.button.IconButton
import app.opentune.ui.dialog.InfoLabel
import app.opentune.ui.screens.settings.fragments.AudioEffectsFrag
import app.opentune.ui.screens.settings.fragments.AudioQualityFrag
import app.opentune.ui.screens.settings.fragments.PlaybackBehaviourFrag
import app.opentune.ui.screens.settings.fragments.PlayerGeneralFrag
import app.opentune.ui.utils.backToMain
import app.opentune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (audioDecoder, onAudioDecoderChange) = rememberPreference(
        key = AudioDecoderKey,
        defaultValue = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
    )
    val (keepAlive, onKeepAliveChange) = rememberPreference(key = KeepAliveKey, defaultValue = false)
    val (stopMusicOnTaskClear, onStopMusicOnTaskClearChange) = rememberPreference(
        key = StopMusicOnTaskClearKey,
        defaultValue = true
    )

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PreferenceGroupTitle(
            title = stringResource(R.string.grp_general)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PlayerGeneralFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(
            title = stringResource(R.string.grp_audio)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            AudioQualityFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            AudioEffectsFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(
            title = stringResource(R.string.grp_behavior)
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PlaybackBehaviourFrag()
        }

        SettingsClickToReveal(stringResource(R.string.advanced)) {

            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (ENABLE_FFMETADATAEX) {
                    ListPreference(
                        title = { Text(stringResource(R.string.audio_decoder_preference)) },
                        icon = { Icon(Icons.Rounded.AudioFile, null) },
                        selectedValue = audioDecoder,
                        onValueSelected = onAudioDecoderChange,
                        values = listOf(
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
                        ),
                        valueText = {
                            when (it) {
                                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> stringResource(R.string.audio_decoder_system_only)
                                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> stringResource(R.string.audio_decoder_system_with_ffmpeg)
                                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> stringResource(R.string.audio_decoder_ffmpeg_only)
                                else -> {stringResource(R.string.error_unknown)}
                            }
                        }
                    )
                    InfoLabel(stringResource(R.string.restart_to_apply_changes))
                }
                SwitchPreference(
                    title = { Text(stringResource(R.string.keep_alive_title)) },
                    description = stringResource(R.string.keep_alive_description),
                    icon = { Icon(Icons.Rounded.NoCell, null) },
                    checked = keepAlive,
                    onCheckedChange = {
                        if (it) {
                            onStopMusicOnTaskClearChange(false)
                        }
                        onKeepAliveChange(it)
                    }
                )
            }
        }
        Spacer(Modifier.height(96.dp))
    }


    TopAppBar(
        title = { Text(stringResource(R.string.player_and_audio)) },
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
