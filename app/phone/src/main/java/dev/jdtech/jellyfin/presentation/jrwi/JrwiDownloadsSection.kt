package dev.jdtech.jellyfin.presentation.jrwi

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiDownloadEntry
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiEpisodeNumber
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiTaVideo
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiProgress
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.presentation.film.components.BaseBadge
import dev.jdtech.jellyfin.presentation.film.components.CollectionSectionHeader
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.DownloadedBadge
import dev.jdtech.jellyfin.presentation.film.components.ItemCard
import dev.jdtech.jellyfin.presentation.film.components.PlayedBadge
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.tube.formatBytes
import dev.jdtech.jellyfin.presentation.utils.LocalEpisodeTitleFirst
import kotlin.math.roundToInt

/**
 * The JRWI episodes whose audio is on the phone, or on its way, on the Downloads page. Tapping one
 * opens its page, which works without a connection from what was kept with the audio.
 */
@Composable
fun JrwiDownloadsSection(
    entries: List<JrwiDownloadEntry>,
    onClick: (itemId: Long) -> Unit,
    onRemove: (itemId: Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
        Text(
            text = stringResource(CoreR.string.jrwi_downloads),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = MaterialTheme.spacings.small),
        )
        entries.forEach { entry ->
            JrwiDownloadListRow(
                entry = entry,
                // On the audio, which is what's downloaded
                onClick = { onClick(entry.download.audioPostId) },
                onRemove = { onRemove(entry.download.itemId) },
            )
        }
    }
}

@Composable
private fun JrwiDownloadListRow(
    entry: JrwiDownloadEntry,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val download = entry.download
    val progress = entry.progress
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        modifier =
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = entry.picture,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier.width(120.dp)
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = download.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    when {
                        download.done ->
                            listOfNotNull(download.showName, download.size?.let { formatBytes(it) })
                                .joinToString(" · ")
                        download.failed -> stringResource(CoreR.string.jrwi_download_failed)
                        progress != null ->
                            stringResource(
                                CoreR.string.jrwi_download_running,
                                (progress * 100).toInt(),
                            )
                        else -> stringResource(CoreR.string.jrwi_download_waiting)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!download.done && !download.failed) {
                val bar = Modifier.fillMaxWidth().padding(top = 4.dp)
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = bar)
                } else {
                    LinearProgressIndicator(modifier = bar)
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(painterResource(CoreR.drawable.ic_more_vertical), contentDescription = null)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (download.done) CoreR.string.jrwi_download_remove
                                else CoreR.string.jrwi_download_cancel
                            )
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
}

// JRWI mode's Downloads, by show

/** A card on JRWI mode's Downloads: an archived video through Jellyfin, or an episode's audio. */
sealed interface JrwiDownloadCard {
    val key: String

    /** [jrwi] is the item kept with the video, when the app has it. */
    data class Video(val item: FindroidItem, val jrwi: JrwiItem? = null) : JrwiDownloadCard {
        override val key: String
            get() = item.id.toString()
    }

    data class Audio(val entry: JrwiDownloadEntry) : JrwiDownloadCard {
        override val key: String
            get() = "audio:" + entry.download.itemId
    }
}

/** One show's downloads, its archived videos and audio together, in episode order. */
data class JrwiShowDownloads(val name: String, val cards: List<JrwiDownloadCard>)

/**
 * JRWI mode's downloads by show, as TubeArchivist's are by channel: the shows by name, each with
 * its numbered episodes first, season by season with an episode's video before its audio, then the
 * rest by title. [other] names the audio of posts without a show. A video's watched mark and bar
 * show how far the archive has it played ([videoWatch], by episode ID), as its page does, in place
 * of the position kept with its Jellyfin download. TubeArchivist's downloads of JRWI episodes
 * ([tubeArchivist], by episode ID) go under their show with their number, which Jellyfin doesn't
 * know them by, as it files them under the channel. [videoItems] gives the item kept with each
 * video, by episode ID, for the label of the versions on its card.
 */
fun jrwiDownloadsByShow(
    videos: List<CollectionSection>,
    audio: List<JrwiDownloadEntry>,
    other: String,
    videoWatch: Map<String, JrwiProgress> = emptyMap(),
    tubeArchivist: Map<String, JrwiTaVideo> = emptyMap(),
    videoItems: Map<String, JrwiItem> = emptyMap(),
): List<JrwiShowDownloads> {
    data class Sorted(val number: JrwiEpisodeNumber?, val audio: Boolean, val title: String)
    val byShow = mutableMapOf<String, MutableList<Pair<Sorted, JrwiDownloadCard>>>()
    videos
        .flatMap { it.items }
        .forEach { item ->
            val episode = item as? FindroidEpisode
            val fromTa = episode?.let { tubeArchivist[it.id.toString()] }
            // Season 0 holds the specials, which the archiver doesn't number as episodes
            val number =
                if (fromTa != null) fromTa.number
                else
                    episode
                        ?.takeIf { it.parentIndexNumber > 0 }
                        ?.let { JrwiEpisodeNumber(it.parentIndexNumber, it.indexNumber) }
            val watch = episode?.let { videoWatch[it.id.toString()] }
            val shown = if (episode != null && watch != null) episode.playedAs(watch) else item
            val show =
                if (fromTa != null) fromTa.showName?.takeIf { it.isNotBlank() } ?: other
                else episode?.seriesName ?: other
            byShow
                .getOrPut(show) { mutableListOf() }
                .add(
                    Sorted(number, false, fromTa?.title ?: item.name) to
                        JrwiDownloadCard.Video(shown, videoItems[item.id.toString()])
                )
        }
    // Those still to come are in the queue at the top, until they're on the phone
    audio
        .filter { it.download.done }
        .forEach { entry ->
            byShow
                .getOrPut(entry.download.showName?.takeIf { it.isNotBlank() } ?: other) {
                    mutableListOf()
                }
                .add(
                    Sorted(entry.number, true, entry.download.title) to
                        JrwiDownloadCard.Audio(entry)
                )
        }
    val order =
        compareBy<Pair<Sorted, JrwiDownloadCard>>(
            { it.first.number == null },
            { it.first.number?.season ?: 0 },
            { it.first.number?.episode ?: 0 },
            { if (it.first.number == null) it.first.title.lowercase() else "" },
            { it.first.audio },
        )
    return byShow.entries
        .sortedBy { it.key.lowercase() }
        .map { (name, cards) -> JrwiShowDownloads(name, cards.sortedWith(order).map { it.second }) }
}

/** The episode as far played as [watch] says, in the video's timeline. */
private fun FindroidEpisode.playedAs(watch: JrwiProgress): FindroidEpisode =
    copy(
        played = watch.finished,
        playbackPositionTicks =
            if (watch.finished) 0L else (watch.position * TICKS_PER_SECOND).toLong(),
    )

private const val TICKS_PER_SECOND = 10_000_000.0

/** The shows' sections on JRWI mode's Downloads: a heading for each, then its cards. */
fun LazyGridScope.jrwiShowSections(
    shows: List<JrwiShowDownloads>,
    onVideoClick: (FindroidItem) -> Unit,
    onAudioClick: (postId: Long) -> Unit,
    onAudioRemove: (itemId: Long) -> Unit,
) {
    shows.forEachIndexed { index, show ->
        item(span = { GridItemSpan(maxLineSpan) }, key = "jrwi_show:" + show.name) {
            CollectionSectionHeader(name = show.name, first = index == 0)
        }
        items(show.cards, key = { it.key }) { card ->
            when (card) {
                is JrwiDownloadCard.Video ->
                    ItemCard(
                        item = card.item,
                        direction = Direction.HORIZONTAL,
                        onClick = onVideoClick,
                        modifier = Modifier.animateItem(),
                        // The version label in the corner says it's on the phone, in yellow
                        downloadedBadge = card.jrwi == null,
                    ) {
                        card.jrwi?.let { jrwi ->
                            val video = card.item
                            JrwiDownloadLabel(
                                item = jrwi,
                                videoOnPhone = video.isDownloaded(),
                                audioOnPhone = null,
                                duration = jrwi.video?.duration,
                                // Over the bar for how far it was played, when it has one
                                raised = video.runtimeTicks > 0 && video.playbackPositionTicks > 0,
                            )
                        }
                    }
                is JrwiDownloadCard.Audio ->
                    JrwiAudioDownloadCard(
                        entry = card.entry,
                        // On the audio, which is what's downloaded
                        onClick = { onAudioClick(card.entry.download.audioPostId) },
                        onRemove = { onAudioRemove(card.entry.download.itemId) },
                        modifier = Modifier.animateItem(),
                    )
            }
        }
    }
}

/**
 * An episode's audio on JRWI mode's Downloads, as the video cards beside it: its picture with a
 * headphones badge, and the downloaded badge once it's all on the phone, or a bar while it comes. A
 * long press offers to remove or cancel it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JrwiAudioDownloadCard(
    entry: JrwiDownloadEntry,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val download = entry.download
    val progress = entry.progress
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier =
            modifier
                .width(AUDIO_CARD_WIDTH.dp)
                .clip(MaterialTheme.shapes.small)
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
    ) {
        Surface(shape = MaterialTheme.shapes.small) {
            Box {
                AsyncImage(
                    model = entry.picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier.fillMaxWidth()
                            .aspectRatio(1.77f)
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                )
                val watch = entry.watch
                Row(
                    modifier =
                        Modifier.align(Alignment.TopEnd).padding(MaterialTheme.spacings.small),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                ) {
                    // On the phone or not, the version label in the corner says, in yellow
                    if (download.done && entry.item == null) DownloadedBadge()
                    // Watched, as the video cards beside it show it
                    if (watch?.finished == true) PlayedBadge()
                    BaseBadge {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_headphones),
                            contentDescription = stringResource(CoreR.string.jrwi_audio),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp).align(Alignment.Center),
                        )
                    }
                }
                if (!download.done && !download.failed) {
                    val bar =
                        Modifier.align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(MaterialTheme.spacings.small)
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = bar)
                    } else {
                        LinearProgressIndicator(modifier = bar)
                    }
                } else {
                    // How far it was played, as the video cards' bar: a share of the card's real
                    // width, which in the grid is its column's rather than its own 260 dp
                    val length = watch?.duration?.takeIf { it > 0 }
                    if (watch != null && !watch.finished && length != null && watch.position > 0) {
                        val played = (watch.position / length).coerceIn(0.0, 1.0).toFloat()
                        Box(
                            modifier =
                                Modifier.align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .padding(MaterialTheme.spacings.small)
                        ) {
                            Box(
                                modifier =
                                    Modifier.height(4.dp)
                                        .fillMaxWidth(played)
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
                entry.item?.let { item ->
                    val length = watch?.duration?.takeIf { it > 0 }
                    JrwiDownloadLabel(
                        item = item,
                        videoOnPhone = null,
                        audioOnPhone = download.done,
                        duration = item.audio?.duration,
                        // Over the bar while it downloads, or for how far it was played
                        raised =
                            (!download.done && !download.failed) ||
                                (watch != null &&
                                    !watch.finished &&
                                    length != null &&
                                    watch.position > 0),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (download.done) CoreR.string.jrwi_download_remove
                                    else CoreR.string.jrwi_download_cancel
                                )
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacings.extraSmall))
        // Named as the video cards are: the episode's title or its show first, as set
        val status =
            when {
                download.failed -> stringResource(CoreR.string.jrwi_download_failed)
                download.done -> null
                progress != null ->
                    stringResource(CoreR.string.jrwi_download_running, (progress * 100).toInt())
                else -> stringResource(CoreR.string.jrwi_download_waiting)
            }
        val show = download.showName?.takeIf { it.isNotBlank() }
        val titleFirst = LocalEpisodeTitleFirst.current || show == null
        Text(
            text = if (titleFirst) download.title else show.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle =
            listOfNotNull(
                    if (titleFirst) show else download.title,
                    status ?: stringResource(CoreR.string.jrwi_audio),
                )
                .joinToString(" · ")
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
    }
}

// As wide as the video cards beside them
private const val AUDIO_CARD_WIDTH = 260

/**
 * The version label every JRWI post's picture has, on a download's card: a film when [item] has a
 * video, headphones when it has audio, and the card's own length, each drawn yellow when it's on
 * the phone. The card's own version says itself whether it's on the phone ([videoOnPhone] or
 * [audioOnPhone]); for the other, null, the app's record of what's on the phone says. [raised]
 * lifts it over a bar along the bottom, which on these cards sits a little in from the edge.
 */
@Composable
private fun BoxScope.JrwiDownloadLabel(
    item: JrwiItem,
    videoOnPhone: Boolean?,
    audioOnPhone: Boolean?,
    duration: Double?,
    raised: Boolean,
) {
    val onPhone = jrwiOnPhone()
    // The card's own version, whatever the kept item says
    val video = videoOnPhone != null || item.video != null
    val audio = audioOnPhone != null || item.audio != null
    JrwiVersionLabel(
        video = video,
        audio = audio,
        videoOnPhone = video && (videoOnPhone ?: onPhone.videoOnPhone(item.id)),
        audioOnPhone = audio && (audioOnPhone ?: onPhone.audioOnPhone(item.id)),
        duration = duration ?: item.duration,
        // In line with the badges at the top, which sit 8 dp in; the label keeps 6 dp of its own
        modifier =
            Modifier.align(Alignment.BottomEnd)
                .padding(end = 2.dp, bottom = if (raised) LABEL_OVER_BAR else 2.dp),
    )
}

// The bar's 8 dp from the bottom and 4 dp height, and a little room above it, less the label's 6 dp
private val LABEL_OVER_BAR = 10.dp

/**
 * The JRWI audio still to come, for the queue at the top of Downloads: the one downloading first,
 * then those waiting in the queue's order, then those that failed.
 */
fun jrwiQueued(entries: List<JrwiDownloadEntry>): List<JrwiDownloadEntry> =
    entries
        .filter { !it.download.done }
        .sortedWith(
            compareBy<JrwiDownloadEntry>({
                    when {
                        it.download.failed -> 2
                        it.progress != null -> 0
                        else -> 1
                    }
                })
                .thenBy { it.download.addedAt }
        )

/**
 * A JRWI episode's audio in the queue at the top of Downloads, as the video downloads there are:
 * its picture (headphones until it has one), title, show, and how it's going, with a bar while it
 * downloads. A tap opens its page, where it's tried again or removed.
 */
@Composable
fun JrwiQueueRow(entry: JrwiDownloadEntry, onClick: () -> Unit) {
    val download = entry.download
    val progress = entry.progress
    val status =
        when {
            download.failed -> stringResource(CoreR.string.download_failed)
            progress != null ->
                stringResource(CoreR.string.download_downloading) +
                    " · ${(progress * 100).roundToInt()}%"
            else -> stringResource(CoreR.string.download_queued)
        }
    Row(
        modifier =
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.height(64.dp)
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (entry.picture != null) {
                AsyncImage(
                    model = entry.picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_headphones),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(MaterialTheme.spacings.medium))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
        ) {
            Text(
                text = download.title.ifBlank { stringResource(CoreR.string.jrwi_audio) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            download.showName
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        text = it,
                        modifier = Modifier.alpha(0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            Text(
                text = status,
                color =
                    if (download.failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                // Digits of equal width, so the line doesn't shift as the numbers change
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            )
            if (progress != null && !download.failed) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
