package app.opentune.ui.menu

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import app.opentune.LocalDownloadUtil
import app.opentune.LocalPlayerConnection
import app.opentune.R
import app.opentune.playback.ExoDownloadService
import app.opentune.utils.getDownloadState
import app.opentune.models.MultiQueueObject
import app.opentune.ui.component.items.QueueListItem
import app.opentune.ui.dialog.AddToPlaylistDialog
import app.opentune.ui.dialog.AddToQueueDialog
import app.opentune.ui.dialog.EditQueueDialog

@Composable
fun QueueMenu(
    navController: NavController,
    mq: MultiQueueObject?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val queueBoard by playerConnection.queueBoard.collectAsState()
    val downloadUtil = LocalDownloadUtil.current

    if (mq == null) {
        onDismiss()
        return
    }
    val songs = mq.getCurrentQueueShuffled()

    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }
    LaunchedEffect(songs) {
        downloadUtil.downloads.collect { downloads ->
            downloadState = getDownloadState(songs.map { downloads[it.id] })
        }
    }

    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showChooseQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showEditDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // queue item
    QueueListItem(queue = mq)

    HorizontalDivider()

    // menu options
    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            title = R.string.add_to_queue
        ) {
            showChooseQueueDialog = true
        }
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            title = R.string.add_to_playlist
        ) {
            showChoosePlaylistDialog = true
        }
        DownloadGridMenu(
            state = downloadState,
            onDownload = {
                downloadUtil.download(songs)
            },
            onRemoveDownload = {
                songs.forEach { song ->
                    DownloadService.sendRemoveDownload(
                        context,
                        ExoDownloadService::class.java,
                        song.id,
                        false
                    )
                }
            }
        )
        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            title = R.string.edit
        ) {
            showEditDialog = true
        }
    }

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */
    if (showChoosePlaylistDialog) {
        AddToPlaylistDialog(
            navController = navController,
            songIds = songs.map { it.id },
            onDismiss = {
                showChoosePlaylistDialog = false
            }
        )
    }

    if (showChooseQueueDialog) {
        AddToQueueDialog(
            onAdd = { queueName ->
                val q = queueBoard.addQueue(
                    queueName,
                    songs,
                    forceInsert = true,
                    delta = false
                )
                q?.let {
                    queueBoard.setCurrQueue(it)
                }
            },
            onDismiss = {
                showChooseQueueDialog = false
                onDismiss() // here we dismiss since we switch to the queue anyways
            }
        )
    }

    if (showEditDialog) {
        EditQueueDialog(
            queue = mq,
            onDismiss = {
                showEditDialog = false
                onDismiss()
            }
        )
    }
}