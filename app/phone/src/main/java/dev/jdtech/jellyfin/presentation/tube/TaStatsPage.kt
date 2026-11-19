package dev.jdtech.jellyfin.presentation.tube

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.tube.TaServerStats
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.tubearchivist.TaBiggestChannel
import dev.jdtech.jellyfin.tubearchivist.TaBiggestOrder
import dev.jdtech.jellyfin.tubearchivist.TaCountStats
import dev.jdtech.jellyfin.tubearchivist.TaDownloadDay
import dev.jdtech.jellyfin.tubearchivist.TaDownloadStats
import dev.jdtech.jellyfin.tubearchivist.TaVideoStats
import dev.jdtech.jellyfin.tubearchivist.TaVideoTotals
import dev.jdtech.jellyfin.tubearchivist.TaWatchStats
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Home's server side: TubeArchivist's numbers, as on its dashboard. The main ones sit in a card the
 * size of the channel carousel on the other side, with the rest in cards below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaHomeStatsPage(
    stats: TaServerStats,
    version: String?,
    onRefresh: () -> Unit,
    onChannelClick: (channelId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(isRefreshing = false, onRefresh = onRefresh, modifier = modifier) {
        when {
            stats.error != null ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier.align(Alignment.Center).padding(MaterialTheme.spacings.default),
                ) {
                    Text(
                        text = stats.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onRefresh) { Text(stringResource(CoreR.string.retry)) }
                }
            !stats.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = TA_CARD_MIN_WIDTH),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(MaterialTheme.spacings.medium),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
                        TaStatsHero(stats = stats, version = version)
                    }
                    stats.watch
                        ?.takeIf { it.watched != null || it.unwatched != null }
                        ?.let { watch -> item(key = "watch") { TaWatchCard(watch) } }
                    stats.videos?.let { videos ->
                        item(key = "types") { TaVideoTypesCard(videos, stats.metricSizes) }
                    }
                    if (stats.channels != null || stats.playlists != null) {
                        item(key = "library") { TaLibraryCard(stats.channels, stats.playlists) }
                    }
                    if (stats.downloads != null || stats.history != null) {
                        item(key = "downloads") {
                            TaDownloadsStatsCard(stats.downloads, stats.history, stats.metricSizes)
                        }
                    }
                    if (stats.biggest.isNotEmpty()) {
                        item(key = "biggest") {
                            TaBiggestChannelsCard(
                                biggest = stats.biggest,
                                metricSizes = stats.metricSizes,
                                onChannelClick = onChannelClick,
                            )
                        }
                    }
                }
        }
    }
}

/** The main numbers, in a card as big as one page of the channel carousel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaStatsHero(stats: TaServerStats, version: String?) {
    val videos = stats.videos ?: TaVideoStats()
    // The accent itself: on the server side the main colors take its place
    val accent = MaterialTheme.colorScheme.inversePrimary
    val colorStops =
        arrayOf(
            0.0f to Color.Black.copy(alpha = 0.25f),
            0.5f to Color.Black.copy(alpha = 0.5f),
            1f to Color.Black.copy(alpha = 0.65f),
        )
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val spacing = MaterialTheme.spacings.medium
        val pages =
            when {
                maxWidth >= 840.dp -> 3
                maxWidth >= 600.dp -> 2
                else -> 1
            }
        Box(
            modifier =
                Modifier.width((maxWidth - spacing * (pages - 1)) / pages)
                    .aspectRatio(1.77f)
                    .clip(MaterialTheme.shapes.large)
                    .background(accent)
        ) {
            // The logo's mark, large and faint, behind the numbers
            Icon(
                painter = painterResource(CoreR.drawable.ic_ta_logo_mark),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.22f),
                modifier =
                    Modifier.size(150.dp).align(Alignment.TopEnd).offset(x = 30.dp, y = (-24).dp),
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(brush = Brush.verticalGradient(colorStops = colorStops))
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            horizontal = MaterialTheme.spacings.default,
                            vertical = MaterialTheme.spacings.medium,
                        )
                        .align(Alignment.BottomStart),
            ) {
                Text(
                    text =
                        listOfNotNull(
                                stringResource(CoreR.string.ta_stats_archive),
                                version?.let { "TubeArchivist $it" },
                            )
                            .joinToString(" · "),
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        pluralStringResource(
                            CoreR.plurals.ta_video_count,
                            videos.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            formatNumber(videos.count),
                        ),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium)
                ) {
                    TaHeroStat(
                        icon = CoreR.drawable.ic_database,
                        label = stringResource(CoreR.string.ta_stats_storage),
                        value = formatSize(videos.mediaSize, stats.metricSizes),
                    )
                    TaHeroStat(
                        icon = CoreR.drawable.ic_clock,
                        label = stringResource(CoreR.string.ta_stats_playtime),
                        value = formatPlaytime(videos.duration),
                    )
                }
                stats.watch?.watched?.let { watched ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TaBar(
                            fraction = watched.progress.toFloat(),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(MaterialTheme.spacings.small))
                        Text(
                            text =
                                stringResource(
                                    CoreR.string.ta_stats_watched_share,
                                    formatPercent(watched.progress),
                                ),
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaHeroStat(@DrawableRes icon: Int, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = value, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

/** How much of the archive was watched, by playing time. */
@Composable
private fun TaWatchCard(watch: TaWatchStats) {
    TaStatsCard(title = stringResource(CoreR.string.ta_stats_watch_progress)) {
        val progress = watch.watched?.progress ?: 0.0
        Text(
            text = stringResource(CoreR.string.ta_stats_watched_share, formatPercent(progress)),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(CoreR.string.ta_stats_watch_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TaBar(
            fraction = progress.toFloat(),
            color = MaterialTheme.colorScheme.inversePrimary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.padding(vertical = MaterialTheme.spacings.small),
        )
        watch.watched?.let {
            TaStatLine(
                label = stringResource(CoreR.string.ta_stats_watched),
                detail = videoCount(it.items),
                value = formatPlaytime(it.duration),
            )
        }
        watch.unwatched?.let {
            TaStatLine(
                label = stringResource(CoreR.string.ta_stats_unwatched),
                detail = videoCount(it.items),
                value = formatPlaytime(it.duration),
            )
        }
    }
}

/** Regular videos, shorts and streams, and what can't be found on YouTube any more. */
@Composable
private fun TaVideoTypesCard(videos: TaVideoStats, metricSizes: Boolean) {
    TaStatsCard(title = stringResource(CoreR.string.ta_stats_video_types)) {
        val types =
            listOf(
                CoreR.string.ta_stats_regular to videos.regular,
                CoreR.string.ta_shorts to videos.shorts,
                CoreR.string.ta_streams to videos.streams,
            )
        types.forEach { (label, totals) ->
            TaTotalsLine(stringResource(label), totals ?: TaVideoTotals(), metricSizes)
        }
        videos.inactive
            ?.takeIf { it.count > 0 }
            ?.let { removed ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                TaTotalsLine(stringResource(CoreR.string.ta_stats_removed), removed, metricSizes)
            }
    }
}

@Composable
private fun TaTotalsLine(label: String, totals: TaVideoTotals, metricSizes: Boolean) {
    TaStatLine(
        label = label,
        detail =
            if (totals.count > 0) {
                formatSize(totals.mediaSize, metricSizes) + " · " + formatPlaytime(totals.duration)
            } else {
                null
            },
        value = formatNumber(totals.count),
    )
}

@Composable
private fun TaLibraryCard(channels: TaCountStats?, playlists: TaCountStats?) {
    TaStatsCard(title = stringResource(CoreR.string.ta_stats_library)) {
        channels?.let {
            TaStatLine(
                label = stringResource(CoreR.string.ta_stats_channels),
                detail =
                    stringResource(CoreR.string.ta_stats_subscribed, formatNumber(it.subscribed)),
                value = formatNumber(it.total),
            )
        }
        playlists?.let {
            TaStatLine(
                label = stringResource(CoreR.string.ta_playlists),
                detail =
                    stringResource(CoreR.string.ta_stats_subscribed, formatNumber(it.subscribed)),
                value = formatNumber(it.total),
            )
        }
    }
}

/** The download queue, and how many videos came in on each of the last days. */
@Composable
private fun TaDownloadsStatsCard(
    downloads: TaDownloadStats?,
    history: List<TaDownloadDay>?,
    metricSizes: Boolean,
) {
    TaStatsCard(title = stringResource(CoreR.string.ta_stats_downloads)) {
        downloads?.let {
            TaStatLine(
                label = stringResource(CoreR.string.ta_stats_queue),
                detail =
                    if (it.pending > 0) {
                        stringResource(
                            CoreR.string.ta_stats_queue_detail,
                            formatNumber(it.pendingVideos),
                            formatNumber(it.pendingShorts),
                            formatNumber(it.pendingStreams),
                        )
                    } else {
                        null
                    },
                value = formatNumber(it.pending),
            )
            if (it.ignore > 0) {
                TaStatLine(
                    label = stringResource(CoreR.string.ta_stats_ignored),
                    value = formatNumber(it.ignore),
                )
            }
        }
        // The server has no history when nothing came in lately
        val days = history.orEmpty()
        TaStatLine(
            label = stringResource(CoreR.string.ta_stats_history),
            detail =
                if (days.isNotEmpty()) formatSize(days.sumOf { it.mediaSize }, metricSizes)
                else null,
            value = formatNumber(days.sumOf { it.count }),
        )
        TaDownloadChart(days)
    }
}

/** A bar for each of the last eight days, today on the right. */
@Composable
private fun TaDownloadChart(history: List<TaDownloadDay>) {
    val today = LocalDate.now()
    val days = (7 downTo 0).map { today.minusDays(it.toLong()) }
    val counts = history.associate { it.date to it.count }
    val values = days.map { counts[it.toString()] ?: 0L }
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val barHeight = 64.dp

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacings.small),
    ) {
        days.forEachIndexed { index, day ->
            val value = values[index]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (value > 0) formatNumber(value) else "",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(if (value > 0) barHeight * (value.toFloat() / max) else 2.dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(
                                if (value > 0) MaterialTheme.colorScheme.inversePrimary
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                )
                Text(
                    text =
                        if (day == today) stringResource(CoreR.string.ta_stats_today)
                        else day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** The ten biggest channels by videos, playing time or storage. Tapping one opens it. */
@Composable
private fun TaBiggestChannelsCard(
    biggest: Map<TaBiggestOrder, List<TaBiggestChannel>>,
    metricSizes: Boolean,
    onChannelClick: (channelId: String) -> Unit,
) {
    var order by rememberSaveable {
        mutableStateOf(biggest.keys.firstOrNull() ?: TaBiggestOrder.VIDEOS)
    }
    TaStatsCard(title = stringResource(CoreR.string.ta_stats_biggest)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
            TaBiggestOrder.entries
                .filter { it in biggest }
                .forEach { entry ->
                    FilterChip(
                        selected = order == entry,
                        onClick = { order = entry },
                        label = { Text(stringResource(entry.label())) },
                    )
                }
        }
        val channels = biggest[order].orEmpty()
        if (channels.isEmpty()) {
            Text(
                text = stringResource(CoreR.string.ta_stats_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = MaterialTheme.spacings.small),
            )
        }
        val max = (channels.maxOfOrNull { it.value(order) } ?: 0L).coerceAtLeast(1L)
        channels.forEachIndexed { index, channel ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onChannelClick(channel.id) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(MaterialTheme.spacings.small))
                        Text(
                            text = channel.valueText(order, metricSizes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    TaBar(
                        fraction = channel.value(order).toFloat() / max,
                        color = MaterialTheme.colorScheme.inversePrimary,
                        trackColor = Color.Transparent,
                        height = 4.dp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private fun TaBiggestOrder.label(): Int =
    when (this) {
        TaBiggestOrder.VIDEOS -> CoreR.string.ta_stats_by_videos
        TaBiggestOrder.DURATION -> CoreR.string.ta_stats_by_playtime
        TaBiggestOrder.SIZE -> CoreR.string.ta_stats_by_storage
    }

private fun TaBiggestChannel.value(order: TaBiggestOrder): Long =
    when (order) {
        TaBiggestOrder.VIDEOS -> count
        TaBiggestOrder.DURATION -> duration
        TaBiggestOrder.SIZE -> mediaSize
    }

private fun TaBiggestChannel.valueText(order: TaBiggestOrder, metricSizes: Boolean): String =
    when (order) {
        TaBiggestOrder.VIDEOS -> formatNumber(count)
        TaBiggestOrder.DURATION -> formatPlaytime(duration)
        TaBiggestOrder.SIZE -> formatSize(mediaSize, metricSizes)
    }

/** A card of stats with a title. */
@Composable
private fun TaStatsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacings.medium)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = MaterialTheme.spacings.small),
            )
            content()
        }
    }
}

/** A stat's name, with a detail under it, and its value on the right. */
@Composable
private fun TaStatLine(label: String, value: String, detail: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(MaterialTheme.spacings.small))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A rounded bar filled to [fraction]. */
@Composable
private fun TaBar(
    fraction: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(trackColor)
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(color)
        )
    }
}

@Composable
private fun videoCount(count: Long): String =
    pluralStringResource(
        CoreR.plurals.ta_video_count,
        count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        formatNumber(count),
    )

/** 0.2612 as 26.1. */
private fun formatPercent(fraction: Double): String =
    String.format(Locale.getDefault(), "%.1f", fraction * 100)
