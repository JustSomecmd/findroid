package dev.jdtech.tubedroid.desktop.screens

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaBiggestChannel
import dev.jdtech.jellyfin.tubearchivist.TaBiggestOrder
import dev.jdtech.jellyfin.tubearchivist.TaCountStats
import dev.jdtech.jellyfin.tubearchivist.TaDownloadDay
import dev.jdtech.jellyfin.tubearchivist.TaDownloadStats
import dev.jdtech.jellyfin.tubearchivist.TaVideoStats
import dev.jdtech.jellyfin.tubearchivist.TaVideoTotals
import dev.jdtech.jellyfin.tubearchivist.TaWatchStats
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.errorText
import dev.jdtech.tubedroid.desktop.ui.formatNumber
import dev.jdtech.tubedroid.desktop.ui.formatPlaytime
import dev.jdtech.tubedroid.desktop.ui.formatSize
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The numbers on TubeArchivist's dashboard. Each part is null when it couldn't be loaded. */
data class TaServerStats(
    val videos: TaVideoStats? = null,
    val watch: TaWatchStats? = null,
    val channels: TaCountStats? = null,
    val playlists: TaCountStats? = null,
    val downloads: TaDownloadStats? = null,
    val history: List<TaDownloadDay>? = null,
    val biggest: Map<TaBiggestOrder, List<TaBiggestChannel>> = emptyMap(),
    /** Sizes in GB rather than GiB, as set in TubeArchivist. */
    val metricSizes: Boolean = false,
    val loading: Boolean = false,
    /** True once the stats were asked for, whether or not they came. */
    val loaded: Boolean = false,
    /** Set when nothing at all could be loaded. */
    val error: String? = null,
)

/** Loads the server's stats, all at once, each part on its own. */
class StatsModel(private val api: TaApi, private val scope: CoroutineScope) {
    var stats by mutableStateOf(TaServerStats())
        private set

    private var lastLoad = 0L

    /** Loads the stats the first time they're shown, and again when they're a minute old. */
    fun onShown() {
        if (stats.loading) return
        if (System.currentTimeMillis() - lastLoad < 60_000L && stats.error == null) return
        refresh()
    }

    fun refresh() {
        lastLoad = System.currentTimeMillis()
        stats = stats.copy(loading = true)
        scope.launch {
            coroutineScope {
                val videos = async { part { api.getVideoStats() } }
                val watch = async { part { api.getWatchStats() } }
                val channels = async { part { api.getChannelStats() } }
                val playlists = async { part { api.getPlaylistStats() } }
                val downloads = async { part { api.getDownloadStats() } }
                val history = async { part { api.getDownloadHistory() } }
                val biggest =
                    TaBiggestOrder.entries.map { order ->
                        async { order to part { api.getBiggestChannels(order) } }
                    }
                val metric = async { api.usesMetricSizes() }
                val parts =
                    listOf(videos, watch, channels, playlists, downloads, history).awaitAll()
                stats =
                    TaServerStats(
                        videos = videos.await().getOrNull(),
                        watch = watch.await().getOrNull(),
                        channels = channels.await().getOrNull(),
                        playlists = playlists.await().getOrNull(),
                        downloads = downloads.await().getOrNull(),
                        history = history.await().getOrNull(),
                        biggest =
                            biggest
                                .awaitAll()
                                .mapNotNull { (order, result) ->
                                    result.getOrNull()?.let { order to it }
                                }
                                .toMap(),
                        metricSizes = metric.await(),
                        loaded = true,
                        error =
                            if (parts.all { it.isFailure }) {
                                parts.first().exceptionOrNull()?.let(::errorText)
                            } else {
                                null
                            },
                    )
            }
        }
    }

    private suspend fun <T> part(load: suspend () -> T): Result<T> =
        try {
            Result.success(load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}

/**
 * Home's server side: TubeArchivist's numbers, as on its dashboard. The main ones sit in a card the
 * size of the channel carousel on the other side, with the rest in cards below.
 */
@Composable
fun TaHomeStatsPage(
    stats: TaServerStats,
    version: String?,
    onRefresh: () -> Unit,
    onChannelClick: (channelId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        when {
            stats.error != null ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center).padding(Spacings.default),
                ) {
                    Text(
                        text = stats.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onRefresh) { Text("Retry") }
                }
            !stats.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else ->
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 360.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spacings.default),
                    horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacings.medium),
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
    // Where the channel carousel is on the other side, as tall as its pages
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val spacing = Spacings.medium
        val pages = carouselPages(maxWidth)
        val pageWidth = (maxWidth - spacing * (pages - 1)) / pages
        // Two pages wide when three fit, so the numbers have room
        val width = if (pages >= 3) pageWidth * 2 + spacing else pageWidth
        Box(
            modifier =
                Modifier.width(width)
                    .height(pageWidth / 1.77f)
                    .clip(MaterialTheme.shapes.large)
                    .background(accent)
        ) {
            // The logo's ring, large and faint, behind the numbers
            AppIcon(
                name = "ic_ta_logo_ring",
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.22f),
                modifier =
                    Modifier.size(150.dp).align(Alignment.TopEnd).offset(x = 30.dp, y = (-24).dp),
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(brush = Brush.verticalGradient(colorStops = colorStops))
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacings.small),
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            horizontal = Spacings.default,
                            vertical = Spacings.medium,
                        )
                        .align(Alignment.BottomStart),
            ) {
                Text(
                    text =
                        listOfNotNull(
                                "Your archive",
                                version?.let { "TubeArchivist $it" },
                            )
                            .joinToString(" · "),
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = videoCount(videos.count),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacings.medium)) {
                    TaHeroStat(
                        icon = "ic_database",
                        label = "Storage used",
                        value = formatSize(videos.mediaSize, stats.metricSizes),
                    )
                    TaHeroStat(
                        icon = "ic_clock",
                        label = "Playing time",
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
                        Spacer(Modifier.width(Spacings.small))
                        Text(
                            text = "${formatPercent(watched.progress)}% watched",
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
private fun TaHeroStat(icon: String, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(
            name = icon,
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
    TaStatsCard(title = "Watch progress") {
        val progress = watch.watched?.progress ?: 0.0
        Text(
            text = "${formatPercent(progress)}% watched",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "By playing time, out of everything on the server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TaBar(
            fraction = progress.toFloat(),
            color = MaterialTheme.colorScheme.inversePrimary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.padding(vertical = Spacings.small),
        )
        watch.watched?.let {
            TaStatLine(
                label = "Watched",
                detail = videoCount(it.items),
                value = formatPlaytime(it.duration),
            )
        }
        watch.unwatched?.let {
            TaStatLine(
                label = "Unwatched",
                detail = videoCount(it.items),
                value = formatPlaytime(it.duration),
            )
        }
    }
}

/** Regular videos, shorts and streams, and what can't be found on YouTube any more. */
@Composable
private fun TaVideoTypesCard(videos: TaVideoStats, metricSizes: Boolean) {
    TaStatsCard(title = "Video types") {
        val types =
            listOf(
                "Regular videos" to videos.regular,
                "Shorts" to videos.shorts,
                "Streams" to videos.streams,
            )
        types.forEach { (label, totals) ->
            TaTotalsLine(label, totals ?: TaVideoTotals(), metricSizes)
        }
        videos.inactive
            ?.takeIf { it.count > 0 }
            ?.let { removed ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                TaTotalsLine("Removed from YouTube", removed, metricSizes)
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
    TaStatsCard(title = "Channels and playlists") {
        channels?.let {
            TaStatLine(
                label = "Channels",
                detail = "${formatNumber(it.subscribed)} subscribed",
                value = formatNumber(it.total),
            )
        }
        playlists?.let {
            TaStatLine(
                label = "Playlists",
                detail = "${formatNumber(it.subscribed)} subscribed",
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
    TaStatsCard(title = "Downloads") {
        downloads?.let {
            TaStatLine(
                label = "Waiting in the queue",
                detail =
                    if (it.pending > 0) {
                        "Videos: ${formatNumber(it.pendingVideos)} · Shorts: ${formatNumber(it.pendingShorts)} · Streams: ${formatNumber(it.pendingStreams)}"
                    } else {
                        null
                    },
                value = formatNumber(it.pending),
            )
            if (it.ignore > 0) {
                TaStatLine(
                    label = "Ignored",
                    value = formatNumber(it.ignore),
                )
            }
        }
        // The server has no history when nothing came in lately
        val days = history.orEmpty()
        TaStatLine(
            label = "Downloaded in the last 8 days",
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
        modifier = Modifier.fillMaxWidth().padding(top = Spacings.small),
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
                        if (day == today) "Today"
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
    TaStatsCard(title = "Biggest channels") {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacings.small)) {
            TaBiggestOrder.entries
                .filter { it in biggest }
                .forEach { entry ->
                    FilterChip(
                        selected = order == entry,
                        onClick = { order = entry },
                        label = { Text(entry.label()) },
                    )
                }
        }
        val channels = biggest[order].orEmpty()
        if (channels.isEmpty()) {
            Text(
                text = "Nothing to show yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacings.small),
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
                        Spacer(Modifier.width(Spacings.small))
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

private fun TaBiggestOrder.label(): String =
    when (this) {
        TaBiggestOrder.VIDEOS -> "Videos"
        TaBiggestOrder.DURATION -> "Playing time"
        TaBiggestOrder.SIZE -> "Storage"
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
        Spacer(Modifier.width(Spacings.small))
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

/** "1 video", "5,890 videos". */
fun videoCount(count: Long): String = "${formatNumber(count)} video${if (count == 1L) "" else "s"}"

/** 0.2612 as 26.1. */
private fun formatPercent(fraction: Double): String =
    String.format(Locale.getDefault(), "%.1f", fraction * 100)

/** How many cards sit side by side: one in a narrow window, up to three in a wide one. */
private fun carouselPages(width: Dp): Int =
    when {
        width >= 840.dp -> 3
        width >= 600.dp -> 2
        else -> 1
    }
