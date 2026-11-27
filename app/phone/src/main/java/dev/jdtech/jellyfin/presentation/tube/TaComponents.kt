package dev.jdtech.jellyfin.presentation.tube

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.tube.TaListState
import dev.jdtech.jellyfin.film.presentation.tube.TaMessages
import dev.jdtech.jellyfin.presentation.theme.LocalPillShape
import dev.jdtech.jellyfin.presentation.theme.sideTopBarColor
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.tubearchivist.TaChannel
import dev.jdtech.jellyfin.tubearchivist.TaNowPlaying
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import java.text.NumberFormat
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.round
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Whether TubeArchivist is the main interface right now. */
val LocalTubeArchivistMode = staticCompositionLocalOf { false }

/** Address of the TubeArchivist server, for the images it serves. */
val LocalTaBaseUrl = staticCompositionLocalOf<String?> { null }

/** Full address of a path such as /media/thumb/video/abc.jpg. */
@Composable
fun taUrl(path: String?): String? {
    if (path.isNullOrBlank()) return null
    if (path.startsWith("http://") || path.startsWith("https://")) return path
    val base = LocalTaBaseUrl.current ?: return null
    return base + (if (path.startsWith("/")) path else "/$path")
}

@Composable
fun TaImage(
    path: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    AsyncImage(
        model = taUrl(path),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@Composable
fun TaAvatar(path: String?, size: Dp, modifier: Modifier = Modifier) {
    TaImage(path = path, modifier = modifier.size(size).clip(CircleShape))
}

/** A video with its thumbnail on top, as in a feed. */
@Composable
fun TaVideoCard(
    video: TaVideo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showChannel: Boolean = true,
) {
    Column(modifier = modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        TaVideoThumbnail(video = video, modifier = Modifier.fillMaxWidth())
        // Kept clear of the rounded corners, which would cut off the first letters
        Column(modifier = Modifier.padding(TaCardTextPadding)) {
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

/**
 * A smaller video card for rows that scroll sideways. Every card is as tall as one whose title
 * takes two lines, so the row keeps its height as it scrolls and what's under it stays put. The
 * channel sits right under the title, and the room a one-line title leaves is under the channel.
 */
@Composable
fun TaVideoSmallCard(video: TaVideo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier.width(240.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)
    ) {
        TaVideoThumbnail(video = video, modifier = Modifier.fillMaxWidth())
        Box(modifier = Modifier.padding(TaCardTextPadding)) {
            // Takes the height of a two-line title and the channel, with nothing to show or read
            Column(modifier = Modifier.clearAndSetSemantics {}) {
                Text(text = "", style = MaterialTheme.typography.bodyMedium, minLines = 2)
                Text(text = "", style = MaterialTheme.typography.bodySmall)
            }
            Column {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = video.channel?.name.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Padding of the text under a card's thumbnail, clear of the card's rounded corners. */
private val TaCardTextPadding =
    PaddingValues(
        start = 6.dp,
        end = 6.dp,
        top = 6.dp,
        bottom = 10.dp,
    )

/** The video open in the player, such as in a small window, which thumbnails mark. */
val LocalTaNowPlaying = compositionLocalOf<TaNowPlaying.State?> { null }

@Composable
fun TaVideoThumbnail(video: TaVideo, modifier: Modifier = Modifier) {
    Box(modifier = modifier.aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)) {
        TaImage(path = video.thumbUrl, modifier = Modifier.fillMaxSize())
        val nowPlaying = LocalTaNowPlaying.current
        if (nowPlaying?.videoId == video.youtubeId) {
            TaNowPlayingOverlay(playing = nowPlaying.playing)
        }
        val player = video.player
        if (player != null && player.duration > 0) {
            TaOverlayLabel(
                text = formatDuration(player.duration),
                modifier =
                    Modifier.align(Alignment.BottomEnd).padding(MaterialTheme.spacings.small),
            )
        }
        if (player?.watched == true) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(MaterialTheme.spacings.small),
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_check),
                    contentDescription = stringResource(CoreR.string.ta_watched),
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

/** Darkens a thumbnail and says the video is the one playing, or paused, in the player. */
@Composable
private fun TaNowPlayingOverlay(playing: Boolean) {
    Row(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter =
                painterResource(if (playing) CoreR.drawable.ic_play else CoreR.drawable.ic_pause),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text =
                stringResource(
                    if (playing) CoreR.string.ta_now_playing else CoreR.string.ta_now_paused
                ),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

@Composable
fun TaOverlayLabel(text: String, modifier: Modifier = Modifier) {
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

@Composable
fun TaChannelItem(channel: TaChannel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .padding(MaterialTheme.spacings.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TaAvatar(path = channel.thumbUrl, size = 80.dp)
        Spacer(Modifier.height(MaterialTheme.spacings.small))
        Text(
            text = channel.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        channel.subs?.let { subs ->
            Text(
                text = stringResource(CoreR.string.ta_subscribers, formatCount(subs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun TaPlaylistCard(playlist: TaPlaylist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)) {
        Box(
            modifier =
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)
        ) {
            TaImage(path = playlist.thumbUrl, modifier = Modifier.fillMaxSize())
            if (playlist.entries.isNotEmpty()) {
                TaOverlayLabel(
                    text = stringResource(CoreR.string.ta_video_count, playlist.entries.size),
                    modifier =
                        Modifier.align(Alignment.BottomEnd).padding(MaterialTheme.spacings.small),
                )
            }
        }
        // Kept clear of the rounded corners, which would cut off the first letters
        Column(modifier = Modifier.padding(TaCardTextPadding)) {
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

/** Loading spinner, error with retry, or nothing, for the end of a list that loads in pages. */
@Composable
fun <T> TaListFooter(state: TaListState<T>, onRetry: () -> Unit, emptyText: String? = null) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.loading -> CircularProgressIndicator()
            state.error != null ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(CoreR.string.retry)) }
                }
            state.isEmpty && emptyText != null ->
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

/** Calls [onLoadMore] when the list is scrolled close to its end. */
@Composable
fun LoadMoreEffect(state: LazyGridState, onLoadMore: () -> Unit) {
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) currentOnLoadMore() }
    }
}

@Composable
fun LoadMoreEffect(state: LazyListState, onLoadMore: () -> Unit) {
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) currentOnLoadMore() }
    }
}

private const val LOAD_MORE_THRESHOLD = 4

/** Text with web addresses that can be tapped. */
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

@Composable
private fun videoSubtitle(video: TaVideo, showChannel: Boolean): String {
    val parts = mutableListOf<String>()
    if (showChannel) video.channel?.name?.let { parts.add(it) }
    video.stats?.viewCount?.let {
        parts.add(stringResource(CoreR.string.ta_views, formatCount(it)))
    }
    relativeDate(video.published)?.let { parts.add(it) }
    return parts.joinToString(" • ")
}

/** 1234 as 1.2K, 3400000 as 3.4M. */
fun formatCount(count: Long): String =
    when {
        count >= 1_000_000_000 -> trimDecimal(count / 1_000_000_000.0) + "B"
        count >= 1_000_000 -> trimDecimal(count / 1_000_000.0) + "M"
        count >= 1_000 -> trimDecimal(count / 1_000.0) + "K"
        else -> count.toString()
    }

private fun trimDecimal(value: Double): String =
    if (value >= 100) value.toLong().toString()
    else String.format(java.util.Locale.getDefault(), "%.1f", value).trimEnd('0').trimEnd('.', ',')

/** Seconds as 4:05 or 1:02:03. */
fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
    else "%d:%02d".format(minutes, secs)
}

private fun parseDate(iso: String?): OffsetDateTime? =
    try {
        iso?.let { OffsetDateTime.parse(it) }
    } catch (_: Exception) {
        null
    }

/** "3 days ago" */
fun relativeDate(iso: String?): String? {
    val date = parseDate(iso) ?: return null
    return DateUtils.getRelativeTimeSpanString(
            date.toInstant().toEpochMilli(),
            System.currentTimeMillis(),
            DateUtils.DAY_IN_MILLIS,
        )
        .toString()
}

/** "18 Nov 2026" in the phone's own format. */
fun fullDate(iso: String?): String? {
    val date = parseDate(iso) ?: return null
    return date.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

/** Turns a message from a TubeArchivist screen into text. */
fun taMessageText(context: Context, message: String): String =
    when (message) {
        TaMessages.SUBSCRIBE_STARTED -> context.getString(CoreR.string.ta_subscribe_started)
        TaMessages.PLAYLIST_SUBSCRIBE_STARTED ->
            context.getString(CoreR.string.ta_playlist_subscribe_started)
        TaMessages.ADDED_TO_QUEUE -> context.getString(CoreR.string.ta_added_to_queue)
        TaMessages.REDOWNLOAD_STARTED -> context.getString(CoreR.string.ta_redownload_started)
        TaMessages.DELETED -> context.getString(CoreR.string.ta_deleted)
        TaMessages.NOT_IN_JELLYFIN -> context.getString(CoreR.string.ta_not_in_jellyfin)
        TaMessages.PLAYLIST_CREATED -> context.getString(CoreR.string.ta_playlist_created)
        TaMessages.TASK_STARTED -> context.getString(CoreR.string.ta_task_started)
        TaMessages.SAVED -> context.getString(CoreR.string.ta_saved)
        TaMessages.REFRESH_STARTED -> context.getString(CoreR.string.ta_refresh_started)
        TaMessages.ERRORS_CLEARED -> context.getString(CoreR.string.ta_errors_cleared)
        else -> message
    }

/** A top bar button that opens a page of the TubeArchivist web app, such as /video/<id>. */
@Composable
fun TaOpenInWebButton(path: String) {
    val context = LocalContext.current
    val url = taUrl(path)
    if (url != null) {
        IconButton(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, url, Toast.LENGTH_LONG).show()
                }
            }
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_external_link),
                contentDescription = stringResource(CoreR.string.ta_open_in_web),
            )
        }
    }
}

/**
 * A page title that switches between the side of something the app shows (such as the downloads on
 * this phone) and its side on the TubeArchivist server. Tapping the title or the switch flips it.
 */
@Composable
fun TaSideTitle(
    title: String,
    onServer: Boolean,
    onToggle: () -> Unit,
    @DrawableRes localIcon: Int = CoreR.drawable.ic_smartphone,
    localLabel: String = stringResource(CoreR.string.ta_side_phone),
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.clip(MaterialTheme.shapes.small)
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(MaterialTheme.spacings.small))
        TaSideSwitch(onServer = onServer, localIcon = localIcon, localLabel = localLabel)
    }
}

/**
 * The switch of [TaSideTitle] on its own, for pages whose title is already shown below. Tapping it
 * flips the side when [onToggle] is set.
 */
@Composable
fun TaSideSwitch(
    onServer: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (() -> Unit)? = null,
    @DrawableRes localIcon: Int = CoreR.drawable.ic_smartphone,
    localLabel: String = stringResource(CoreR.string.ta_side_phone),
    /** Names the side shown next to its icon. Off where room is short. */
    showLabel: Boolean = true,
    /** Puts the server on the left, for pages whose server side is to the left. */
    serverFirst: Boolean = false,
) {
    // Round, or square on the JRWI pages
    val shape = LocalPillShape.current
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier =
            if (onToggle != null) modifier.clip(shape).clickable(onClick = onToggle) else modifier,
    ) {
        Row(modifier = Modifier.padding(2.dp)) {
            val local =
                @Composable {
                    TaSideSegment(
                        icon = localIcon,
                        label = localLabel,
                        selected = !onServer,
                        showLabel = showLabel,
                    )
                }
            val server =
                @Composable {
                    TaSideSegment(
                        icon = CoreR.drawable.ic_server,
                        label = stringResource(CoreR.string.ta_side_server),
                        selected = onServer,
                        showLabel = showLabel,
                    )
                }
            if (serverFirst) {
                server()
                local()
            } else {
                local()
                server()
            }
        }
    }
}

@Composable
private fun TaSideSegment(
    @DrawableRes icon: Int,
    label: String,
    selected: Boolean,
    showLabel: Boolean,
) {
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
            Modifier.clip(LocalPillShape.current)
                .background(background)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .animateContentSize(),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = content,
            modifier = Modifier.size(16.dp),
        )
        // Only the side that is shown is named, so the switch stays small
        if (selected && showLabel) {
            Spacer(Modifier.width(4.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = content)
        }
    }
}

/**
 * True for the main pages shown side by side in the pager that swipes between Home, Channels and
 * Downloads.
 */
val LocalInMainPager = staticCompositionLocalOf { false }

/**
 * Works out the screen's insets from where it is on screen, as screens inside the app's navigation
 * do, except in the main pager: there a page that slides would get different insets at each step,
 * so the pager works them out once for all its pages.
 */
@Composable
fun Modifier.recalculateWindowInsetsUnlessPaged(): Modifier =
    if (LocalInMainPager.current) this else this.recalculateWindowInsets()

/**
 * Top bar colors with the bar's background always shown, not only once the page scrolls. On a
 * page's server side the bar takes the page's background instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun alwaysShownTopAppBarColors(): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(
        containerColor = sideTopBarColor(),
        scrolledContainerColor = sideTopBarColor(),
    )

/**
 * Pages of something shown on the phone side and the server side. The phone side is the first page,
 * or with [serverFirst] the second, with the server side to its left. It starts on the phone side,
 * or with [onServer] on the server side.
 */
@Composable
fun rememberSidePagerState(serverFirst: Boolean = false, onServer: Boolean = false): PagerState =
    rememberPagerState(initialPage = if (onServer != serverFirst) 1 else 0) { 2 }

/** Whether the server side is the page mostly in view. */
val PagerState.onServerSide: Boolean
    get() = currentPage == 1

/** Whether the server side is the page mostly in view, with the server side first when set. */
fun PagerState.serverSideShown(serverFirst: Boolean): Boolean =
    currentPage == if (serverFirst) 0 else 1

/** Slides a side pager over to its other side. */
fun PagerState.switchSide(scope: CoroutineScope) {
    scope.launch { animateScrollToPage(1 - currentPage) }
}

/**
 * Slides a side pager to its server side, to its own side, or with null to the side it isn't on,
 * with the server side first when [serverFirst] is set.
 */
fun PagerState.showSide(scope: CoroutineScope, server: Boolean?, serverFirst: Boolean = false) {
    val page = if (server == null) 1 - currentPage else if (server == serverFirst) 0 else 1
    scope.launch { animateScrollToPage(page) }
}

/** Puts a side pager on its own side at once, for a page that's out of sight. */
fun PagerState.requestOwnSide(serverFirst: Boolean = false) {
    val page = if (serverFirst) 1 else 0
    if (currentPage != page || currentPageOffsetFraction != 0f) requestScrollToPage(page)
}

/**
 * The phone side and the server side of something, side by side, so a swipe moves between them like
 * the tabs on the Channels page.
 */
@Composable
fun TaSidePager(
    state: PagerState,
    phone: @Composable () -> Unit,
    server: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Puts the server side to the left of the phone side. */
    serverFirst: Boolean = false,
    /**
     * Keeps the side out of view built, for busy pages such as Home, so a swipe doesn't wait for
     * the other side to be built first. Its effects keep running too, so not for pages that check
     * the server while shown.
     */
    keepBothSides: Boolean = false,
) {
    val defaultConnection = PagerDefaults.pageNestedScrollConnection(state, Orientation.Horizontal)
    val flickSpeed = with(LocalDensity.current) { SIDE_FLICK_SPEED.toPx() }
    val connection =
        remember(defaultConnection, state, flickSpeed) {
            SideSwipeConnection(state, defaultConnection, flickSpeed)
        }
    HorizontalPager(
        state = state,
        modifier = modifier,
        beyondViewportPageCount = if (keepBothSides) 1 else 0,
        // In the main pager, its swiper moves the sides with the main pages
        userScrollEnabled = !LocalInMainPager.current,
        pageNestedScrollConnection = connection,
        // Turns over after a shorter drag than the default of half the screen
        flingBehavior =
            PagerDefaults.flingBehavior(
                state = state,
                snapPositionalThreshold = SIDE_SWIPE_THRESHOLD,
            ),
        key = { page -> page },
    ) { page ->
        if ((page == 0) != serverFirst) phone() else server()
    }
}

// How far a slow drag has to go, as part of the screen's width, to turn to the other side
private const val SIDE_SWIPE_THRESHOLD = 0.3f

// How fast a flick has to be to turn to the other side, as for the main pages
private val SIDE_FLICK_SPEED = 400.dp

/**
 * Swipes that start on something on a side that scrolls sideways too, such as Home's carousel, move
 * the pager once that thing is at its end. When such a swipe let go, Compose's own handling stopped
 * the thing's fling, which also left the pager between its sides until the next swipe. Here the
 * pager turns to a side itself when let go between them, as it does for swipes on the pager.
 */
private class SideSwipeConnection(
    private val state: PagerState,
    private val default: NestedScrollConnection,
    private val flickSpeed: Float,
) : NestedScrollConnection by default {
    private val betweenSides: Boolean
        get() = abs(state.currentPageOffsetFraction) > 0.001f

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!betweenSides) return default.onPreFling(available)
        val from = state.settledPage
        val moved = state.currentPage + state.currentPageOffsetFraction - from
        val target =
            when {
                available.x < -flickSpeed -> from + 1
                available.x > flickSpeed -> from - 1
                moved > SIDE_SWIPE_THRESHOLD -> from + 1
                moved < -SIDE_SWIPE_THRESHOLD -> from - 1
                else -> from
            }.coerceIn(0, state.pageCount - 1)
        state.animateScrollToPage(target)
        // The swipe was the pager's, so what scrolls inside doesn't fling as well
        return Velocity(available.x, 0f)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // In case a fling inside still left it between the sides
        if (betweenSides) state.animateScrollToPage(state.currentPage)
        return default.onPostFling(consumed, available)
    }
}

/**
 * Lets a row scroll sideways by itself, apart from the pages around it that swipe sideways too. Set
 * on a row whose own scrolling is turned off, it takes the whole swipe, also at the row's ends, and
 * passes nothing on: the pages never move with the row, a fling in the row never stops or turns
 * them as the bottom bar turns them, and a page that's still sliding never takes over the row's
 * swipe. Compose's own scrolling always shares a swipe with the pages around it, so the row is
 * moved here directly instead.
 */
@Composable
fun rememberSeparateRowScroll(state: LazyListState): Modifier {
    val fling = ScrollableDefaults.flingBehavior()
    // Dragging right shows what's to the left, which is the other way round right to left
    val sign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    val drag = rememberDraggableState { delta -> state.dispatchRawDelta(delta * sign) }
    return Modifier.draggable(
        state = drag,
        orientation = Orientation.Horizontal,
        // Catches a row that's still moving, as touching any list does
        startDragImmediately = state.isScrollInProgress,
        onDragStarted = { state.stopScroll() },
        onDragStopped = { velocity ->
            state.scroll { with(fling) { performFling(velocity * sign) } }
        },
    )
}

/**
 * A tap target that reads as a button: a raised tile with a chevron at its end, or [trailing] in
 * its place, such as a switch. Its [content] takes the room left with `Modifier.weight(1f)`.
 */
@Composable
fun TaButtonTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    verticalPadding: Dp = 12.dp,
    trailing: @Composable () -> Unit = {
        Icon(
            painter = painterResource(CoreR.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    },
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(color)
                .clickable(onClick = onClick)
                .padding(
                    start = MaterialTheme.spacings.medium,
                    end = 12.dp,
                    top = verticalPadding,
                    bottom = verticalPadding,
                ),
    ) {
        content()
        Spacer(Modifier.width(MaterialTheme.spacings.small))
        trailing()
    }
}

/** A label and its value on a server page, such as "Size" and "1.2 GB". */
@Composable
fun TaInfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A heading on a server page. */
@Composable
fun TaInfoHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = MaterialTheme.spacings.medium, bottom = 4.dp),
    )
}

/** 1536 as 1.5 KB. */
fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B"
    else String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unit])
}

/**
 * A size as TubeArchivist shows it: 643.0 GB in metric units (powers of 1000), or 598.9 GiB in
 * binary ones (powers of 1024), following the unit picked in TubeArchivist's settings.
 */
fun formatSize(bytes: Long, metric: Boolean): String {
    val step = if (metric) 1000.0 else 1024.0
    if (abs(bytes) < step) return "$bytes B"
    val units =
        if (metric) listOf("kB", "MB", "GB", "TB", "PB", "EB")
        else listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= step
        unit++
    } while (round(abs(value) * 10) / 10 >= step && unit < units.lastIndex)
    return String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unit])
}

/** A long playing time by its two largest units: 137d 10h, 19h 19m, 45m or 30s. */
fun formatPlaytime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

/** 5890 as 5,890, in the phone's own way of writing numbers. */
fun formatNumber(count: Long): String = NumberFormat.getIntegerInstance().format(count)

/** "18 Nov 2026, 14:03" for seconds since 1970. */
fun formatEpochSeconds(seconds: Long): String =
    java.time.Instant.ofEpochSecond(seconds)
        .atZone(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

/** A date TubeArchivist sends, in the phone's format, or as it is when it can't be read. */
fun formatTaDate(value: String?): String? = value?.let { fullDate(it) ?: it }

/** The TubeArchivist logo, with its letters in the text color so it suits light and dark themes. */
@Composable
fun TaLogo(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_ta_logo_mark),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.matchParentSize(),
        )
        Icon(
            painter = painterResource(CoreR.drawable.ic_ta_logo_letters),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/**
 * Tubedroid's logo: the ring of TubeArchivist's logo with the Android figure of Findroid's in the
 * middle. The figure is white on dark themes and in the text color on light ones.
 */
@Composable
fun TubedroidLogo(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Box(modifier = modifier) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_ta_logo_ring),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.matchParentSize(),
        )
        Icon(
            painter = painterResource(CoreR.drawable.ic_tubedroid_head),
            contentDescription = null,
            tint = if (dark) Color.White else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.matchParentSize(),
        )
    }
}
