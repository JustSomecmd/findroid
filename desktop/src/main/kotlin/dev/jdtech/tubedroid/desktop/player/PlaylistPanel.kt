package dev.jdtech.tubedroid.desktop.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.ListScrollbar
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.VideoThumbnail

/** How wide the playlist beside the video is. */
internal val PLAYLIST_PANEL_WIDTH = 380.dp

/**
 * The playlist being played, beside the video as on YouTube. The video playing is marked and the
 * list opens at it; clicking another plays it. At the top right are looping and the playlist's
 * page.
 */
@Composable
internal fun PlaylistPanel(
    name: String,
    videos: List<TaVideo>,
    currentVideoId: String?,
    loop: Boolean,
    onPlay: (videoId: String) -> Unit,
    onLoopChange: (Boolean) -> Unit,
    onOpenPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentIndex = videos.indexOfFirst { it.youtubeId == currentVideoId }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxHeight().width(PLAYLIST_PANEL_WIDTH),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacings.small),
                modifier =
                    Modifier.padding(
                        start = Spacings.medium,
                        end = Spacings.small,
                        top = Spacings.small,
                        bottom = Spacings.small,
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
                            if (currentIndex >= 0) "${currentIndex + 1} of ${videos.size}"
                            else "${videos.size} ${if (videos.size == 1) "video" else "videos"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                WithTooltip(if (loop) "Looping the playlist" else "Loop the playlist") {
                    FilledIconToggleButton(checked = loop, onCheckedChange = onLoopChange) {
                        AppIcon(
                            name = "ic_repeat",
                            contentDescription = "Loop the playlist",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                WithTooltip("Open the playlist's page") {
                    IconButton(onClick = onOpenPage) {
                        AppIcon(
                            name = "ic_external_link",
                            contentDescription = "Open the playlist's page",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            HorizontalDivider()
            val listState = rememberLazyListState()
            // Opens at the video playing, with the one before it above
            LaunchedEffect(currentIndex) {
                if (currentIndex >= 0) listState.scrollToItem((currentIndex - 1).coerceAtLeast(0))
            }
            Box(modifier = Modifier.fillMaxSize()) {
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
                ListScrollbar(listState)
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
                .padding(horizontal = Spacings.small, vertical = 6.dp),
    ) {
        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (current) {
                AppIcon(
                    name = "ic_play",
                    contentDescription = "Playing",
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
        VideoThumbnail(video = video, modifier = Modifier.width(128.dp))
        Column(modifier = Modifier.weight(1f).padding(start = Spacings.medium)) {
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

/** A tooltip with [text] when the mouse rests on [content]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WithTooltip(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        content = content,
    )
}
