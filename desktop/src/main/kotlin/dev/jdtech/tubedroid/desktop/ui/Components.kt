package dev.jdtech.tubedroid.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.tubearchivist.TaChannel
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.tubedroid.desktop.LocalApp
import kotlinx.coroutines.flow.distinctUntilChanged

/** An image from TubeArchivist, by the path the API gives, such as /cache/videos/... */
@Composable
fun TaImage(
    path: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val api = LocalApp.current.api
    AsyncImage(
        model = api.mediaUrl(path),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@Composable
fun TaAvatar(path: String?, size: Dp, modifier: Modifier = Modifier) {
    TaImage(path = path, modifier = modifier.size(size).clip(CircleShape))
}

/** A label on top of a picture, such as a video's length. */
@Composable
fun OverlayLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier =
            modifier
                .background(Color.Black.copy(alpha = 0.75f), MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** A video's thumbnail with its length, watched tick and how far it was watched. */
@Composable
fun VideoThumbnail(video: TaVideo, modifier: Modifier = Modifier) {
    Box(modifier = modifier.aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)) {
        TaImage(path = video.thumbUrl, modifier = Modifier.fillMaxSize())
        val player = video.player
        if (player != null && player.duration > 0) {
            OverlayLabel(
                text = formatDuration(player.duration),
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacings.small),
            )
        }
        if (player?.watched == true) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(Spacings.small),
            ) {
                AppIcon(
                    name = "ic_check",
                    contentDescription = "Watched",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(4.dp).size(14.dp),
                )
            }
        }
        val progress = player?.progress
        if (player?.watched != true && progress != null && progress > 0) {
            Box(
                modifier =
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.4f))
            ) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth((progress / 100).toFloat().coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

// Clear of the card's rounded corners, which would cut off the first letters
private val CardTextPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 10.dp)

/** A video with its thumbnail on top, as in a feed. */
@Composable
fun VideoCard(
    video: TaVideo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showChannel: Boolean = true,
) {
    Column(modifier = modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        VideoThumbnail(video = video, modifier = Modifier.fillMaxWidth())
        Column(modifier = Modifier.padding(CardTextPadding)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = videoSubtitle(video, showChannel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A video in a list: a small thumbnail with the title beside it. */
@Composable
fun VideoRow(
    video: TaVideo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .padding(Spacings.extraSmall),
    ) {
        index?.let {
            Text(
                text = "$it",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp),
            )
        }
        VideoThumbnail(video = video, modifier = Modifier.width(168.dp))
        Spacer(Modifier.width(Spacings.medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = videoSubtitle(video, showChannel = true),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

fun videoSubtitle(video: TaVideo, showChannel: Boolean): String {
    val parts = mutableListOf<String>()
    if (showChannel) video.channel?.name?.let { parts.add(it) }
    video.stats?.viewCount?.let { parts.add("${formatCount(it)} views") }
    relativeDate(video.published)?.let { parts.add(it) }
    return parts.joinToString(" • ")
}

@Composable
fun ChannelItem(channel: TaChannel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .padding(Spacings.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TaAvatar(path = channel.thumbUrl, size = 96.dp)
        Spacer(Modifier.height(Spacings.small))
        Text(
            text = channel.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        channel.subs?.let { subs ->
            Text(
                text = "${formatCount(subs)} subscribers",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun PlaylistCard(playlist: TaPlaylist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Box(
            modifier =
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)
        ) {
            TaImage(path = playlist.thumbUrl, modifier = Modifier.fillMaxSize())
            if (playlist.entries.isNotEmpty()) {
                OverlayLabel(
                    text = "${playlist.entries.size} videos",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(Spacings.small),
                )
            }
        }
        Column(modifier = Modifier.padding(CardTextPadding)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            playlist.channelName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(vertical = Spacings.small),
    )
}

/** Loading spinner, error with retry, or a note when empty, for the end of a list. */
@Composable
fun ListFooter(
    loading: Boolean,
    error: String?,
    isEmpty: Boolean,
    onRetry: () -> Unit,
    emptyText: String? = null,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(Spacings.medium),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator()
            error != null ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            isEmpty && emptyText != null ->
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

@Composable
fun <T> ListFooter(list: PagedList<T>, emptyText: String? = null) {
    ListFooter(
        loading = list.loading,
        error = list.error,
        isEmpty = list.items.isEmpty() && list.endReached,
        onRetry = list::loadMore,
        emptyText = emptyText,
    )
}

/** Calls [onLoadMore] when the grid is scrolled close to its end. */
@Composable
fun LoadMoreEffect(state: LazyGridState, onLoadMore: () -> Unit) {
    val current by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) current() }
    }
}

@Composable
fun LoadMoreEffect(state: LazyListState, onLoadMore: () -> Unit) {
    val current by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) current() }
    }
}

private const val LOAD_MORE_THRESHOLD = 6

/** A scroll bar along the right edge, as desktop apps have. */
@Composable
fun BoxScope.GridScrollbar(state: LazyGridState) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 2.dp),
    )
}

@Composable
fun BoxScope.ListScrollbar(state: LazyListState) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 2.dp),
    )
}

@Composable
fun BoxScope.ColumnScrollbar(state: ScrollState) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 2.dp),
    )
}

/** Text with web addresses that can be clicked. */
@Composable
fun rememberLinkedText(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, linkColor) {
        val styles = TextLinkStyles(style = SpanStyle(color = linkColor))
        buildAnnotatedString {
            var last = 0
            urlPattern.findAll(text).forEach { match ->
                append(text.substring(last, match.range.first))
                val url = match.value.trimEnd('.', ',', ')', '!', '?', ':', ';')
                withLink(LinkAnnotation.Url(url, styles)) { append(url) }
                last = match.range.first + url.length
            }
            append(text.substring(last))
        }
    }
}

private val urlPattern = Regex("""https?://[^\s<>"]+""")

/**
 * Which side of a page is shown: this computer's, or the server's, with its settings and actions.
 * Tapping flips it; the page's colors swap to show the server side.
 */
@Composable
fun SideSwitch(
    onServer: Boolean,
    onToggle: () -> Unit,
    localLabel: String,
    modifier: Modifier = Modifier,
    localIcon: String = "ic_monitor",
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.clip(CircleShape).clickable(onClick = onToggle),
    ) {
        Row(modifier = Modifier.padding(2.dp)) {
            SideSegment(icon = localIcon, label = localLabel, selected = !onServer)
            SideSegment(icon = "ic_server", label = "Server", selected = onServer)
        }
    }
}

@Composable
private fun SideSegment(icon: String, label: String, selected: Boolean) {
    val background by
        animateColorAsState(
            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            label = "side background",
        )
    val content by
        animateColorAsState(
            if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            label = "side content",
        )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.clip(CircleShape)
                .background(background)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .animateContentSize(),
    ) {
        AppIcon(
            name = icon,
            contentDescription = label,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

/**
 * The bar at the top of a page: a back button when there's somewhere to go back to, and actions.
 */
@Composable
fun PageBar(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacings.small),
        modifier =
            modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = Spacings.medium),
    ) {
        if (onBack != null) {
            Surface(
                shape = CircleShape,
                color = Color.Transparent,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onBack),
            ) {
                AppIcon(
                    name = "ic_arrow_left",
                    contentDescription = "Back",
                    modifier = Modifier.padding(Spacings.small).size(22.dp),
                )
            }
        }
        Text(
            text = title.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** Asks before something that can't be undone, such as deleting videos from the server. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A centered spinner or error for a page that loads as a whole. */
@Composable
fun PageState(loading: Boolean, error: String?, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator()
        } else if (error != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

/** A name and its value, for details such as a video's file on the server. */
@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(200.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A card with a title, for the server sides of pages. */
@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacings.medium)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = Spacings.small),
            )
            content()
        }
    }
}

/** Reaches past the list's padding on both sides, so a banner runs from edge to edge. */
fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val sides = horizontal.roundToPx()
    val width = constraints.maxWidth + sides * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-sides, 0) }
}
