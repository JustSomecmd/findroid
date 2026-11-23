package dev.jdtech.jellyfin.presentation.jrwi

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiArchiveStats
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiRanked
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiShowTotals
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiTotals
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiWatchTotals
import dev.jdtech.jellyfin.jrwi.JrwiAuthor
import dev.jdtech.jellyfin.jrwi.JrwiCheckRun
import dev.jdtech.jellyfin.jrwi.JrwiCommentStats
import dev.jdtech.jellyfin.jrwi.JrwiFileCount
import dev.jdtech.jellyfin.jrwi.JrwiStatus
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.tube.TaBar
import dev.jdtech.jellyfin.presentation.tube.carouselPages
import dev.jdtech.jellyfin.presentation.tube.formatBytes
import dev.jdtech.jellyfin.presentation.tube.formatDuration
import dev.jdtech.jellyfin.presentation.tube.formatNumber
import dev.jdtech.jellyfin.presentation.tube.formatPercent
import dev.jdtech.jellyfin.presentation.tube.formatPlaytime
import dev.jdtech.jellyfin.presentation.tube.fullDate
import dev.jdtech.jellyfin.presentation.utils.isWideLayout
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.util.Locale

// The archiver page's numbers, in the purples of JRWI Home's server side, laid out as
// TubeArchivist's stats on its Home's server side: the main ones in a card at the top, the rest in
// cards below, side by side where there's room.

/**
 * The archive's main numbers, in a card as big as the TubeArchivist side's: its posts, storage,
 * playing time and how much was watched. Shows the status's counts until the rest is in.
 */
@Composable
internal fun JrwiStatsHero(stats: JrwiArchiveStats?, status: JrwiStatus?) {
    val accent = MaterialTheme.colorScheme.inversePrimary
    val colorStops =
        arrayOf(
            0.0f to Color.Black.copy(alpha = 0.25f),
            0.5f to Color.Black.copy(alpha = 0.5f),
            1f to Color.Black.copy(alpha = 0.65f),
        )
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacings.small)
    ) {
        val spacing = MaterialTheme.spacings.medium
        val pages = carouselPages(maxWidth, isWideLayout())
        val pageWidth = (maxWidth - spacing * (pages - 1)) / pages
        val width = if (pages >= 3) pageWidth * 2 + spacing else pageWidth
        Box(
            modifier =
                Modifier.width(width)
                    .height(pageWidth / 1.77f)
                    .clip(MaterialTheme.shapes.large)
                    .background(accent)
        ) {
            // The dice of JRWI mode's switch, large and faint, behind the numbers
            Icon(
                painter = painterResource(CoreR.drawable.ic_dice),
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
                                stringResource(CoreR.string.jrwi_stats_archive),
                                stats?.version?.let { "jrwi-archiver $it" },
                            )
                            .joinToString(" · "),
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val posts = stats?.posts ?: status?.counts?.archived ?: 0
                Text(
                    text =
                        pluralStringResource(
                            CoreR.plurals.jrwi_post_count,
                            posts,
                            formatNumber(posts.toLong()),
                        ),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium)
                ) {
                    status?.disk?.archiveBytes?.let {
                        JrwiHeroStat(
                            icon = CoreR.drawable.ic_database,
                            label = stringResource(CoreR.string.ta_stats_storage),
                            value = formatBytes(it),
                        )
                    }
                    stats?.let {
                        JrwiHeroStat(
                            icon = CoreR.drawable.ic_clock,
                            label = stringResource(CoreR.string.ta_stats_playtime),
                            value = formatPlaytime(it.playtime.toLong()),
                        )
                        JrwiHeroStat(
                            icon = CoreR.drawable.ic_film,
                            label = stringResource(CoreR.string.jrwi_stats_episodes),
                            value = formatNumber(it.episodes.toLong()),
                        )
                    }
                }
                stats
                    ?.watch
                    ?.takeIf { it.total > 0 }
                    ?.let { watch ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TaBar(
                                fraction = watch.share.toFloat(),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(MaterialTheme.spacings.small))
                            Text(
                                text =
                                    stringResource(
                                        CoreR.string.ta_stats_watched_share,
                                        formatPercent(watch.share),
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
private fun JrwiHeroStat(@DrawableRes icon: Int, label: String, value: String) {
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

/** The cards under the main numbers, each where it has something to show. */
internal fun LazyGridScope.jrwiStatsCards(
    stats: JrwiArchiveStats,
    status: JrwiStatus?,
    onShowClick: (String) -> Unit,
    onItemClick: (Long) -> Unit,
) {
    if (stats.watch.total > 0) item(key = "watch") { JrwiWatchCard(stats.watch) }
    item(key = "contents") { JrwiContentsCard(stats, status) }
    if (stats.shows.isNotEmpty()) {
        item(key = "shows") { JrwiShowsCard(stats.shows, onShowClick) }
    }
    stats.archivedDays?.let { days ->
        item(key = "archived") { JrwiArchivedCard(days, stats.failedChecks) }
    }
    if (stats.byYear.isNotEmpty()) item(key = "years") { JrwiYearsCard(stats) }
    if (stats.likes > 0 || stats.comments > 0) {
        item(key = "community") { JrwiCommunityCard(stats, onItemClick) }
    }
    item(key = "records") { JrwiRecordsCard(stats, onItemClick) }
    if (stats.bothVersions > 0 || stats.heights.isNotEmpty()) {
        item(key = "versions") { JrwiVersionsCard(stats) }
    }
    // What the archiver counts itself, on archivers that give it
    stats.extra?.let { extra ->
        if (extra.checks.isNotEmpty()) item(key = "checks") { JrwiChecksCard(extra.checks) }
        if (extra.files.isNotEmpty()) item(key = "files") { JrwiFilesCard(extra.files) }
        extra.comments
            ?.takeIf { it.total > 0 }
            ?.let { comments -> item(key = "commenters") { JrwiCommentersCard(comments) } }
    }
}

/** A full-width item, such as the archiver's status at the top. */
internal fun LazyGridScope.jrwiWideItem(key: String, content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }, key = key) { content() }
}

/** How much of the archive was watched or listened to, by playing time. */
@Composable
private fun JrwiWatchCard(watch: JrwiWatchTotals) {
    JrwiStatsCard(title = stringResource(CoreR.string.ta_stats_watch_progress)) {
        Text(
            text = stringResource(CoreR.string.ta_stats_watched_share, formatPercent(watch.share)),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(CoreR.string.jrwi_stats_watch_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        JrwiStatsBar(
            fraction = watch.share.toFloat(),
            modifier = Modifier.padding(vertical = MaterialTheme.spacings.small),
        )
        JrwiStatLine(
            label = stringResource(CoreR.string.jrwi_stats_time_played),
            value = formatPlaytime(watch.played.toLong()),
        )
        JrwiStatLine(
            label = stringResource(CoreR.string.ta_stats_watched),
            detail = episodeCount(watch.watched.count),
            value = formatPlaytime(watch.watched.seconds.toLong()),
        )
        if (watch.started.count > 0) {
            val playedOfStarted = watch.played - watch.watched.seconds
            JrwiStatLine(
                label = stringResource(CoreR.string.jrwi_stats_started),
                detail =
                    episodeCount(watch.started.count) +
                        " · " +
                        stringResource(
                            CoreR.string.jrwi_stats_left,
                            formatPlaytime(
                                (watch.started.seconds - playedOfStarted).toLong().coerceAtLeast(0)
                            ),
                        ),
                value = formatPlaytime(watch.started.seconds.toLong()),
            )
        }
        JrwiStatLine(
            label = stringResource(CoreR.string.ta_stats_unwatched),
            detail = episodeCount(watch.unwatched.count),
            value = formatPlaytime(watch.unwatched.seconds.toLong()),
        )
    }
}

/** What kinds of posts and files the archive holds, and what it couldn't keep. */
@Composable
private fun JrwiContentsCard(stats: JrwiArchiveStats, status: JrwiStatus?) {
    JrwiStatsCard(title = stringResource(CoreR.string.jrwi_stats_contents)) {
        JrwiTotalsLine(stringResource(CoreR.string.jrwi_stats_videos), stats.videos)
        JrwiTotalsLine(stringResource(CoreR.string.jrwi_stats_audio), stats.audio)
        if (stats.youtube.count > 0) {
            JrwiStatLine(
                label = stringResource(CoreR.string.jrwi_stats_youtube),
                // Their sizes are TubeArchivist's copies', from archivers since 0.14.9
                detail =
                    if (stats.youtube.bytes > 0) {
                        stringResource(
                            CoreR.string.jrwi_stats_youtube_detail_size,
                            formatBytes(stats.youtube.bytes),
                            formatPlaytime(stats.youtube.seconds.toLong()),
                        )
                    } else {
                        stringResource(
                            CoreR.string.jrwi_stats_youtube_detail,
                            formatPlaytime(stats.youtube.seconds.toLong()),
                        )
                    },
                value = formatNumber(stats.youtube.count.toLong()),
            )
        }
        if (stats.handedOver > 0) {
            JrwiStatLine(
                label = stringResource(CoreR.string.jrwi_stats_handed_over),
                value = formatNumber(stats.handedOver.toLong()),
            )
        }
        JrwiStatLine(
            label = stringResource(CoreR.string.jrwi_stats_text_posts),
            value = formatNumber(stats.textPosts.toLong()),
        )
        JrwiStatLine(
            label = stringResource(CoreR.string.jrwi_tab_art),
            value = formatNumber(stats.artPosts.toLong()),
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        status?.counts?.let { counts ->
            if (counts.locked > 0) {
                JrwiStatLine(
                    label = stringResource(CoreR.string.jrwi_stats_locked),
                    value = formatNumber(counts.locked.toLong()),
                )
            }
            if (counts.problems > 0) {
                JrwiStatLine(
                    label = stringResource(CoreR.string.jrwi_stats_problems),
                    value = formatNumber(counts.problems.toLong()),
                )
            }
        }
        if (stats.removedFromSite > 0) {
            JrwiStatLine(
                label = stringResource(CoreR.string.jrwi_stats_removed),
                detail = stringResource(CoreR.string.jrwi_stats_removed_detail),
                value = formatNumber(stats.removedFromSite.toLong()),
            )
        }
        val disk = status?.disk
        disk?.archiveBytes?.let { archived ->
            // Thumbnails, pictures, documents, saved pages and the archiver's database
            val other = archived - stats.videos.bytes - stats.audio.bytes
            JrwiStatLine(
                label = stringResource(CoreR.string.ta_stats_storage),
                detail =
                    listOfNotNull(
                            other
                                .takeIf { it > 0 }
                                ?.let {
                                    stringResource(
                                        CoreR.string.jrwi_stats_other_files,
                                        formatBytes(it),
                                    )
                                },
                            disk?.freeBytes?.let {
                                stringResource(CoreR.string.jrwi_stats_free, formatBytes(it))
                            },
                        )
                        .joinToString(" · ")
                        .takeIf { it.isNotEmpty() },
                value = formatBytes(archived),
            )
        }
    }
}

@Composable
private fun JrwiTotalsLine(label: String, totals: JrwiTotals) {
    JrwiStatLine(
        label = label,
        detail =
            if (totals.count > 0) {
                formatBytes(totals.bytes) + " · " + formatPlaytime(totals.seconds.toLong())
            } else {
                null
            },
        value = formatNumber(totals.count.toLong()),
    )
}

private enum class JrwiShowOrder(val label: Int) {
    POSTS(CoreR.string.jrwi_stats_by_posts),
    PLAYTIME(CoreR.string.ta_stats_by_playtime),
    STORAGE(CoreR.string.ta_stats_by_storage),
    WATCHED(CoreR.string.ta_stats_watched),
}

/** The ten biggest shows by posts, playing time or storage, or the most watched. */
@Composable
private fun JrwiShowsCard(shows: List<JrwiShowTotals>, onShowClick: (String) -> Unit) {
    var order by rememberSaveable { mutableStateOf(JrwiShowOrder.POSTS) }
    JrwiStatsCard(title = stringResource(CoreR.string.jrwi_stats_shows)) {
        JrwiStatsChoices(
            choices = JrwiShowOrder.entries.map { it to stringResource(it.label) },
            chosen = order,
            onChoose = { order = it },
        )
        val ranked =
            when (order) {
                JrwiShowOrder.POSTS -> shows.sortedByDescending { it.posts }
                JrwiShowOrder.PLAYTIME -> shows.sortedByDescending { it.seconds }
                JrwiShowOrder.STORAGE -> shows.sortedByDescending { it.bytes }
                JrwiShowOrder.WATCHED ->
                    shows.filter { it.seconds > 0 }.sortedByDescending { it.watchedShare }
            }.take(TOP_SHOWS)
        if (ranked.isEmpty()) JrwiStatsNone()
        val max =
            when (order) {
                JrwiShowOrder.WATCHED -> 1.0
                else -> ranked.maxOfOrNull { it.value(order) }?.coerceAtLeast(1.0) ?: 1.0
            }
        ranked.forEachIndexed { index, show ->
            JrwiRankRow(
                rank = index + 1,
                name = show.name,
                value =
                    when (order) {
                        JrwiShowOrder.POSTS -> formatNumber(show.posts.toLong())
                        JrwiShowOrder.PLAYTIME -> formatPlaytime(show.seconds.toLong())
                        JrwiShowOrder.STORAGE -> formatBytes(show.bytes)
                        JrwiShowOrder.WATCHED -> formatPercent(show.watchedShare) + "%"
                    },
                fraction = (show.value(order) / max).toFloat(),
                onClick = { onShowClick(show.id) },
            )
        }
    }
}

private fun JrwiShowTotals.value(order: JrwiShowOrder): Double =
    when (order) {
        JrwiShowOrder.POSTS -> posts.toDouble()
        JrwiShowOrder.PLAYTIME -> seconds
        JrwiShowOrder.STORAGE -> bytes.toDouble()
        JrwiShowOrder.WATCHED -> watchedShare
    }

private const val TOP_SHOWS = 10

/** Posts archived on each of the last eight days, from the archiver's events. */
@Composable
private fun JrwiArchivedCard(days: List<Pair<LocalDate, Int>>, failedChecks: Int) {
    JrwiStatsCard(title = stringResource(CoreR.string.jrwi_stats_archived)) {
        JrwiStatLine(
            label = stringResource(CoreR.string.jrwi_stats_archived_days),
            value = formatNumber(days.sumOf { it.second }.toLong()),
        )
        if (failedChecks > 0) {
            JrwiStatLine(
                label = stringResource(CoreR.string.jrwi_stats_failed_checks),
                value = formatNumber(failedChecks.toLong()),
            )
        }
        val today = LocalDate.now()
        JrwiBarChart(
            bars =
                days.map { (day, count) ->
                    val label =
                        if (day == today) stringResource(CoreR.string.ta_stats_today)
                        else day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    label to count
                }
        )
    }
}

/** Posts by the year they came out on the site, and the first one. */
@Composable
private fun JrwiYearsCard(stats: JrwiArchiveStats) {
    JrwiStatsCard(title = stringResource(CoreR.string.jrwi_stats_years)) {
        stats.byYear
            .maxByOrNull { it.second }
            ?.let { (year, count) ->
                JrwiStatLine(
                    label = stringResource(CoreR.string.jrwi_stats_busiest_year, year),
                    value = formatNumber(count.toLong()),
                )
            }
        // The last years, so the bars stay wide enough to read
        JrwiBarChart(
            bars = stats.byYear.takeLast(MAX_YEARS).map { (year, count) -> "$year" to count }
        )
    }
}

private const val MAX_YEARS = 10

/** Likes and comments across the archive, and the posts with the most. */
@Composable
private fun JrwiCommunityCard(stats: JrwiArchiveStats, onItemClick: (Long) -> Unit) {
    var byComments by rememberSaveable { mutableStateOf(false) }
    JrwiStatsCard(title = stringResource(CoreR.string.jrwi_stats_community)) {
        JrwiStatLine(
            label = stringResource(CoreR.string.jrwi_stats_likes),
            detail =
                stats.posts
                    .takeIf { it > 0 }
                    ?.let {
                        stringResource(
                            CoreR.string.jrwi_stats_per_post,
                            formatNumber(stats.likes / it),
                        )
                    },
            value = formatNumber(stats.likes),
        )
        JrwiStatLine(
            label = stringResource(CoreR.string.jrwi_comments),
            detail =
                stats.posts
                    .takeIf { it > 0 }
                    ?.let {
                        stringResource(
                            CoreR.string.jrwi_stats_per_post,
                            formatNumber(stats.comments / it),
                        )
                    },
            value = formatNumber(stats.comments),
        )
        JrwiStatsChoices(
            choices =
                listOf(
                    false to stringResource(CoreR.string.jrwi_stats_most_liked),
                    true to stringResource(CoreR.string.jrwi_stats_most_comments),
                ),
            chosen = byComments,
            onChoose = { byComments = it },
            modifier = Modifier.padding(top = MaterialTheme.spacings.small),
        )
        val ranked = if (byComments) stats.mostCommented else stats.mostLiked
        if (ranked.isEmpty()) JrwiStatsNone()
        val max = (ranked.maxOfOrNull { it.value } ?: 0L).coerceAtLeast(1L)
        ranked.forEachIndexed { index, entry ->
            JrwiRankRow(
                rank = index + 1,
                name = entry.post.title,
                value = formatNumber(entry.value),
                fraction = entry.value.toFloat() / max,
                onClick = { onItemClick(entry.post.id) },
            )
        }
    }
}

/** The longest episode, the biggest video, and the first and newest posts. */
@Composable
private fun JrwiRecordsCard(stats: JrwiArchiveStats, onItemClick: (Long) -> Unit) {
    JrwiStatsCard(title = stringResource(CoreR.string.jrwi_stats_records)) {
        stats.longest?.let {
            JrwiRecordLine(
                label = stringResource(CoreR.string.jrwi_stats_longest),
                entry = it,
                value = formatDuration(it.value.toDouble()),
                onItemClick = onItemClick,
            )
        }
        stats.biggestVideo?.let {
            JrwiRecordLine(
                label = stringResource(CoreR.string.jrwi_stats_biggest_video),
                entry = it,
                value = formatBytes(it.value),
                onItemClick = onItemClick,
            )
        }
        stats.first?.let {
            JrwiRecordLine(
                label = stringResource(CoreR.string.jrwi_stats_first_post),
                entry = JrwiRanked(it, 0),
                value = fullDate(it.date).orEmpty(),
                onItemClick = onItemClick,
            )
        }
        stats.newest?.let {
            JrwiRecordLine(
                label = stringResource(CoreR.string.jrwi_stats_newest_post),
                entry = JrwiRanked(it, 0),
                value = fullDate(it.date).orEmpty(),
                onItemClick = onItemClick,
            )
        }
    }
}

@Composable
private fun JrwiRecordLine(
    label: String,
    entry: JrwiRanked,
    value: String,
    onItemClick: (Long) -> Unit,
) {
    JrwiStatLine(
        label = label,
        detail = entry.post.title,
        value = value,
        onClick = { onItemClick(entry.post.id) },
    )
}

/** How the episodes' versions line up, and the archived videos' quality. */
@Composable
private fun JrwiVersionsCard(stats: JrwiArchiveStats) {
    JrwiStatsCard(title = stringResource(CoreR.string.jrwi_stats_versions)) {
        if (stats.bothVersions > 0) {
            JrwiStatLine(
                label = stringResource(CoreR.string.jrwi_stats_both),
                value = formatNumber(stats.bothVersions.toLong()),
            )
            JrwiStatLine(
                label = stringResource(CoreR.string.jrwi_stats_lined_up),
                detail =
                    stringResource(
                        CoreR.string.jrwi_stats_assumed,
                        formatNumber((stats.bothVersions - stats.linedUp).toLong()),
                    ),
                value = formatNumber(stats.linedUp.toLong()),
            )
        }
        if (stats.heights.isNotEmpty()) {
            if (stats.bothVersions > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            val max = (stats.heights.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
            stats.heights.forEach { (height, count) ->
                JrwiRankRow(
                    rank = null,
                    name = "${height}p",
                    value = formatNumber(count.toLong()),
                    fraction = count.toFloat() / max,
                )
            }
        }
    }
}

/** The archiver's last checks: when, how long, what came of them. From its own stats. */
@Composable
private fun JrwiChecksCard(checks: List<JrwiCheckRun>) {
    JrwiStatsCard(title = stringResource(CoreR.string.jrwi_stats_checks)) {
        val lengths = checks.mapNotNull { checkLength(it) }
        if (lengths.isNotEmpty()) {
            JrwiStatLine(
                label = stringResource(CoreR.string.jrwi_stats_check_length),
                value = formatPlaytime(lengths.average().toLong()),
            )
        }
        JrwiStatLine(
            label = stringResource(CoreR.string.jrwi_stats_check_new),
            value = formatNumber(checks.sumOf { it.newItems }.toLong()),
        )
        checks.take(MAX_CHECKS).forEach { check ->
            JrwiStatLine(
                label = fullDate(check.started) ?: "?",
                detail =
                    listOfNotNull(
                            check.result,
                            checkLength(check)?.let { formatPlaytime(it) },
                            check.message?.takeIf { it.isNotBlank() },
                        )
                        .joinToString(" · ")
                        .takeIf { it.isNotEmpty() },
                value = "+" + formatNumber(check.newItems.toLong()),
            )
        }
    }
}

private const val MAX_CHECKS = 5

/** How long a check took, in seconds, or null when it's still running or the times are off. */
private fun checkLength(check: JrwiCheckRun): Long? =
    try {
        val started = OffsetDateTime.parse(check.started ?: return null)
        val finished = OffsetDateTime.parse(check.finished ?: return null)
        Duration.between(started, finished).seconds.takeIf { it >= 0 }
    } catch (_: Exception) {
        null
    }

/** The archive's files by type, as its Admin > Files page counts them. From its own stats. */
@Composable
private fun JrwiFilesCard(files: List<JrwiFileCount>) {
    JrwiStatsCard(title = stringResource(CoreR.string.jrwi_files)) {
        val byType =
            files
                .groupBy { it.type }
                .map { (type, list) ->
                    Triple(type, list.sumOf { it.count }, list.sumOf { it.bytes })
                }
                .sortedByDescending { it.second }
        byType.forEach { (type, count, bytes) ->
            JrwiStatLine(
                label = jrwiFileTypeName(type),
                detail =
                    files
                        .filter { it.type == type && it.ext.isNotBlank() }
                        .sortedByDescending { it.count }
                        .take(MAX_EXTENSIONS)
                        .joinToString(", ") { it.ext } +
                        (if (bytes > 0) " · " + formatBytes(bytes) else ""),
                value = formatNumber(count.toLong()),
            )
        }
    }
}

private const val MAX_EXTENSIONS = 4

/** Who comments most. From the archiver's own stats. */
@Composable
private fun JrwiCommentersCard(comments: JrwiCommentStats) {
    JrwiStatsCard(title = stringResource(CoreR.string.jrwi_stats_commenters)) {
        JrwiStatLine(
            label = stringResource(CoreR.string.jrwi_comments),
            detail =
                stringResource(
                    CoreR.string.jrwi_stats_replies,
                    formatNumber(comments.replies.toLong()),
                ),
            value = formatNumber(comments.total.toLong()),
        )
        if (comments.commenters > 0) {
            JrwiStatLine(
                label = stringResource(CoreR.string.jrwi_stats_members_commented),
                value = formatNumber(comments.commenters.toLong()),
            )
        }
        comments.top.forEachIndexed { index, member ->
            val author =
                JrwiAuthor(
                    id = member.id,
                    name = member.name,
                    avatar = member.avatar,
                    badges = member.badges,
                    color = member.color,
                )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(20.dp),
                )
                JrwiCommentAvatar(author, 28.dp)
                Column(modifier = Modifier.weight(1f)) {
                    JrwiAuthorName(author = author)
                    if (member.likes > 0) {
                        Text(
                            text =
                                stringResource(
                                    CoreR.string.jrwi_stats_member_likes,
                                    formatNumber(member.likes.toLong()),
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = formatNumber(member.count.toLong()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** A card of numbers with a title, on the server side's purple. */
@Composable
internal fun JrwiStatsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = JrwiPurple.Card,
        contentColor = JrwiColors.Text,
        border = BorderStroke(1.dp, JrwiPurple.Line),
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

/** A number's name, with a detail under it, and the number on the right. A tap opens it. */
@Composable
private fun JrwiStatLine(
    label: String,
    value: String,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clip(JrwiShape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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

/** A place in a ranking: its number, its name and value, and a bar for how it compares. */
@Composable
private fun JrwiRankRow(
    rank: Int?,
    name: String,
    value: String,
    fraction: Float,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clip(JrwiShape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        rank?.let {
            Text(
                text = "$it",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(MaterialTheme.spacings.small))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TaBar(
                fraction = fraction,
                color = MaterialTheme.colorScheme.inversePrimary,
                trackColor = Color.Transparent,
                height = 4.dp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A row of choices, the chosen one filled in the server side's purple. */
@Composable
private fun <T> JrwiStatsChoices(
    choices: List<Pair<T, String>>,
    chosen: T,
    onChoose: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        modifier = modifier.padding(bottom = 4.dp),
    ) {
        choices.forEach { (value, name) ->
            val selected = value == chosen
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) JrwiPurple.Ink else JrwiColors.Text,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier =
                    Modifier.clip(JrwiShape)
                        .background(if (selected) JrwiPurple.Accent else JrwiPurple.Input)
                        .clickable { onChoose(value) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** A bar for each value, its count above and its name under it. */
@Composable
private fun JrwiBarChart(bars: List<Pair<String, Int>>) {
    val max = (bars.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val barHeight = 64.dp
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacings.small),
    ) {
        bars.forEach { (label, value) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (value > 0) formatNumber(value.toLong()) else "",
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
                                else JrwiPurple.Input
                            )
                )
                Text(
                    text = label,
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

/** A bar filled to [fraction], in the server side's purple. */
@Composable
private fun JrwiStatsBar(fraction: Float, modifier: Modifier = Modifier) {
    TaBar(
        fraction = fraction,
        color = MaterialTheme.colorScheme.inversePrimary,
        trackColor = JrwiPurple.Input,
        modifier = modifier,
    )
}

@Composable
private fun JrwiStatsNone() {
    Text(
        text = stringResource(CoreR.string.ta_stats_none),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = MaterialTheme.spacings.small),
    )
}

@Composable
private fun episodeCount(count: Int): String =
    pluralStringResource(CoreR.plurals.jrwi_episode_count, count, formatNumber(count.toLong()))
