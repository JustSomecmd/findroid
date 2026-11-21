package dev.jdtech.jellyfin.presentation.jrwi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderAction
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiDownloadEntry
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.presentation.film.components.CancelDownloadDialog
import dev.jdtech.jellyfin.presentation.film.components.DeleteDownloadDialog
import dev.jdtech.jellyfin.presentation.film.components.DownloaderCard
import dev.jdtech.jellyfin.presentation.film.components.StorageSelectionDialog
import dev.jdtech.jellyfin.presentation.theme.spacings
import kotlin.math.ceil

/**
 * An episode's buttons, as on a TubeArchivist video's page but square and in the archiver's yellow:
 * Play, with the minutes left once it's started, and Play from the start next to it; then watched
 * or not, and the download of the version picked: the audio from the archiver, or the archived
 * video through Jellyfin like the app's other downloads.
 */
@Composable
fun JrwiItemButtons(
    item: JrwiItem,
    playsAudio: Boolean,
    position: Double,
    finished: Boolean,
    lookingUp: Boolean,
    onPlay: (fromStart: Boolean) -> Unit,
    onToggleWatched: () -> Unit,
    audioDownload: JrwiDownloadEntry?,
    onDownloadAudio: () -> Unit,
    onRemoveAudio: () -> Unit,
    episode: FindroidEpisode?,
    videoDownload: DownloaderState,
    onVideoAction: (DownloaderAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val length = (if (playsAudio) item.audio?.duration else item.video?.duration) ?: item.duration
    val started = !finished && position > 0
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
            Button(
                onClick = { onPlay(false) },
                enabled = !lookingUp,
                shape = JrwiShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = JrwiColors.Accent,
                        contentColor = JrwiColors.AccentInk,
                    ),
                modifier = Modifier.weight(1f).height(BUTTON_SIZE),
            ) {
                if (lookingUp) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = JrwiColors.AccentInk,
                    )
                } else {
                    Icon(painterResource(CoreR.drawable.ic_play), contentDescription = null)
                    Spacer(Modifier.width(MaterialTheme.spacings.small))
                    Text(
                        text =
                            if (started && length != null && length > position) {
                                val minutes = ceil((length - position) / 60).toInt()
                                stringResource(CoreR.string.runtime_minutes_left, minutes)
                            } else {
                                stringResource(CoreR.string.play)
                            },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            // From a minute in, as on a TubeArchivist video's page
            if (started && position >= 60) {
                JrwiSquareButton(
                    icon = CoreR.drawable.ic_rotate_ccw,
                    contentDescription = stringResource(CoreR.string.jrwi_play_from_start),
                    onClick = { onPlay(true) },
                    enabled = !lookingUp,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
            JrwiSquareButton(
                icon = CoreR.drawable.ic_check,
                contentDescription =
                    stringResource(
                        if (finished) CoreR.string.jrwi_mark_unwatched
                        else CoreR.string.jrwi_mark_watched
                    ),
                onClick = onToggleWatched,
                filled = finished,
            )
            if (playsAudio) {
                if (item.audio != null) {
                    JrwiAudioDownloadButton(audioDownload, onDownloadAudio, onRemoveAudio)
                }
            } else if (episode != null && (episode.canDownload || episode.isDownloaded())) {
                JrwiVideoDownloadButton(episode, videoDownload, onVideoAction)
            }
        }
        if (playsAudio) {
            JrwiAudioDownloadProgress(audioDownload)
        } else if (episode != null) {
            JrwiVideoDownloadProgress(episode, videoDownload, onVideoAction)
        }
    }
}

/**
 * A square button in the archiver's yellow: [filled] for one that's on, such as a watched episode,
 * or else yellow on a dark yellow.
 */
@Composable
fun JrwiSquareButton(
    icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = JrwiShape,
        color = if (filled) JrwiColors.Accent else JrwiTonal,
        contentColor = if (filled) JrwiColors.AccentInk else JrwiColors.Accent,
        modifier = modifier.size(BUTTON_SIZE),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (content != null) content()
            else Icon(painterResource(icon), contentDescription = contentDescription)
        }
    }
}

/** The archiver's yellow, dimmed on its navy, for buttons that aren't on. */
val JrwiTonal: Color = JrwiColors.Accent.copy(alpha = 0.16f).compositeOver(JrwiColors.Background)

private val BUTTON_SIZE = 48.dp

/**
 * The audio's download: download, how far it's got (tapping cancels it), or delete once it's on the
 * phone, each asked first as the app's other downloads do.
 */
@Composable
private fun JrwiAudioDownloadButton(
    entry: JrwiDownloadEntry?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    var cancelOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    val download = entry?.download
    when {
        download == null || download.failed ->
            JrwiSquareButton(
                icon = CoreR.drawable.ic_download,
                contentDescription = stringResource(CoreR.string.jrwi_download_audio),
                onClick = onDownload,
            )
        download.done ->
            JrwiSquareButton(
                icon = CoreR.drawable.ic_trash,
                contentDescription = stringResource(CoreR.string.jrwi_download_remove),
                onClick = { deleteOpen = true },
            )
        else ->
            JrwiSquareButton(
                icon = CoreR.drawable.ic_x,
                contentDescription = stringResource(CoreR.string.jrwi_download_cancel),
                onClick = { cancelOpen = true },
            ) {
                val progress = entry.progress
                if (progress != null) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = JrwiColors.Accent,
                        trackColor = JrwiColors.Accent.copy(alpha = 0.25f),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = JrwiColors.Accent,
                    )
                }
            }
    }
    if (cancelOpen) {
        CancelDownloadDialog(
            onCancel = {
                cancelOpen = false
                onRemove()
            },
            onDismiss = { cancelOpen = false },
        )
    }
    if (deleteOpen) {
        DeleteDownloadDialog(
            onDelete = {
                deleteOpen = false
                onRemove()
            },
            onDismiss = { deleteOpen = false },
        )
    }
}

/** How far the audio's download has got, or that it failed, under the buttons. */
@Composable
private fun JrwiAudioDownloadProgress(entry: JrwiDownloadEntry?) {
    val download = entry?.download ?: return
    if (download.done) return
    val progress = entry.progress
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text =
                when {
                    download.failed -> stringResource(CoreR.string.jrwi_download_failed)
                    progress != null ->
                        stringResource(CoreR.string.jrwi_download_running, (progress * 100).toInt())
                    else -> stringResource(CoreR.string.jrwi_download_waiting)
                },
            style = MaterialTheme.typography.bodySmall,
            color =
                if (download.failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!download.failed) {
            val bar = Modifier.fillMaxWidth()
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    color = JrwiColors.Accent,
                    trackColor = JrwiBarTrack,
                    modifier = bar,
                )
            } else {
                LinearProgressIndicator(
                    color = JrwiColors.Accent,
                    trackColor = JrwiBarTrack,
                    modifier = bar,
                )
            }
        }
    }
}

/**
 * The archived video's download through Jellyfin: download, asking where when the phone has more
 * than one place for it, or delete once it's on the phone. While it downloads, the app's download
 * card shows under the buttons.
 */
@Composable
private fun JrwiVideoDownloadButton(
    episode: FindroidEpisode,
    state: DownloaderState,
    onAction: (DownloaderAction) -> Unit,
) {
    val context = LocalContext.current
    var storageOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    // Progress shows in a notification, which needs this permission on Android 13 and newer. The
    // download starts whether or not it's given.
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    if (state.isDownloading) return
    if (episode.isDownloaded()) {
        JrwiSquareButton(
            icon = CoreR.drawable.ic_trash,
            contentDescription = stringResource(CoreR.string.jrwi_download_remove),
            onClick = { deleteOpen = true },
        )
    } else {
        JrwiSquareButton(
            icon = CoreR.drawable.ic_download,
            contentDescription = stringResource(CoreR.string.jrwi_download_video),
            onClick = {
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (context.getExternalFilesDirs(null).size > 1) {
                    storageOpen = true
                } else {
                    onAction(DownloaderAction.Download(episode, 0))
                }
            },
        )
    }
    if (storageOpen) {
        val locations = remember {
            context.getExternalFilesDirs(null).map { dir ->
                val where =
                    context.getString(
                        if (Environment.isExternalStorageRemovable(dir)) CoreR.string.external
                        else CoreR.string.internal
                    )
                context.getString(
                    CoreR.string.storage_name,
                    where,
                    StatFs(dir.path).availableBytes / 1_000_000,
                )
            }
        }
        StorageSelectionDialog(
            storageLocations = locations,
            onSelect = { index ->
                storageOpen = false
                onAction(DownloaderAction.Download(episode, index))
            },
            onDismiss = { storageOpen = false },
        )
    }
    if (deleteOpen) {
        DeleteDownloadDialog(
            onDelete = {
                deleteOpen = false
                onAction(DownloaderAction.DeleteDownload(episode))
            },
            onDismiss = { deleteOpen = false },
        )
    }
}

/** The app's download card while the video downloads, with pause, resume and cancel. */
@Composable
private fun JrwiVideoDownloadProgress(
    episode: FindroidEpisode,
    state: DownloaderState,
    onAction: (DownloaderAction) -> Unit,
) {
    var cancelOpen by remember { mutableStateOf(false) }
    AnimatedVisibility(state.isDownloading) {
        DownloaderCard(
            state = state,
            onCancelClick = { cancelOpen = true },
            onRetryClick = {
                if (state.canResume) onAction(DownloaderAction.ResumeDownload(episode))
                else onAction(DownloaderAction.Download(episode, 0))
            },
            onPauseClick = { onAction(DownloaderAction.PauseDownload(episode)) },
            onResumeClick = { onAction(DownloaderAction.ResumeDownload(episode)) },
        )
    }
    if (cancelOpen) {
        CancelDownloadDialog(
            onCancel = {
                cancelOpen = false
                onAction(DownloaderAction.CancelDownload(episode))
            },
            onDismiss = { cancelOpen = false },
        )
    }
}
