package dev.jdtech.jellyfin.film.presentation.jrwi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.presentation.tube.errorText
import dev.jdtech.jellyfin.jrwi.JrwiApi
import dev.jdtech.jellyfin.jrwi.JrwiApiException
import dev.jdtech.jellyfin.jrwi.JrwiEvent
import dev.jdtech.jellyfin.jrwi.JrwiFeature
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiPage
import dev.jdtech.jellyfin.jrwi.JrwiShow
import dev.jdtech.jellyfin.jrwi.JrwiStats
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** How many files, how big and how long. */
data class JrwiTotals(val count: Int = 0, val bytes: Long = 0, val seconds: Double = 0.0) {
    operator fun plus(other: JrwiTotals) =
        JrwiTotals(count + other.count, bytes + other.bytes, seconds + other.seconds)
}

/** How much of the archive's episodes were watched or listened to, by playing time. */
data class JrwiWatchTotals(
    /** Finished, with their playing time. */
    val watched: JrwiTotals = JrwiTotals(),
    /** Started and not finished, with the time played so far in [played]. */
    val started: JrwiTotals = JrwiTotals(),
    val unwatched: JrwiTotals = JrwiTotals(),
    /** The time played, in finished episodes and in those started. */
    val played: Double = 0.0,
) {
    val total: Double
        get() = watched.seconds + started.seconds + unwatched.seconds

    /** From 0 to 1, by playing time. */
    val share: Double
        get() = if (total > 0) (played / total).coerceIn(0.0, 1.0) else 0.0
}

/** One show's part of the archive. */
data class JrwiShowTotals(
    val id: String,
    val name: String,
    /** Posts whose own show this is. */
    val posts: Int = 0,
    /** Items with a video or audio. */
    val episodes: Int = 0,
    /** The playing time of its episodes. */
    val seconds: Double = 0.0,
    /** Its archived video and audio files. */
    val bytes: Long = 0,
    /** The time played of its episodes. */
    val played: Double = 0.0,
) {
    val watchedShare: Double
        get() = if (seconds > 0) (played / seconds).coerceIn(0.0, 1.0) else 0.0
}

/** A post with the number it's ranked by. */
data class JrwiRanked(val post: JrwiItem, val value: Long)

/** The numbers worked out from the archive's lists, and those the archiver gives itself. */
data class JrwiArchiveStats(
    val version: String? = null,
    /** Posts in the user's tier. */
    val posts: Int = 0,
    /** Items with a video or audio. */
    val episodes: Int = 0,
    /** The playing time of every episode, each once whatever its versions. */
    val playtime: Double = 0.0,
    val watch: JrwiWatchTotals = JrwiWatchTotals(),
    /** Members-only videos kept in the archive (and in Jellyfin). */
    val videos: JrwiTotals = JrwiTotals(),
    val audio: JrwiTotals = JrwiTotals(),
    /** Videos only on YouTube, which play from TubeArchivist. */
    val youtube: JrwiTotals = JrwiTotals(),
    /** Members-only videos deleted since TubeArchivist has them from YouTube. */
    val handedOver: Int = 0,
    /** Posts without a video or audio, outside Art. */
    val textPosts: Int = 0,
    val artPosts: Int = 0,
    /** Posts the site has taken down, which the archive keeps. */
    val removedFromSite: Int = 0,
    /** Episodes with both a video and an audio version. */
    val bothVersions: Int = 0,
    /** Of those, the ones the archiver lined up itself. */
    val linedUp: Int = 0,
    /** Archived videos by height, highest first. */
    val heights: List<Pair<Int, Int>> = emptyList(),
    val likes: Long = 0,
    val comments: Long = 0,
    val mostLiked: List<JrwiRanked> = emptyList(),
    val mostCommented: List<JrwiRanked> = emptyList(),
    val longest: JrwiRanked? = null,
    val biggestVideo: JrwiRanked? = null,
    val first: JrwiItem? = null,
    val newest: JrwiItem? = null,
    /** Posts by the year they came out, oldest first. */
    val byYear: List<Pair<Int, Int>> = emptyList(),
    /** Shows by posts, most first. */
    val shows: List<JrwiShowTotals> = emptyList(),
    /** Posts archived on each of the last eight days, today last; null when not known. */
    val archivedDays: List<Pair<LocalDate, Int>>? = null,
    /** Checks that failed in the last eight days. */
    val failedChecks: Int = 0,
    /** What the archiver counts itself (suggested); null on archivers without it. */
    val extra: JrwiStats? = null,
)

data class JrwiStatsState(
    val stats: JrwiArchiveStats? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * The archive's numbers for the archiver page, as TubeArchivist's stats on its Home's server side.
 * The archiver has no stats of its own yet, so they're worked out from its lists of posts and
 * items, a few pages of 200 each, and its events.
 */
@HiltViewModel
class JrwiStatsViewModel @Inject constructor(private val jrwiApi: JrwiApi) : ViewModel() {
    private val _state = MutableStateFlow(JrwiStatsState())
    val state = _state.asStateFlow()

    private var lastLoad = 0L

    /** Loads the numbers the first time they're shown, and again once they're a while old. */
    fun onShown() {
        val state = _state.value
        if (state.loading) return
        if (
            System.currentTimeMillis() - lastLoad < STALE_AFTER_MS &&
                state.error == null &&
                state.stats != null
        ) {
            return
        }
        refresh()
    }

    fun refresh() {
        if (!jrwiApi.isConfigured) return
        lastLoad = System.currentTimeMillis()
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val stats = load()
                _state.update { JrwiStatsState(stats = stats, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "The archive's stats could not be loaded")
                _state.update { it.copy(loading = false, error = e.errorText()) }
            }
        }
    }

    private suspend fun load(): JrwiArchiveStats = coroutineScope {
        val posts = async { allPages { jrwiApi.getPosts(page = it, pageSize = LIST_PAGE) } }
        val items = async { allPages { jrwiApi.search(page = it, pageSize = LIST_PAGE) } }
        val shows = async { optional("shows") { jrwiApi.getShows() } }
        val version = async { optional("ping") { jrwiApi.getPing().version } }
        val since = LocalDate.now().minusDays(STATS_DAYS - 1L)
        val events = async {
            optional("events") {
                val start = since.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime()
                // As the archiver writes its times, which it compares as text
                jrwiApi.getEvents(since = start.withOffsetSameInstant(ZoneOffset.UTC).format(ISO))
            }
        }
        val extra = async {
            try {
                // Not asked of an archiver that lists its features without its own stats
                if (jrwiApi.has(JrwiFeature.STATS) == false) null else jrwiApi.getStats()
            } catch (e: CancellationException) {
                throw e
            } catch (e: JrwiApiException) {
                // Archivers without their own stats answer 404
                if (e.code != NOT_FOUND) Timber.d(e, "The archiver's own stats")
                null
            } catch (e: Exception) {
                Timber.d(e, "The archiver's own stats")
                null
            }
        }
        val postList = posts.await()
        val itemList = items.await()
        val showList = shows.await().orEmpty()
        val eventList = events.await()
        withContext(Dispatchers.Default) {
            archiveStats(postList, itemList, showList, eventList, since)
                .copy(version = version.await(), extra = extra.await())
        }
    }

    /** Every page of a list, the first to learn how many there are, then the rest together. */
    private suspend fun <T> allPages(load: suspend (Int) -> JrwiPage<T>): List<T> = coroutineScope {
        val first = load(1)
        val pages =
            ceil(first.total.toDouble() / first.pageSize.coerceAtLeast(1))
                .toInt()
                .coerceIn(1, MAX_PAGES)
        first.items + (2..pages).map { async { load(it).items } }.awaitAll().flatten()
    }

    private suspend fun <T> optional(name: String, load: suspend () -> T): T? =
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Could not load the $name for the stats")
            null
        }

    private companion object {
        const val LIST_PAGE = 200
        const val MAX_PAGES = 100
        const val STALE_AFTER_MS = 5 * 60_000L
        const val NOT_FOUND = 404
        val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")
    }
}

/** How far an item was played, in seconds, and whether it's finished. */
private fun JrwiItem.playedSeconds(length: Double): Double {
    val progress = progress ?: return 0.0
    if (progress.finished) return length
    return progress.position.coerceIn(0.0, length)
}

private fun JrwiItem.year(): Int? = date?.take(4)?.toIntOrNull()

private fun localDate(iso: String): LocalDate? =
    try {
        OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
    } catch (_: Exception) {
        null
    }

/** The numbers, from every post, every item, the shows and the last days' events. */
internal fun archiveStats(
    posts: List<JrwiItem>,
    items: List<JrwiItem>,
    shows: List<JrwiShow>,
    events: List<JrwiEvent>?,
    since: LocalDate,
): JrwiArchiveStats {
    val showNames = shows.associate { it.id to it.name }

    // Each file once: posts carry only their own video or audio
    var videos = JrwiTotals()
    var audio = JrwiTotals()
    var youtube = JrwiTotals()
    val heights = mutableMapOf<Int, Int>()
    posts.forEach { post ->
        val video = post.video
        if (video != null && video.jellyfinPath != null) {
            videos += JrwiTotals(1, video.size ?: 0L, video.duration ?: 0.0)
            video.height?.let { heights[it] = (heights[it] ?: 0) + 1 }
        } else if (video != null && video.youtubeId != null) {
            youtube += JrwiTotals(1, 0, video.duration ?: 0.0)
        }
        post.audio?.let { audio += JrwiTotals(1, it.size ?: 0L, it.duration ?: 0.0) }
    }
    val plain = posts.filter { it.video == null && it.audio == null && it.handedOver == null }
    val art = plain.count { "art" in it.ownTabs }

    // Episodes are items with a video or audio, each counted once
    val episodes = items.filter { it.video != null || it.audio != null }
    var watched = JrwiTotals()
    var started = JrwiTotals()
    var unwatched = JrwiTotals()
    var played = 0.0
    episodes.forEach { item ->
        val length = item.duration ?: 0.0
        val progress = item.progress
        when {
            progress?.finished == true -> watched += JrwiTotals(1, 0, length)
            progress != null && progress.position >= STARTED_SECONDS ->
                started += JrwiTotals(1, 0, length)
            else -> unwatched += JrwiTotals(1, 0, length)
        }
        played += item.playedSeconds(length)
    }

    // Shows: posts and files by each post's own show, episodes and time by each item's
    val byShow = mutableMapOf<String, JrwiShowTotals>()
    fun show(id: String) = byShow.getOrPut(id) { JrwiShowTotals(id, showNames[id] ?: id) }
    posts.forEach { post ->
        val bytes =
            (post.video?.takeIf { it.jellyfinPath != null }?.size ?: 0L) + (post.audio?.size ?: 0L)
        val totals = show(post.show)
        byShow[post.show] = totals.copy(posts = totals.posts + 1, bytes = totals.bytes + bytes)
    }
    episodes.forEach { item ->
        val length = item.duration ?: 0.0
        val totals = show(item.show)
        byShow[item.show] =
            totals.copy(
                episodes = totals.episodes + 1,
                seconds = totals.seconds + length,
                played = totals.played + item.playedSeconds(length),
            )
    }

    val dated = posts.filter { it.date != null }.sortedBy { it.date }
    val days = (0 until STATS_DAYS).map { since.plusDays(it.toLong()) }
    val archivedDays = events?.let { list ->
        val perDay =
            list
                .filter { it.type == "new_item" }
                .mapNotNull { localDate(it.at) }
                .groupingBy { it }
                .eachCount()
        days.map { it to (perDay[it] ?: 0) }
    }
    return JrwiArchiveStats(
        posts = posts.size,
        episodes = episodes.size,
        playtime = episodes.sumOf { it.duration ?: 0.0 },
        watch = JrwiWatchTotals(watched, started, unwatched, played),
        videos = videos,
        audio = audio,
        youtube = youtube,
        handedOver = posts.count { it.handedOver != null },
        textPosts = plain.size - art,
        artPosts = art,
        removedFromSite = posts.count { it.removedFromSite != null },
        bothVersions = episodes.count { it.hasBoth },
        linedUp = episodes.count { it.hasBoth && it.sync?.checked == true },
        heights = heights.entries.sortedByDescending { it.key }.map { it.key to it.value },
        likes = posts.sumOf { (it.likeCount ?: 0).toLong() },
        comments = posts.sumOf { (it.commentCount ?: 0).toLong() },
        mostLiked =
            posts
                .filter { (it.likeCount ?: 0) > 0 }
                .sortedByDescending { it.likeCount ?: 0 }
                .take(TOP)
                .map { JrwiRanked(it, (it.likeCount ?: 0).toLong()) },
        mostCommented =
            posts
                .filter { (it.commentCount ?: 0) > 0 }
                .sortedByDescending { it.commentCount ?: 0 }
                .take(TOP)
                .map { JrwiRanked(it, (it.commentCount ?: 0).toLong()) },
        longest =
            episodes
                .maxByOrNull { it.duration ?: 0.0 }
                ?.takeIf { (it.duration ?: 0.0) > 0 }
                ?.let { JrwiRanked(it, (it.duration ?: 0.0).toLong()) },
        biggestVideo =
            posts
                .filter { it.video?.jellyfinPath != null && (it.video?.size ?: 0L) > 0 }
                .maxByOrNull { it.video?.size ?: 0L }
                ?.let { JrwiRanked(it, it.video?.size ?: 0L) },
        first = dated.firstOrNull(),
        newest = dated.lastOrNull(),
        byYear =
            posts
                .mapNotNull { it.year() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedBy { it.key }
                .map { it.key to it.value },
        shows = byShow.values.sortedByDescending { it.posts },
        archivedDays = archivedDays,
        failedChecks =
            events.orEmpty().count { it.type == "check_failed" || it.type == "login_failed" },
    )
}

// As the web app's Continue watching: started means 10 seconds or more in
private const val STARTED_SECONDS = 10.0
private const val TOP = 5

// The archived posts are counted for each of the last eight days, as TubeArchivist's downloads
private const val STATS_DAYS = 8
