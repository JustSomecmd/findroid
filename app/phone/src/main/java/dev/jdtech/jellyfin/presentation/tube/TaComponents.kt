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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.tube.TaListState
import dev.jdtech.jellyfin.film.presentation.tube.TaMessages
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.tubearchivist.TaChannel
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

/** A smaller video card for rows that scroll sideways. */
@Composable
fun TaVideoSmallCard(video: TaVideo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier.width(240.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)
    ) {
        TaVideoThumbnail(video = video, modifier = Modifier.fillMaxWidth())
        Column(modifier = Modifier.padding(TaCardTextPadding)) {
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

/** Padding of the text under a card's thumbnail, clear of the card's rounded corners. */
private val TaCardTextPadding =
    PaddingValues(
        start = 6.dp,
        end = 6.dp,
        top = 6.dp,
        bottom = 10.dp,
    )

@Composable
fun TaVideoThumbnail(video: TaVideo, modifier: Modifier = Modifier) {
    Box(modifier = modifier.aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)) {
        TaImage(path = video.thumbUrl, modifier = Modifier.fillMaxSize())
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
) {
    val shape = CircleShape
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier =
            if (onToggle != null) modifier.clip(shape).clickable(onClick = onToggle) else modifier,
    ) {
        Row(modifier = Modifier.padding(2.dp)) {
            TaSideSegment(
                icon = localIcon,
                label = localLabel,
                selected = !onServer,
                showLabel = showLabel,
            )
            TaSideSegment(
                icon = CoreR.drawable.ic_server,
                label = stringResource(CoreR.string.ta_side_server),
                selected = onServer,
                showLabel = showLabel,
            )
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
            Modifier.clip(CircleShape)
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

/** Top bar colors with the bar's background always shown, not only once the page scrolls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun alwaysShownTopAppBarColors(): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )

/** Pages of something shown on the phone side (the first page) and the server side (the second). */
@Composable fun rememberSidePagerState(): PagerState = rememberPagerState { 2 }

/** Whether the server side is the page mostly in view. */
val PagerState.onServerSide: Boolean
    get() = currentPage == 1

/** Slides a side pager over to its other side. */
fun PagerState.switchSide(scope: CoroutineScope) {
    scope.launch { animateScrollToPage(1 - currentPage) }
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
) {
    HorizontalPager(state = state, modifier = modifier, key = { page -> page }) { page ->
        if (page == 0) phone() else server()
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
