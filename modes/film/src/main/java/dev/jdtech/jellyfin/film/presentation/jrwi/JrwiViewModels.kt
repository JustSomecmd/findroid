package dev.jdtech.jellyfin.film.presentation.jrwi

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.presentation.tube.TaListState
import dev.jdtech.jellyfin.film.presentation.tube.errorText
import dev.jdtech.jellyfin.film.presentation.tube.loadNextPage
import dev.jdtech.jellyfin.jrwi.JrwiApi
import dev.jdtech.jellyfin.jrwi.JrwiApiException
import dev.jdtech.jellyfin.jrwi.JrwiCheckRun
import dev.jdtech.jellyfin.jrwi.JrwiComment
import dev.jdtech.jellyfin.jrwi.JrwiContinue
import dev.jdtech.jellyfin.jrwi.JrwiDownload
import dev.jdtech.jellyfin.jrwi.JrwiDownloader
import dev.jdtech.jellyfin.jrwi.JrwiDownloads
import dev.jdtech.jellyfin.jrwi.JrwiEvent
import dev.jdtech.jellyfin.jrwi.JrwiFeature
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiPage
import dev.jdtech.jellyfin.jrwi.JrwiPdfSource
import dev.jdtech.jellyfin.jrwi.JrwiPodcast
import dev.jdtech.jellyfin.jrwi.JrwiPostFile
import dev.jdtech.jellyfin.jrwi.JrwiPreview
import dev.jdtech.jellyfin.jrwi.JrwiProblem
import dev.jdtech.jellyfin.jrwi.JrwiProgress
import dev.jdtech.jellyfin.jrwi.JrwiProgressChange
import dev.jdtech.jellyfin.jrwi.JrwiProgressSync
import dev.jdtech.jellyfin.jrwi.JrwiShow
import dev.jdtech.jellyfin.jrwi.JrwiStatus
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.isDownloading
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.MediaChanges
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.tubearchivist.TaPage
import dev.jdtech.jellyfin.tubearchivist.TaPaginate
import dev.jdtech.jellyfin.utils.Downloader
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber

/** A page of the archiver's list in the shape the TubeArchivist lists are paged with. */
fun <T> JrwiPage<T>.asTaPage(): TaPage<T> =
    TaPage(
        data = items,
        paginate =
            TaPaginate(
                currentPage = page,
                lastPage = if (hasMore) page + 1 else page,
                totalHits = total,
            ),
    )

/** Whether the archiver is set up, which adds the JRWI tab, and its address for its images. */
@HiltViewModel
class JrwiNavViewModel @Inject constructor(appPreferences: AppPreferences, jrwiApi: JrwiApi) :
    ViewModel() {
    val baseUrl =
        combine(
                appPreferences.observe(appPreferences.jrwiUrl),
                appPreferences.observe(appPreferences.jrwiToken),
            ) { url, token ->
                url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() && !token.isNullOrBlank() }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                jrwiApi.baseUrl.takeIf { jrwiApi.isConfigured },
            )
}

/** Messages the JRWI pages show as a short note. */
object JrwiMessages {
    const val SAVED = "jrwi:saved"
    const val CHECK_STARTED = "jrwi:check_started"
    const val CHECK_RUNNING = "jrwi:check_running"
    const val NOT_IN_JELLYFIN = "jrwi:not_in_jellyfin"
    const val NOT_SAVED = "jrwi:not_saved"
    const val STOPPING = "jrwi:stopping"
    const val NOTHING_RUNNING = "jrwi:nothing_running"
    const val REREAD_STARTED = "jrwi:reread_started"
    const val PODCAST_READING = "jrwi:podcast_reading"
}

/** An episode's audio on the phone or on its way, with how far its download has got. */
data class JrwiDownloadEntry(
    val download: JrwiDownload,
    /** From 0 to 1 while it downloads, or null while it waits. */
    val progress: Float?,
    /** Its picture on the phone. */
    val picture: String? = null,
    /** Its season and episode number, as its show's Episodes number it; null for the rest. */
    val number: JrwiEpisodeNumber? = null,
    /** How far it was played, as the archive has it, in the audio's timeline; null when unknown. */
    val watch: JrwiProgress? = null,
)

/** An episode's place in its show, to list it in order with the show's other downloads. */
data class JrwiEpisodeNumber(val season: Int, val episode: Int)

/** [this] with the progress just saved for one of its items. */
private fun List<JrwiItem>.withProgress(change: JrwiProgressChange): List<JrwiItem> {
    return map { if (it.id == change.itemId) it.copy(progress = change.progress) else it }
}

/**
 * A single post with the progress just saved for its episode, when it's one of the episode's
 * versions. The archive keeps one record per episode, so every version shows it.
 */
internal fun JrwiItem.withEpisodeProgress(change: JrwiProgressChange): JrwiItem {
    if (video == null && audio == null) return this
    if ((itemId ?: id) != change.itemId && id != change.itemId) return this
    return copy(progress = change.progress)
}

/** A post's own progress, for its watched mark; null for a post without a video or audio. */
internal suspend fun JrwiApi.postProgress(post: JrwiItem): JrwiProgress? {
    if (post.video == null && post.audio == null) return null
    return try {
        getProgress(post.id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "No progress for post ${post.id}")
        null
    }
}

/** A post's first comments as the site's feed picks them: pinned ones, then the most liked. */
internal fun List<JrwiComment>.topComments(count: Int): List<JrwiComment> =
    sortedWith(compareBy<JrwiComment>({ !it.pinned }, { -(it.likeCount ?: 0) })).take(count)

data class JrwiShowsState(
    val configured: Boolean = false,
    val shows: List<JrwiShow> = emptyList(),
    val latest: List<JrwiItem> = emptyList(),
    /**
     * For shows without a picture from archivers older than 0.6.0, the picture of their newest
     * item, by show ID.
     */
    val showPictures: Map<String, String> = emptyMap(),
    /** Episodes started and not finished, the one played most recently first. */
    val continueWatching: List<JrwiContinue> = emptyList(),
    /** The archive's feed, as the web app's Home: every post on its own, newest first. */
    val feed: List<JrwiFeedPost> = emptyList(),
    /** How many posts the feed has in all, to load more of as it's scrolled. */
    val feedTotal: Int = 0,
    val feedLoading: Boolean = false,
    /** The archiver's version, for its card behind Home's pill. */
    val version: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * A post in the feed, on its own as the web app's feed has it. Its text, pictures, files, tags and
 * first comments come once its card shows.
 */
data class JrwiFeedPost(
    val post: JrwiItem,
    val full: JrwiItem? = null,
    /** Its first comments, pinned ones first, as the site's feed shows them. */
    val comments: List<JrwiComment> = emptyList(),
    /** It has more comments than those. */
    val moreComments: Boolean = false,
)

/** The JRWI tab: the shows, like the channels on the Channels tab, and what's new. */
@HiltViewModel
class JrwiShowsViewModel
@Inject
constructor(
    private val jrwiApi: JrwiApi,
    private val jrwiProgress: JrwiProgressSync,
    private val jrwiDownloads: JrwiDownloads,
) : ViewModel() {
    private val _state = MutableStateFlow(JrwiShowsState(configured = jrwiApi.isConfigured))
    val state = _state.asStateFlow()

    private var job: Job? = null
    private var continueJob: Job? = null
    private var feedJob: Job? = null
    private var feedPage = 0
    // The feed's posts whose text and comments are being loaded
    private val detailsLoading = mutableSetOf<Long>()

    init {
        // Marks what's been played since the rows were loaded
        viewModelScope.launch {
            jrwiProgress.changes.collect { change ->
                _state.update {
                    it.copy(
                        latest = it.latest.withProgress(change),
                        continueWatching = continueWith(it.continueWatching, change),
                        feed =
                            it.feed.map { entry ->
                                entry.copy(post = entry.post.withEpisodeProgress(change))
                            },
                    )
                }
            }
        }
        // Progress made without a connection, now that the archiver may be back
        viewModelScope.launch { jrwiProgress.sendPending() }
    }

    /**
     * Loads the shows, unless they're in already. [force] loads them again anyway. Continue
     * watching is loaded again each time, as playing elsewhere changes it.
     */
    fun load(force: Boolean = false) {
        val configured = jrwiApi.isConfigured
        _state.update { it.copy(configured = configured) }
        if (!configured) return
        loadContinue()
        loadFeed(force)
        if (!force && (_state.value.shows.isNotEmpty() || job?.isActive == true)) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val shows = jrwiApi.getShows()
                val version =
                    try {
                        jrwiApi.getPing().version
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                val latest =
                    try {
                        jrwiApi.getLatest(limit = LATEST_COUNT)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                _state.update {
                    it.copy(
                        shows = shows,
                        latest = latest,
                        version = version,
                        loading = false,
                        error = null,
                    )
                }
                loadShowPictures(shows, latest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.errorText()) }
            }
        }
    }

    private fun loadContinue() {
        if (continueJob?.isActive == true) return
        continueJob = viewModelScope.launch {
            try {
                val entries = jrwiProgress.continueWatching()
                _state.update { it.copy(continueWatching = entries) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The row is left as it was
                Timber.d(e, "Continue watching could not be loaded")
            }
        }
    }

    /** The feed's first page, unless it's in already; [force] loads it again from the top. */
    private fun loadFeed(force: Boolean) {
        if (!force && (_state.value.feed.isNotEmpty() || feedJob?.isActive == true)) return
        feedJob?.cancel()
        feedPage = 0
        detailsLoading.clear()
        feedJob = viewModelScope.launch { loadFeedPage(reset = true) }
    }

    /** The feed's next page, as it's scrolled near its end. */
    fun loadMoreFeed() {
        val state = _state.value
        if (
            feedJob?.isActive == true || state.feed.isEmpty() || state.feed.size >= state.feedTotal
        ) {
            return
        }
        feedJob = viewModelScope.launch { loadFeedPage(reset = false) }
    }

    private suspend fun loadFeedPage(reset: Boolean) {
        val page = feedPage + 1
        _state.update { it.copy(feedLoading = true) }
        try {
            // Archivers with the feed (0.9.7) give each post whole with its first comments, so
            // its card needs nothing more; older ones give the list, and each card asks for the
            // rest as it shows
            val (entries, total) =
                if (jrwiApi.has(JrwiFeature.FEED) == true) {
                    val result = jrwiApi.getFeed(page = page, pageSize = FEED_PAGE_SIZE)
                    result.items.map {
                        JrwiFeedPost(
                            post = it.post,
                            full = it.post,
                            comments = it.topComments,
                            moreComments = it.moreComments,
                        )
                    } to result.total
                } else {
                    val result = jrwiApi.getPosts(page = page, pageSize = FEED_PAGE_SIZE)
                    result.items.map { JrwiFeedPost(it) } to result.total
                }
            feedPage = page
            _state.update { state ->
                val shown = if (reset) emptyList() else state.feed
                val ids = shown.map { it.post.id }.toSet()
                state.copy(
                    feed = shown + entries.filter { it.post.id !in ids },
                    feedTotal = total,
                    feedLoading = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "The feed could not be loaded")
            _state.update { it.copy(feedLoading = false) }
        }
    }

    /**
     * A feed post's text, pictures, files and tags, its first comments, and for a video or audio
     * how far it was played, once its card shows. One that couldn't be loaded is tried again when
     * it shows again.
     */
    fun loadFeedDetails(postId: Long) {
        val entry = _state.value.feed.firstOrNull { it.post.id == postId } ?: return
        if (entry.full != null || !detailsLoading.add(postId)) return
        viewModelScope.launch {
            try {
                coroutineScope {
                    val full = async { jrwiApi.getPost(postId) }
                    val comments = async {
                        try {
                            jrwiApi.getPostComments(postId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                    // Posts carry their progress since 0.9.7, so it's asked for only of older
                    // archivers
                    val progress =
                        if (jrwiApi.has(JrwiFeature.POST_PROGRESS) == true) null
                        else async { jrwiApi.postProgress(entry.post) }
                    val post = full.await()
                    val page = comments.await()
                    val played = progress?.await() ?: post.progress
                    _state.update { state ->
                        state.copy(
                            feed =
                                state.feed.map {
                                    if (it.post.id != postId) it
                                    else
                                        it.copy(
                                            post =
                                                if (played != null) {
                                                    it.post.copy(progress = played)
                                                } else {
                                                    it.post
                                                },
                                            full = post,
                                            comments =
                                                page?.items.orEmpty().topComments(FEED_COMMENTS),
                                            moreComments = (page?.total ?: 0) > FEED_COMMENTS,
                                        )
                                }
                        )
                    }
                }
            } catch (e: CancellationException) {
                detailsLoading.remove(postId)
                throw e
            } catch (e: Exception) {
                Timber.d(e, "Post $postId could not be loaded for the feed")
                detailsLoading.remove(postId)
            }
        }
    }

    /** A file's address with the token, for saving a picture through the download manager. */
    fun fileUrl(path: String?): String? = jrwiApi.fileUrlWithToken(path)

    /**
     * Where the picture viewer gets the pages of a feed post's PDF: the archiver's pictures of
     * them, or else a copy fetched now and drawn on the phone.
     */
    suspend fun pdfSource(file: JrwiPostFile): JrwiPdfSource {
        val preview =
            try {
                file.preview?.let { jrwiApi.getPreview(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "No page pictures for ${file.name}")
                null
            }
        return jrwiPdfSource(preview)
            ?: JrwiPdfSource.Phone(jrwiCachedCopy(jrwiApi, jrwiDownloads, file))
    }

    /**
     * Continue watching after progress was saved from the app: the episode goes first with its new
     * progress, or leaves the row once it's finished. One not in the row yet comes with the next
     * load, when the tab is shown again.
     */
    private fun continueWith(
        entries: List<JrwiContinue>,
        change: JrwiProgressChange,
    ): List<JrwiContinue> {
        val index = entries.indexOfFirst { it.item.id == change.itemId }
        val progress = change.progress
        if (index < 0) return entries
        val rest = entries.filterIndexed { i, _ -> i != index }
        if (progress.finished) return rest
        return listOf(entries[index].copy(progress = progress)) + rest
    }

    /**
     * Shows without any picture, from archivers older than 0.6.0, get the picture of their newest
     * item, as on the web app of those: from the Latest row when it has one of theirs, or else
     * asked for. Newer archivers give every show a picture, the JRWI logo when it has none of its
     * own.
     */
    private suspend fun loadShowPictures(shows: List<JrwiShow>, latest: List<JrwiItem>) {
        val missing =
            shows
                .filter { it.image == null && it.poster == null && it.banner == null }
                .map { it.id }
        if (missing.isEmpty()) return
        val pictures = mutableMapOf<String, String>()
        // Newest first, so the first picture of a show is its newest
        latest.forEach { item ->
            val picture = item.thumbnail ?: return@forEach
            if (item.show in missing) pictures.putIfAbsent(item.show, picture)
        }
        val toAsk = missing.filter { it !in pictures }
        val found = coroutineScope { toAsk.map { async { it to newestPicture(it) } }.awaitAll() }
        found.forEach { (showId, picture) -> picture?.let { pictures[showId] = it } }
        _state.update { it.copy(showPictures = pictures.toMap()) }
    }

    /** The picture of a show's newest item that has one, or null. */
    private suspend fun newestPicture(showId: String): String? =
        try {
            val items = jrwiApi.search(show = showId, pageSize = PICTURE_SEARCH_SIZE).items
            items.firstNotNullOfOrNull { it.thumbnail }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val LATEST_COUNT = 20
        // As many as the web app's feed shows on a page
        const val FEED_PAGE_SIZE = 20
        // The site's feed shows each post's first two comments
        const val FEED_COMMENTS = 2

        // Text posts have no picture, so a few items in case the newest are those
        const val PICTURE_SEARCH_SIZE = 10
    }
}

/** One page of a show: a tab, or one season of a tab that has more than one. */
data class JrwiShowPage(
    val tab: String,
    val tabName: String,
    val season: Int? = null,
    val seasonName: String? = null,
    /** How many items are on the page. */
    val count: Int = 0,
) {
    val key: String
        get() = if (season == null) tab else "$tab:$season"
}

/**
 * A show's pages: a page for each tab, or a page per season for a tab with more than one, as the
 * archiver's web app has (v0.13 and v0.14).
 */
fun JrwiShow.pages(): List<JrwiShowPage> = tabs.flatMap { tab ->
    // Archivers on v0.13 only give the seasons of the episodes, on the show
    val tabSeasons = tab.seasons.ifEmpty { if (tab.id == "episodes") this.seasons else emptyList() }
    if (tabSeasons.size > 1) {
        tabSeasons.map { JrwiShowPage(tab.id, tab.name, it.season, it.name, it.count) }
    } else {
        listOf(JrwiShowPage(tab.id, tab.name, count = tab.count))
    }
}

/**
 * A page of a show about to be downloaded whole, such as a season: its [items], oldest first, and
 * how many have audio and an archived video, for the pop-up to offer the version.
 */
data class JrwiPageDownload(
    val page: JrwiShowPage,
    val items: List<JrwiItem>,
    val audioCount: Int,
    val videoCount: Int,
)

/** What came of downloading a show's page, for a short note. */
sealed interface JrwiPageDownloadResult {
    /** [added] episodes went in the queue; [missing] videos weren't found in Jellyfin. */
    data class Added(val added: Int, val missing: Int) : JrwiPageDownloadResult

    /** Every episode was on the phone or downloading already. */
    data object AllThere : JrwiPageDownloadResult

    /** The page has neither audio nor archived videos. */
    data object NothingToDownload : JrwiPageDownloadResult

    /** It stopped, after [added] were queued: no room left, say. */
    data class Failed(val error: UiText, val added: Int) : JrwiPageDownloadResult
}

/** A show's page: its tabs, and a list for each that loads as it's scrolled. */
@HiltViewModel
class JrwiShowViewModel
@Inject
constructor(
    private val jrwiApi: JrwiApi,
    private val jrwiProgress: JrwiProgressSync,
    private val jrwiDownloads: JrwiDownloads,
    private val jrwiDownloader: JrwiDownloader,
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
) : ViewModel() {
    private val _show = MutableStateFlow<JrwiShow?>(null)
    val show = _show.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** The names of the shows, for posts that the site's tags place here from another show. */
    private val _showNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val showNames = _showNames.asStateFlow()

    private val lists = mutableMapOf<String, MutableStateFlow<TaListState<JrwiItem>>>()

    private var showId: String? = null

    // The items whose archived video, or TubeArchivist's copy of it, is downloaded through Jellyfin
    private val videosOnPhone = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * The items on the phone, their audio or their video downloaded whole, to mark in the lists as
     * the Jellyfin and TubeArchivist lists mark downloads.
     */
    val onPhone: StateFlow<Set<Long>> =
        combine(jrwiDownloads.downloads, videosOnPhone) { audio, videos ->
                audio.filter { it.done }.map { it.itemId }.toSet() + videos
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private suspend fun loadVideosOnPhone() {
        videosOnPhone.value =
            withContext(Dispatchers.IO) {
                try {
                    val downloaded =
                        repository
                            .getDownloadedEpisodes()
                            .filter { it.isDownloaded() }
                            .map { it.id.toString() }
                            .toSet()
                    jrwiDownloads
                        .savedVideos()
                        .filter { it.episodeId in downloaded }
                        .map { it.item.id }
                        .toSet()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Could not read the downloaded videos")
                    emptySet()
                }
            }
    }

    init {
        // What's on the phone, and again when a download finishes or is removed
        viewModelScope.launch {
            loadVideosOnPhone()
            MediaChanges.changes.filterIsInstance<MediaChanges.Downloads>().collect {
                loadVideosOnPhone()
            }
        }
        // Marks what's been played since the lists were loaded
        viewModelScope.launch {
            jrwiProgress.changes.collect { change ->
                lists.values.forEach { list ->
                    list.update { it.copy(items = it.items.withProgress(change)) }
                }
            }
        }
    }

    fun load(showId: String) {
        if (this.showId == showId && _show.value != null) return
        this.showId = showId
        viewModelScope.launch {
            try {
                _error.value = null
                _show.value = jrwiApi.getShow(showId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.errorText()
                return@launch
            }
            try {
                _showNames.value = jrwiApi.getShows().associate { it.id to it.name }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Posts from other shows are then shown without their show's name
            }
        }
    }

    /** The list of one page, such as the episodes or one season of them. */
    fun items(page: JrwiShowPage): MutableStateFlow<TaListState<JrwiItem>> =
        lists.getOrPut(page.key) { MutableStateFlow(TaListState()) }

    fun loadMore(page: JrwiShowPage) {
        val showId = showId ?: return
        viewModelScope.launch {
            items(page).loadNextPage(key = { it.id.toString() }) { number ->
                // Episodes go by number, newest first, as on the site
                jrwiApi
                    .getShowItems(showId, page.tab, season = page.season, page = number)
                    .asTaPage()
            }
        }
    }

    private val _pageDownload = MutableStateFlow<JrwiPageDownload?>(null)

    /** A page about to be downloaded, for its pop-up, once its episodes are in. */
    val pageDownload = _pageDownload.asStateFlow()

    private val _queueing = MutableStateFlow<String?>(null)

    /** The key of the page whose episodes are being read or queued, for its button. */
    val queueing = _queueing.asStateFlow()

    private val _downloadResults = Channel<JrwiPageDownloadResult>(Channel.BUFFERED)
    val downloadResults = _downloadResults.receiveAsFlow()

    /** Reads every episode of [page], oldest first, for the pop-up to ask how to download them. */
    fun prepareDownload(page: JrwiShowPage) {
        val showId = showId ?: return
        if (_queueing.value != null) return
        _queueing.value = page.key
        viewModelScope.launch {
            try {
                val items = mutableListOf<JrwiItem>()
                for (number in 1..DOWNLOAD_MAX_PAGES) {
                    val result =
                        jrwiApi.getShowItems(
                            showId,
                            page.tab,
                            season = page.season,
                            page = number,
                            order = "asc",
                            pageSize = DOWNLOAD_PAGE_SIZE,
                        )
                    items += result.items
                    if (!result.hasMore) break
                }
                val unique = items.distinctBy { it.id }.filter { !it.locked }
                val audio = unique.count { it.audio != null }
                val video = unique.count { it.video?.isYoutube == false }
                if (audio == 0 && video == 0) {
                    _downloadResults.send(JrwiPageDownloadResult.NothingToDownload)
                } else {
                    _pageDownload.value = JrwiPageDownload(page, unique, audio, video)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _downloadResults.send(
                    JrwiPageDownloadResult.Failed(UiText.DynamicString(e.errorText()), 0)
                )
            } finally {
                _queueing.value = null
            }
        }
    }

    fun cancelDownload() {
        _pageDownload.value = null
    }

    /**
     * Puts every episode of the page in the download queue: its audio, or with [video] its archived
     * video through Jellyfin to the storage [storageIndex], each with its post's files when
     * [withFiles], as an episode's own download button does. Those on the phone or on their way
     * already are left as they are.
     */
    fun download(video: Boolean, withFiles: Boolean, storageIndex: Int = 0) {
        val download = _pageDownload.value ?: return
        _pageDownload.value = null
        _queueing.value = download.page.key
        viewModelScope.launch {
            try {
                val result =
                    if (video) queueVideos(download.items, withFiles, storageIndex)
                    else queueAudio(download.items, withFiles)
                _downloadResults.send(result)
            } finally {
                _queueing.value = null
            }
        }
    }

    private fun queueAudio(items: List<JrwiItem>, withFiles: Boolean): JrwiPageDownloadResult {
        var added = 0
        items
            .filter { it.audio != null }
            .forEach { item ->
                val existing = jrwiDownloads.get(item.id)
                // On the phone or on its way; a failed one is tried again
                if (existing != null && !existing.failed) return@forEach
                jrwiDownloader.download(item, withFiles)
                added++
            }
        return if (added == 0) JrwiPageDownloadResult.AllThere
        else JrwiPageDownloadResult.Added(added, 0)
    }

    private suspend fun queueVideos(
        items: List<JrwiItem>,
        withFiles: Boolean,
        storageIndex: Int,
    ): JrwiPageDownloadResult {
        var added = 0
        var missing = 0
        for (item in items.filter { it.video?.isYoutube == false }) {
            val episodeId = jrwiProgress.episodeId(item)
            val episode = episodeId?.let {
                try {
                    repository.getEpisode(it)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.d(e, "Episode $it of ${item.id} could not be loaded")
                    null
                }
            }
            val source = episode?.sources?.firstOrNull()
            if (episodeId == null || episode == null || source == null) {
                missing++
                continue
            }
            if (episode.isDownloaded() || episode.isDownloading()) continue
            // The whole item is kept with the download, for its page without a connection
            val full =
                try {
                    jrwiApi.getItem(item.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    item
                }
            jrwiDownloads.saveVideo(full, episodeId.toString(), withFiles)
            if (jrwiDownloads.videoKeepsFiles(item.id) || full.transcript) {
                jrwiDownloader.keepFilesForVideo(item.id)
            }
            val error = downloader.downloadItem(episode, source.id, storageIndex)
            if (error != null) {
                jrwiDownloads.deleteVideo(item.id)
                return JrwiPageDownloadResult.Failed(error, added)
            }
            added++
        }
        return when {
            added > 0 || missing > 0 -> JrwiPageDownloadResult.Added(added, missing)
            else -> JrwiPageDownloadResult.AllThere
        }
    }

    private companion object {
        // Reading a page's episodes to download them: pages of 200, oldest first
        const val DOWNLOAD_PAGE_SIZE = 200
        const val DOWNLOAD_MAX_PAGES = 20
    }
}

data class JrwiItemState(
    val item: JrwiItem? = null,
    val error: String? = null,
    /**
     * Where the episode was left, in the video's timeline and in the audio's. The archiver keeps
     * one record for both and gives it in either.
     */
    val videoPosition: Double = 0.0,
    val audioPosition: Double = 0.0,
    val finished: Boolean = false,
    /** Jellyfin's copy of the video, once looked up. */
    val episodeId: java.util.UUID? = null,
    val lookingUp: Boolean = false,
    /** The audio is picked, rather than the video. */
    val playsAudio: Boolean = false,
    /** What the picked version's post says for itself. */
    val content: JrwiItemContent = JrwiItemContent(),
    /** The archiver can't be reached, so this is the copy kept with the audio's download. */
    val offline: Boolean = false,
    /** Jellyfin's episode of the archived video, for downloading it, once looked up. */
    val jellyfinEpisode: FindroidEpisode? = null,
    /**
     * The show the episode is from, for its link; without a connection only its name, from the
     * audio's download, when there is one.
     */
    val show: JrwiShow? = null,
)

/**
 * One post's own text, pictures, files and comments. For an episode with both a video and an audio
 * version, each version's post has its own; otherwise it's the whole item.
 */
data class JrwiItemContent(
    val post: JrwiItem? = null,
    val error: String? = null,
    val comments: TaListState<JrwiComment> = TaListState(),
    /**
     * Every file the post carries, for the file inspector (archiver 0.9.0 and newer), or null
     * before they're in or from older archivers, which show the post's attachments and documents.
     */
    val files: List<JrwiPostFile>? = null,
)

/** What the item page asks the screen to do. */
sealed interface JrwiItemEvent {
    /** [positionSeconds] is where the archive says the episode was left, in the video. */
    data class PlayVideo(
        val episodeId: java.util.UUID,
        val fromStart: Boolean,
        val positionSeconds: Double?,
    ) : JrwiItemEvent

    data class PlayYoutube(val videoId: String) : JrwiItemEvent

    data class PlayAudio(val postId: Long, val fromStart: Boolean) : JrwiItemEvent

    data class Message(val text: String) : JrwiItemEvent
}

/**
 * One post: its text, pictures, files and comments, and its video and audio, which share one
 * position and one watched state.
 */
@HiltViewModel
class JrwiItemViewModel
@Inject
constructor(
    private val jrwiApi: JrwiApi,
    private val jrwiProgress: JrwiProgressSync,
    private val jrwiDownloads: JrwiDownloads,
    private val jrwiDownloader: JrwiDownloader,
    // Online or offline, as it is at the time
    private val repository: JellyfinRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(JrwiItemState())
    val state = _state.asStateFlow()

    private val _events = Channel<JrwiItemEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The episode's audio on the phone, or on its way there. */
    val download: StateFlow<JrwiDownloadEntry?> =
        combine(
                _state.map { it.item?.id }.distinctUntilChanged(),
                jrwiDownloads.downloads,
                jrwiDownloads.progress,
            ) { itemId, downloads, progress ->
                downloads
                    .firstOrNull { it.itemId == itemId }
                    ?.let { JrwiDownloadEntry(it, progress[it.itemId]) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Each post's own content and comments, kept for switching back, by post ID
    private val contents = mutableMapOf<Long, MutableStateFlow<JrwiItemContent>>()
    private val commentLists = mutableMapOf<Long, MutableStateFlow<TaListState<JrwiComment>>>()
    private val postsLoading = mutableSetOf<Long>()
    private var contentJob: Job? = null

    private var postId: Long? = null

    // Set when the page was opened to watch the video, which plays once the position is in
    private var videoOnLoad = false

    /**
     * Loads the item, or loads its position again when it's in already. With [startVideo], as from
     * the audio player, the video is picked and plays from where the episode was left.
     */
    fun load(postId: Long, startVideo: Boolean = false) {
        if (startVideo) videoOnLoad = true
        if (this.postId != postId) {
            this.postId = postId
            _state.value = JrwiItemState()
            contents.clear()
            commentLists.clear()
        }
        viewModelScope.launch {
            try {
                val shown = _state.value
                // A saved copy is tried for the archiver again, in case it can be reached now
                val item = shown.item?.takeIf { !shown.offline } ?: loadItem(postId)
                val offline = _state.value.offline
                if (shown.item == null || shown.offline != offline) {
                    contents.clear()
                    commentLists.clear()
                    // The version the page was opened with, such as the audio from Continue
                    // watching or the audio player, else the video, unless there's only the audio
                    // or the audio is all that's here
                    val audio =
                        if (shown.item == null) {
                            if (videoOnLoad && item.video != null && !offline) false
                            else item.video == null || offline || postId == item.audio?.postId
                        } else {
                            shown.playsAudio
                        }
                    _state.update { it.copy(item = item, error = null, playsAudio = audio) }
                }
                // Also tries again what didn't load before
                showContent(item, _state.value.playsAudio)
                loadShow(item)
                // A download from before the post's files were kept with it gets them now
                if (!_state.value.offline && jrwiDownloads.savedFiles(item.id) == null) {
                    jrwiDownloader.refresh(item.id)
                    // Also a video's download whose files couldn't be kept when it started
                    if (jrwiDownloads.videoKeepsFiles(item.id)) {
                        jrwiDownloader.keepFilesForVideo(item.id)
                    }
                }
                refreshProgress(item)
                if (videoOnLoad) {
                    videoOnLoad = false
                    if (!_state.value.offline) playVideo()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.errorText()) }
            }
        }
    }

    private var showJob: Job? = null

    /** The show the episode is from, for the link to its page under the episode's title. */
    private fun loadShow(item: JrwiItem) {
        val shown = _state.value.show
        if (shown?.id == item.show && shown.itemCount > 0) return
        if (showJob?.isActive == true) return
        showJob = viewModelScope.launch {
            val show =
                try {
                    jrwiApi.getShow(item.show)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Without a connection, the name kept with the audio's download
                    jrwiDownloads.get(item.id)?.showName?.let {
                        JrwiShow(id = item.show, name = it)
                    }
                }
            if (show != null && _state.value.item?.id == item.id) {
                _state.update { it.copy(show = show) }
            }
        }
    }

    /** From the archiver, or the copy kept with a download when the archiver can't be reached. */
    private suspend fun loadItem(postId: Long): JrwiItem =
        try {
            jrwiApi.getItem(postId).also { _state.update { it.copy(offline = false) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (e is JrwiApiException) throw e
            val saved =
                jrwiDownloads.forPost(postId)?.let { jrwiDownloads.savedItem(it.itemId) }
                    ?: jrwiDownloads.savedVideo(postId)?.item
                    ?: throw e
            _state.update { it.copy(offline = true) }
            saved
        }

    private var episodeJob: Job? = null

    /**
     * Looks up Jellyfin's episode of an archived video, for its download. The video downloads
     * through Jellyfin like its other downloads; YouTube-only videos download through
     * TubeArchivist.
     */
    private fun lookUpEpisode(item: JrwiItem) {
        if (item.video?.isYoutube != false) return
        if (_state.value.jellyfinEpisode != null || episodeJob?.isActive == true) return
        episodeJob = viewModelScope.launch {
            val episodeId = episodeIdOf(item) ?: return@launch
            val episode =
                try {
                    repository.getEpisode(episodeId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            _state.update { it.copy(episodeId = episodeId, jellyfinEpisode = episode) }
            // A download made elsewhere, such as on the Jellyfin episode's page, gets it too
            if (episode?.isDownloaded() == true) jrwiDownloads.saveVideo(item, episodeId.toString())
        }
    }

    /**
     * Jellyfin's copy of the video: as looked up before, from Jellyfin, or kept with a download.
     */
    private suspend fun episodeIdOf(item: JrwiItem): UUID? =
        _state.value.episodeId
            ?: jrwiProgress.episodeId(item)
            ?: jrwiDownloads
                .savedVideo(item.id)
                ?.takeIf { it.youtubeId == null }
                ?.episodeId
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** Looks at the episode again, once its download has finished or been removed. */
    fun reloadEpisode() {
        val item = _state.value.item ?: return
        episodeJob?.cancel()
        _state.update { it.copy(jellyfinEpisode = null) }
        lookUpEpisode(item)
    }

    /**
     * The video's download starts: the episode is kept with it, so the video's progress goes to the
     * archive and its page opens without a connection, and with [withFiles] the post's files too.
     * Null, as when a download is tried again, keeps what was asked for when it started.
     */
    fun keepVideo(withFiles: Boolean? = null) {
        val state = _state.value
        val item = state.item ?: return
        val episodeId = state.episodeId ?: return
        jrwiDownloads.saveVideo(item, episodeId.toString(), withFiles)
        // With its transcript too, to follow along without a connection
        if (jrwiDownloads.videoKeepsFiles(item.id) || item.transcript) {
            jrwiDownloader.keepFilesForVideo(item.id)
        }
    }

    /** The video's download is removed, and what was kept with it goes too. */
    fun forgetVideo() {
        _state.value.item?.let { jrwiDownloader.forgetVideo(it.id) }
    }

    /** Downloads the audio, to listen to without a connection, and the post's files if asked. */
    fun downloadAudio(withFiles: Boolean) {
        _state.value.item?.let { jrwiDownloader.download(it, withFiles) }
    }

    /** Stops the audio's download, or takes it off the phone. */
    fun removeDownload() {
        _state.value.item?.let { jrwiDownloader.remove(it.id) }
    }

    /** Picks the video or the audio, and shows that version's own text and comments. */
    fun choose(audio: Boolean) {
        val item = _state.value.item ?: return
        if (_state.value.playsAudio == audio) return
        showContent(item, audio)
    }

    /** The post whose content shows: the picked version's, when there are both. */
    private fun contentPostId(item: JrwiItem, audio: Boolean): Long {
        if (!item.hasBoth) return item.id
        return (if (audio) item.audio?.postId else item.video?.postId) ?: item.id
    }

    private fun showContent(item: JrwiItem, audio: Boolean) {
        if (!audio) lookUpEpisode(item)
        val postId = contentPostId(item, audio)
        val content = contents.getOrPut(postId) { MutableStateFlow(JrwiItemContent()) }
        // In one go, so the page never shows one version's comments under the other's switch
        _state.update { it.copy(playsAudio = audio, content = content.value) }
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            content.collect { loaded -> _state.update { it.copy(content = loaded) } }
        }
        if (content.value.post == null) loadPost(item, postId, content)
        if (content.value.files == null) loadFiles(item, postId, content)
        if (content.value.comments.page == 0) loadMoreComments()
    }

    private val filesLoading = mutableSetOf<Long>()

    /**
     * The shown version's files, or all the item's with one version. Without a connection, those
     * kept with the audio's download, for all the item's versions.
     */
    private fun loadFiles(
        item: JrwiItem,
        postId: Long,
        content: MutableStateFlow<JrwiItemContent>,
    ) {
        if (_state.value.offline) {
            val saved = jrwiDownloads.savedFiles(item.id)?.takeIf { it.isNotEmpty() }
            content.update { it.copy(files = saved) }
            return
        }
        if (!filesLoading.add(postId)) return
        viewModelScope.launch {
            try {
                val files =
                    if (item.hasBoth) jrwiApi.getPostFiles(postId)
                    else jrwiApi.getItemFiles(item.id)
                content.update { it.copy(files = files) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Archivers older than 0.9.0 have no file lists
                Timber.d(e, "No files for post $postId")
            } finally {
                filesLoading.remove(postId)
            }
        }
    }

    /** What's inside a file: as kept with the download when there's one, or from the archiver. */
    suspend fun preview(file: JrwiPostFile): JrwiPreview {
        _state.value.item?.id?.let { itemId ->
            jrwiDownloads.savedPreview(itemId, file.path)?.let {
                return it
            }
        }
        val address = file.preview ?: throw IOException("This file has no preview")
        return jrwiApi.getPreview(address)
    }

    /**
     * The file on the phone, for drawing a PDF there: the copy kept with the download, or one
     * fetched now into the cache.
     */
    suspend fun fileOnPhone(file: JrwiPostFile): File =
        localCopy(file) ?: jrwiCachedCopy(jrwiApi, jrwiDownloads, file)

    /**
     * Where the picture viewer gets a PDF's pages: drawn on the phone from the copy kept with the
     * download, or the archiver's pictures of them, or else drawn from a copy fetched now when the
     * archiver can't draw them.
     */
    suspend fun pdfSource(file: JrwiPostFile): JrwiPdfSource {
        localCopy(file)?.let {
            return JrwiPdfSource.Phone(it)
        }
        val preview =
            try {
                preview(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "No page pictures for ${file.name}")
                null
            }
        return jrwiPdfSource(preview) ?: JrwiPdfSource.Phone(fileOnPhone(file))
    }

    /** The copy of a file kept with the episode's download, if there's one. */
    fun localCopy(file: JrwiPostFile): File? =
        _state.value.item?.id?.let { jrwiDownloads.localFile(it, file.path) }

    /** Where to show a file from: the phone's copy when there's one, or the archiver. */
    fun showFrom(file: JrwiPostFile): String? =
        localCopy(file)?.let { Uri.fromFile(it).toString() } ?: file.url

    private fun loadPost(
        item: JrwiItem,
        postId: Long,
        content: MutableStateFlow<JrwiItemContent>,
    ) {
        // With one version, the whole item is that post, with anything else that belongs to it
        if (!item.hasBoth) {
            content.update { it.copy(post = item, error = null) }
            return
        }
        // Without a connection, what was kept with the audio's download
        if (_state.value.offline) {
            val saved = if (postId == item.audio?.postId) jrwiDownloads.savedPost(item.id) else null
            content.update { it.copy(post = saved ?: item, error = null) }
            return
        }
        if (!postsLoading.add(postId)) return
        viewModelScope.launch {
            try {
                val post = jrwiApi.getPost(postId)
                content.update { it.copy(post = post, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                content.update { it.copy(error = e.errorText()) }
            } finally {
                postsLoading.remove(postId)
            }
        }
    }

    /** Reads the episode's one record in both versions' timelines, so the switch needs no wait. */
    private suspend fun refreshProgress(item: JrwiItem) = coroutineScope {
        val video = async { jrwiProgress.progress(item, audio = false) }
        val audio = async { jrwiProgress.progress(item, audio = true) }
        val videoProgress = video.await()
        val audioProgress = audio.await()
        val finished = (videoProgress ?: audioProgress)?.finished ?: return@coroutineScope
        _state.update {
            it.copy(
                videoPosition = videoProgress?.position ?: 0.0,
                audioPosition = audioProgress?.position ?: 0.0,
                finished = finished,
            )
        }
    }

    /** The next page of the picked version's comments, or the whole item's with one version. */
    fun loadMoreComments() {
        val item = _state.value.item ?: return
        val postId = contentPostId(item, _state.value.playsAudio)
        val content = contents[postId] ?: return
        if (_state.value.offline) {
            // Only the audio's comments are kept with its download
            val saved = postId == item.audio?.postId || !item.hasBoth
            val comments =
                TaListState(
                    items = if (saved) jrwiDownloads.savedComments(item.id) else emptyList(),
                    page = 1,
                    hasMore = false,
                    error = if (saved) null else JrwiMessages.NOT_SAVED,
                )
            content.update { it.copy(comments = comments) }
            return
        }
        val list = commentLists.getOrPut(postId) { MutableStateFlow(TaListState()) }
        viewModelScope.launch {
            list.loadNextPage(key = { it.id }) { page ->
                if (item.hasBoth) jrwiApi.getPostComments(postId, page).asTaPage()
                else jrwiApi.getComments(postId, page).asTaPage()
            }
            content.update { it.copy(comments = list.value) }
        }
    }

    /**
     * Plays the video: from Jellyfin, or from TubeArchivist when it's only on YouTube. With
     * [freshPosition] the position is read again first, as after the audio was just played.
     */
    fun playVideo(fromStart: Boolean = false, freshPosition: Boolean = false) {
        val item = _state.value.item ?: return
        val video = item.video ?: return
        viewModelScope.launch {
            video.youtubeId
                ?.takeIf { video.isYoutube }
                ?.let {
                    _events.send(JrwiItemEvent.PlayYoutube(it))
                    return@launch
                }
            _state.update { it.copy(lookingUp = true) }
            if (freshPosition) refreshProgress(item)
            val episodeId = episodeIdOf(item)
            _state.update { it.copy(lookingUp = false, episodeId = episodeId) }
            if (episodeId == null) {
                _events.send(JrwiItemEvent.Message(JrwiMessages.NOT_IN_JELLYFIN))
            } else {
                // The archive has the newest position, as both versions save to it while playing
                val state = _state.value
                val position =
                    state.videoPosition.takeIf { !fromStart && !state.finished && it > 0 }
                _events.send(JrwiItemEvent.PlayVideo(episodeId, fromStart, position))
            }
        }
    }

    fun playAudio(fromStart: Boolean = false) {
        val audio = _state.value.item?.audio ?: return
        viewModelScope.launch { _events.send(JrwiItemEvent.PlayAudio(audio.postId, fromStart)) }
    }

    /** Full address of a file, with the token for the browser or another app. */
    fun fileUrl(path: String?): String? = jrwiApi.fileUrlWithToken(path)

    /** Marks the video and the audio watched, or both not watched. */
    fun setWatched(watched: Boolean) {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            try {
                jrwiProgress.setWatched(item, watched)
                _state.update {
                    it.copy(finished = watched, videoPosition = 0.0, audioPosition = 0.0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(JrwiItemEvent.Message(e.errorText()))
            }
        }
    }
}

/**
 * The episodes whose audio is on the phone, for the Downloads page, and how far each downloaded
 * episode was played, as the archive has it: the one record the item page shows too (decisions 8
 * and 9), which the position kept with a Jellyfin download can fall behind or run ahead of.
 */
@HiltViewModel
class JrwiDownloadsViewModel
@Inject
constructor(
    private val downloads: JrwiDownloads,
    private val downloader: JrwiDownloader,
    private val jrwiProgress: JrwiProgressSync,
    private val handOvers: JrwiHandOvers,
) : ViewModel() {
    // Each episode's number, read once from the item kept with its audio
    private val numbers = ConcurrentHashMap<Long, Optional<JrwiEpisodeNumber>>()

    // How far each downloaded episode was played: its audio's by JRWI item ID, in the audio's
    // timeline, and its video's by Jellyfin episode ID, in the video's
    private val audioWatch = MutableStateFlow<Map<Long, JrwiProgress>>(emptyMap())
    private val _videoWatch = MutableStateFlow<Map<String, JrwiProgress>>(emptyMap())

    /** How far each downloaded archived video was played, by its Jellyfin episode ID. */
    val videoWatch: StateFlow<Map<String, JrwiProgress>> = _videoWatch.asStateFlow()

    private var watchJob: Job? = null

    private val _tubeArchivist = MutableStateFlow<Map<String, JrwiTaVideo>>(emptyMap())

    /**
     * TubeArchivist's downloads that are JRWI episodes' YouTube videos, by Jellyfin episode ID, so
     * they're listed under their show and open on the episode's page.
     */
    val tubeArchivist: StateFlow<Map<String, JrwiTaVideo>> = _tubeArchivist.asStateFlow()

    val entries: StateFlow<List<JrwiDownloadEntry>> =
        combine(downloads.downloads, downloads.progress, audioWatch) { list, progress, watch ->
                list.map {
                    JrwiDownloadEntry(
                        download = it,
                        progress = progress[it.itemId],
                        picture = downloads.thumbnailUri(it.itemId),
                        number = numberOf(it.itemId),
                        watch = watch[it.itemId],
                    )
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Played since, in the app's players: read again for that episode
        viewModelScope.launch { jrwiProgress.changes.collect { loadWatch(it.itemId) } }
        // TubeArchivist's downloads of JRWI episodes, and hand-overs, as of now
        viewModelScope.launch {
            loadTubeArchivist()
            handOvers.check()
            loadTubeArchivist()
        }
        // Again when the downloads change, such as when the check at the app's start links one
        viewModelScope.launch {
            MediaChanges.changes.filterIsInstance<MediaChanges.Downloads>().collect {
                loadTubeArchivist()
            }
        }
    }

    private suspend fun loadTubeArchivist() {
        _tubeArchivist.value =
            withContext(Dispatchers.IO) {
                downloads
                    .savedVideos()
                    .filter { it.youtubeId != null }
                    .associate { saved ->
                        val item = saved.item
                        val episode = item.episode?.takeIf { "episodes" in item.ownTabs }
                        saved.episodeId to
                            JrwiTaVideo(
                                itemId = item.id,
                                showName = saved.showName,
                                title = item.title,
                                number = episode?.let { JrwiEpisodeNumber(item.season ?: 1, it) },
                            )
                    }
            }
    }

    /**
     * Reads how far each downloaded episode was played, from the archive, or without a connection
     * from what was last saved on the phone; [itemId] reads only that episode's. One that can't be
     * read at all keeps the position its download has.
     */
    fun loadWatch(itemId: Long? = null) {
        if (itemId == null) watchJob?.cancel()
        val job =
            viewModelScope.launch(Dispatchers.IO) {
                if (itemId == null) loadTubeArchivist()
                val videos =
                    downloads.savedVideos().filter { itemId == null || it.item.id == itemId }
                val audio =
                    downloads.downloads.value
                        .filter { itemId == null || it.itemId == itemId }
                        .mapNotNull { downloads.savedItem(it.itemId) }
                val limit = Semaphore(WATCH_READS)
                coroutineScope {
                    val readVideos = videos.map { saved ->
                        async {
                            limit.withPermit {
                                readWatch(saved.item, audio = false)?.let { saved.episodeId to it }
                            }
                        }
                    }
                    val readAudio = audio.map { item ->
                        async {
                            limit.withPermit {
                                readWatch(item, audio = true)?.let { item.id to it }
                            }
                        }
                    }
                    val videoResults = readVideos.awaitAll().filterNotNull().toMap()
                    val audioResults = readAudio.awaitAll().filterNotNull().toMap()
                    _videoWatch.update { if (itemId == null) videoResults else it + videoResults }
                    audioWatch.update { if (itemId == null) audioResults else it + audioResults }
                }
            }
        if (itemId == null) watchJob = job
    }

    private suspend fun readWatch(item: JrwiItem, audio: Boolean): JrwiProgress? =
        try {
            jrwiProgress.progress(item, audio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "No progress for downloaded ${item.id}")
            null
        }

    /** The kept item's season and number, once it's on the phone; asked again until it is. */
    private fun numberOf(itemId: Long): JrwiEpisodeNumber? {
        numbers[itemId]?.let {
            return it.orElse(null)
        }
        val item = downloads.savedItem(itemId) ?: return null
        val episode = item.episode?.takeIf { "episodes" in item.ownTabs }
        val number = episode?.let { JrwiEpisodeNumber(item.season ?: 1, it) }
        numbers[itemId] = Optional.ofNullable(number)
        return number
    }

    fun remove(itemId: Long) = downloader.remove(itemId)

    private companion object {
        // Progress read from the archive at once
        const val WATCH_READS = 4
    }
}

/** A TubeArchivist download that's a JRWI episode's YouTube video, as JRWI's Downloads list it. */
data class JrwiTaVideo(
    val itemId: Long,
    val showName: String?,
    val title: String,
    val number: JrwiEpisodeNumber?,
)

data class JrwiServerState(
    val status: JrwiStatus? = null,
    val problems: List<JrwiProblem> = emptyList(),
    /** The archiver can stop a running check (0.9.7), so Stop shows while one runs. */
    val canStop: Boolean = false,
    /** What happened on the archiver lately, newest first; null until read. */
    val events: List<JrwiEvent>? = null,
    /** The archiver can re-read its saved pages for the app, as its admin page does. */
    val canReread: Boolean = false,
    /** The archiver tells the app about the podcast feed it reads. */
    val hasPodcast: Boolean = false,
    val podcast: JrwiPodcast? = null,
    /** The last finished check from the archive's stats, for what it saved. */
    val lastRun: JrwiCheckRun? = null,
    val error: String? = null,
)

/**
 * The archiver itself: what it's doing, a check now, and for the Downloads server side the posts it
 * couldn't save, what happened lately and the podcast feed.
 */
@HiltViewModel
class JrwiServerViewModel @Inject constructor(private val jrwiApi: JrwiApi) : ViewModel() {
    private val _state = MutableStateFlow(JrwiServerState())
    val state = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun load() {
        viewModelScope.launch { refresh() }
    }

    // When the last check had finished as the details were read: the problems, the recent events
    // and the podcast feed, which the Downloads server side shows and which only change with a
    // check
    private var detailsRead = false
    private var detailsCheck: String? = null

    /**
     * Reads the status again, for a page that keeps it up to date. With [details], the posts it
     * couldn't fully save, the recent events and the podcast feed too: the first time, after a
     * check has finished since, or with [force].
     */
    suspend fun refresh(force: Boolean = false, details: Boolean = false) {
        try {
            val status = jrwiApi.getStatus()
            val finished = status.lastCheckFinished
            val canStop = jrwiApi.has(JrwiFeature.STOP) == true
            val canReread = jrwiApi.has(JrwiFeature.REPARSE) == true
            val hasPodcast = jrwiApi.has(JrwiFeature.PODCAST) == true
            _state.update {
                it.copy(
                    status = status,
                    canStop = canStop,
                    canReread = canReread,
                    hasPodcast = hasPodcast,
                    error = null,
                )
            }
            if (details && (force || !detailsRead || finished != detailsCheck)) {
                detailsRead = true
                detailsCheck = finished
                loadDetails(hasPodcast)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.errorText()) }
        }
    }

    /** Starts a check for new posts, or of the whole feed with [full]. */
    fun check(full: Boolean = false) {
        viewModelScope.launch {
            try {
                val started = jrwiApi.startCheck(full)
                _messages.send(
                    if (started) JrwiMessages.CHECK_STARTED else JrwiMessages.CHECK_RUNNING
                )
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            }
        }
    }

    /**
     * The posts the archiver couldn't fully save, the events of the last week, newest first, the
     * podcast feed when the archiver has it, and the last finished check, all asked at once.
     */
    private suspend fun loadDetails(hasPodcast: Boolean) = coroutineScope {
        val since =
            OffsetDateTime.now(ZoneOffset.UTC)
                .minusDays(EVENT_DAYS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val problemsRead = async { orNull("problems") { jrwiApi.getProblems() } }
        val eventsRead = async {
            orNull("events") {
                jrwiApi.getEvents(since = since).sortedByDescending { it.at }.take(MAX_EVENTS)
            }
        }
        val podcastRead = async {
            if (hasPodcast) orNull("podcast feed") { jrwiApi.getPodcast() } else null
        }
        val runRead = async {
            if (jrwiApi.has(JrwiFeature.STATS) == false) null
            else orNull("stats") { jrwiApi.getStats().checks.firstOrNull { it.finished != null } }
        }
        val problems = problemsRead.await()
        val events = eventsRead.await()
        val podcast = podcastRead.await()
        val lastRun = runRead.await()
        _state.update {
            it.copy(
                problems = problems ?: it.problems,
                events = events ?: it.events,
                podcast = podcast ?: it.podcast,
                lastRun = lastRun ?: it.lastRun,
            )
        }
    }

    /** What [block] reads, or null when the archiver couldn't give it. */
    private suspend fun <T> orNull(what: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "No %s from the archiver", what)
            null
        }

    /**
     * Has the archiver re-read its saved pages with its current rules, as its admin page's Re-read
     * saved pages does.
     */
    fun reread() {
        viewModelScope.launch {
            try {
                val started = jrwiApi.startReparse()
                _messages.send(
                    if (started) JrwiMessages.REREAD_STARTED else JrwiMessages.CHECK_RUNNING
                )
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            }
        }
    }

    /** Has the archiver read the podcast feed now, then shows what it found. */
    fun readPodcast() {
        viewModelScope.launch {
            try {
                jrwiApi.readPodcast()
                _messages.send(JrwiMessages.PODCAST_READING)
                // It reads the feed in the background; what it found shows a moment later
                delay(PODCAST_READ_WAIT_MILLIS)
                refresh(force = true, details = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            }
        }
    }

    /**
     * Stops the check that's running, as the admin page's Stop does; the next check carries on from
     * where it got to.
     */
    fun stop() {
        viewModelScope.launch {
            try {
                val stopping = jrwiApi.stopCheck()
                _messages.send(
                    if (stopping) JrwiMessages.STOPPING else JrwiMessages.NOTHING_RUNNING
                )
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            }
        }
    }

    private companion object {
        // The events shown: those of the last week, at most this many
        const val EVENT_DAYS = 7L
        const val MAX_EVENTS = 30
        const val PODCAST_READ_WAIT_MILLIS = 3_000L
    }
}

data class JrwiSettingsState(
    val url: String = "",
    val token: String = "",
    val connected: Boolean = false,
    val checking: Boolean = false,
    val version: String? = null,
    val error: String? = null,
)

/** The archiver's address and API token. */
@HiltViewModel
class JrwiSettingsViewModel
@Inject
constructor(private val appPreferences: AppPreferences, private val jrwiApi: JrwiApi) :
    ViewModel() {
    private val _state =
        MutableStateFlow(
            JrwiSettingsState(
                url = jrwiApi.baseUrl.orEmpty(),
                token = jrwiApi.token.orEmpty(),
                connected = jrwiApi.isConfigured,
            )
        )
    val state = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun onUrlChange(url: String) = _state.update { it.copy(url = url, error = null) }

    fun onTokenChange(token: String) = _state.update { it.copy(token = token, error = null) }

    /** Checks the address and token, and saves them if the archiver accepts them. */
    fun connect() {
        val url = normalizeUrl(_state.value.url)
        val token = _state.value.token.trim()
        if (url.isEmpty() || token.isEmpty()) return
        _state.update { it.copy(url = url, checking = true, error = null) }
        viewModelScope.launch {
            try {
                val version = jrwiApi.check(url, token)
                appPreferences.setValue(appPreferences.jrwiUrl, url)
                appPreferences.setValue(appPreferences.jrwiToken, token)
                _state.update { it.copy(checking = false, connected = true, version = version) }
                _messages.send(JrwiMessages.SAVED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, error = e.errorText()) }
            }
        }
    }

    fun disconnect() {
        appPreferences.setValue(appPreferences.jrwiUrl, null)
        appPreferences.setValue(appPreferences.jrwiToken, null)
        _state.update { it.copy(connected = false, version = null, token = "") }
    }

    private fun normalizeUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "http://$trimmed"
    }
}

/** The archiver's pictures of a PDF's pages, when its preview has them. */
private fun jrwiPdfSource(preview: JrwiPreview?): JrwiPdfSource.Archiver? {
    val pageUrl = preview?.pageUrl ?: return null
    val pages = preview.pages ?: 0
    return if (pages > 0) JrwiPdfSource.Archiver(pageUrl, pages) else null
}

/**
 * A copy of one of the archive's files in the app's cache, fetched now unless it's there already,
 * for drawing a PDF on the phone or handing a file to another app.
 */
private suspend fun jrwiCachedCopy(
    jrwiApi: JrwiApi,
    jrwiDownloads: JrwiDownloads,
    file: JrwiPostFile,
): File =
    withContext(Dispatchers.IO) {
        val url = file.url ?: throw IOException("This file isn't in the archive")
        val target = jrwiDownloads.cacheFileFor(file.path ?: url)
        if (!target.isFile || (file.size != null && target.length() != file.size)) {
            jrwiApi.openFile(url).use { response ->
                if (!response.isSuccessful) {
                    throw JrwiApiException(response.code, "The archiver answered ${response.code}")
                }
                target.parentFile?.mkdirs()
                val temp = File(target.path + ".part")
                temp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                if (!temp.renameTo(target)) throw IOException("Could not keep ${file.name}")
            }
        }
        target
    }
