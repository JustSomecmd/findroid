package dev.jdtech.jellyfin.presentation.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.local.R as PlayerR
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.tube.TaVideoThumbnail
import dev.jdtech.jellyfin.tubearchivist.TaVideo

/**
 * The playlist being played, slid open over the player as on YouTube: from the right when the
 * screen is wider than tall, from the bottom otherwise. The video playing is marked and the list
 * opens at it; tapping another plays it. At the top right are looping and the playlist's page.
 */
@Composable
fun PlayerPlaylistPanel(
    open: Boolean,
    name: String,
    videos: List<TaVideo>,
    currentVideoId: String?,
    loop: Boolean,
    onClose: () -> Unit,
    onPlay: (videoId: String) -> Unit,
    onLoopChange: (Boolean) -> Unit,
    onOpenPage: () -> Unit,
) {
    BackHandler(enabled = open, onBack = onClose)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
            // Tapping beside the playlist closes it
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClose,
                        )
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = if (wide) slideInHorizontally { it } else slideInVertically { it },
            exit = if (wide) slideOutHorizontally { it } else slideOutVertically { it },
            modifier = Modifier.align(if (wide) Alignment.CenterEnd else Alignment.BottomCenter),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape =
                    if (wide) RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                    else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                modifier =
                    if (wide) Modifier.fillMaxHeight().width(min(maxWidth * 0.5f, 440.dp))
                    else Modifier.fillMaxWidth().fillMaxHeight(0.65f),
            ) {
                PlaylistContent(
                    wide = wide,
                    open = open,
                    name = name,
                    videos = videos,
                    currentVideoId = currentVideoId,
                    loop = loop,
                    onPlay = onPlay,
                    onLoopChange = onLoopChange,
                    onOpenPage = onOpenPage,
                )
            }
        }
    }
}

@Composable
private fun PlaylistContent(
    wide: Boolean,
    open: Boolean,
    name: String,
    videos: List<TaVideo>,
    currentVideoId: String?,
    loop: Boolean,
    onPlay: (videoId: String) -> Unit,
    onLoopChange: (Boolean) -> Unit,
    onOpenPage: () -> Unit,
) {
    val currentIndex = videos.indexOfFirst { it.youtubeId == currentVideoId }
    // Clear of a notch, on the sides that reach the screen's edges: a notch on the far side of
    // the screen would only push the list in
    val edges =
        if (wide) WindowInsetsSides.End + WindowInsetsSides.Vertical
        else WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(edges))) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            modifier =
                Modifier.padding(
                    start = MaterialTheme.spacings.medium,
                    end = MaterialTheme.spacings.small,
                    top = MaterialTheme.spacings.small,
                    bottom = MaterialTheme.spacings.small,
                ),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        if (currentIndex >= 0) {
                            stringResource(
                                PlayerR.string.player_playlist_position,
                                currentIndex + 1,
                                videos.size,
                            )
                        } else {
                            pluralStringResource(
                                CoreR.plurals.ta_video_count,
                                videos.size,
                                videos.size.toString(),
                            )
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconToggleButton(checked = loop, onCheckedChange = onLoopChange) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_repeat),
                    contentDescription = stringResource(PlayerR.string.player_loop_playlist),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onOpenPage) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_external_link),
                    contentDescription = stringResource(PlayerR.string.player_open_playlist),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        HorizontalDivider()
        val listState = rememberLazyListState()
        // Opens at the video playing, with the one before it above
        LaunchedEffect(open, currentIndex) {
            if (open && currentIndex >= 0) {
                listState.scrollToItem((currentIndex - 1).coerceAtLeast(0))
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(videos, key = { _, video -> video.youtubeId }) { index, video ->
                PlaylistRow(
                    number = index + 1,
                    video = video,
                    current = video.youtubeId == currentVideoId,
                    onClick = { onPlay(video.youtubeId) },
                )
            }
        }
    }
}

/**
 * A video of the playlist: its number, or a play mark for the one playing, then thumbnail and
 * title.
 */
@Composable
private fun PlaylistRow(number: Int, video: TaVideo, current: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .background(
                    if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                )
                .clickable(onClick = onClick)
                .padding(
                    horizontal = MaterialTheme.spacings.small,
                    vertical = MaterialTheme.spacings.small,
                ),
    ) {
        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (current) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_play),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TaVideoThumbnail(video = video, modifier = Modifier.width(120.dp))
        Column(modifier = Modifier.weight(1f).padding(start = MaterialTheme.spacings.medium)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            video.channel?.name?.let { channel ->
                Text(
                    text = channel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
