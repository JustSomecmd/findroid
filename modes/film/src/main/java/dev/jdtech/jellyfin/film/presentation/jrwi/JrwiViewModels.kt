package dev.jdtech.jellyfin.film.presentation.jrwi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.presentation.tube.TaListState
import dev.jdtech.jellyfin.film.presentation.tube.errorText
import dev.jdtech.jellyfin.film.presentation.tube.loadNextPage
import dev.jdtech.jellyfin.jrwi.JrwiApi
import dev.jdtech.jellyfin.jrwi.JrwiComment
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiPage
import dev.jdtech.jellyfin.jrwi.JrwiProblem
import dev.jdtech.jellyfin.jrwi.JrwiProgressChange
import dev.jdtech.jellyfin.jrwi.JrwiProgressSync
import dev.jdtech.jellyfin.jrwi.JrwiShow
import dev.jdtech.jellyfin.jrwi.JrwiStatus
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.tubearchivist.TaPage
import dev.jdtech.jellyfin.tubearchivist.TaPaginate
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
}

/** [this] with the progress just saved for one of its items. */
private fun List<JrwiItem>.withProgress(change: JrwiProgressChange): List<JrwiItem> {
    return map { if (it.id == change.itemId) it.copy(progress = change.progress) else it }
}

data class JrwiShowsState(
    val configured: Boolean = false,
    val shows: List<JrwiShow> = emptyList(),
    val latest: List<JrwiItem> = emptyList(),
    /** For shows without art of their own, the picture of their newest item, by show ID. */
    val showPictures: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** The JRWI tab: the shows, like the channels on the Channels tab, and what's new. */
@HiltViewModel
class JrwiShowsViewModel
@Inject
constructor(private val jrwiApi: JrwiApi, private val jrwiProgress: JrwiProgressSync) :
    ViewModel() {
    private val _state = MutableStateFlow(JrwiShowsState(configured = jrwiApi.isConfigured))
    val state = _state.asStateFlow()

    private var job: Job? = null

    init {
        // Marks what's been played since the row was loaded
        viewModelScope.launch {
            jrwiProgress.changes.collect { change ->
                _state.update { it.copy(latest = it.latest.withProgress(change)) }
            }
        }
    }

    /** Loads the shows, unless they're in already. [force] loads them again anyway. */
    fun load(force: Boolean = false) {
        val configured = jrwiApi.isConfigured
        _state.update { it.copy(configured = configured) }
        if (!configured) return
        if (!force && (_state.value.shows.isNotEmpty() || job?.isActive == true)) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val shows = jrwiApi.getShows()
                val latest =
                    try {
                        jrwiApi.getLatest(limit = LATEST_COUNT)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                _state.update {
                    it.copy(shows = shows, latest = latest, loading = false, error = null)
                }
                loadShowPictures(shows, latest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.errorText()) }
            }
        }
    }

    /**
     * Shows whose page on the site has no art of its own get the picture of their newest item, as
     * on the archiver's web app: from the Latest row when it has one of theirs, or else asked for.
     */
    private suspend fun loadShowPictures(shows: List<JrwiShow>, latest: List<JrwiItem>) {
        val missing = shows.filter { it.poster == null && it.banner == null }.map { it.id }
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

        // Text posts have no picture, so a few items in case the newest are those
        const val PICTURE_SEARCH_SIZE = 10
    }
}

/** A show's page: its tabs, and a list for each that loads as it's scrolled. */
@HiltViewModel
class JrwiShowViewModel
@Inject
constructor(private val jrwiApi: JrwiApi, private val jrwiProgress: JrwiProgressSync) :
    ViewModel() {
    private val _show = MutableStateFlow<JrwiShow?>(null)
    val show = _show.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val lists = mutableMapOf<String, MutableStateFlow<TaListState<JrwiItem>>>()

    private var showId: String? = null

    init {
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
            }
        }
    }

    /** The list of one tab, such as episodes or aftershow. */
    fun items(tab: String): MutableStateFlow<TaListState<JrwiItem>> =
        lists.getOrPut(tab) { MutableStateFlow(TaListState()) }

    fun loadMore(tab: String) {
        val showId = showId ?: return
        viewModelScope.launch {
            items(tab).loadNextPage(key = { it.id.toString() }) { page ->
                // Episodes go by number, newest first, as on the site
                jrwiApi.getShowItems(showId, tab, page = page).asTaPage()
            }
        }
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
)

/**
 * One post's own text, pictures, files and comments. For an episode with both a video and an audio
 * version, each version's post has its own; otherwise it's the whole item.
 */
data class JrwiItemContent(
    val post: JrwiItem? = null,
    val error: String? = null,
    val comments: TaListState<JrwiComment> = TaListState(),
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
) : ViewModel() {
    private val _state = MutableStateFlow(JrwiItemState())
    val state = _state.asStateFlow()

    private val _events = Channel<JrwiItemEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Each post's own content and comments, kept for switching back, by post ID
    private val contents = mutableMapOf<Long, MutableStateFlow<JrwiItemContent>>()
    private val commentLists = mutableMapOf<Long, MutableStateFlow<TaListState<JrwiComment>>>()
    private val postsLoading = mutableSetOf<Long>()
    private var contentJob: Job? = null

    private var postId: Long? = null

    /** Loads the item, or loads its position again when it's in already. */
    fun load(postId: Long) {
        if (this.postId != postId) {
            this.postId = postId
            _state.value = JrwiItemState()
            contents.clear()
            commentLists.clear()
        }
        viewModelScope.launch {
            try {
                val loaded = _state.value.item
                val item = loaded ?: jrwiApi.getItem(postId)
                if (loaded == null) {
                    // The video, unless there's only the audio
                    _state.update {
                        it.copy(item = item, error = null, playsAudio = item.video == null)
                    }
                }
                // Also tries again what didn't load before
                showContent(item, _state.value.playsAudio)
                refreshProgress(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.errorText()) }
            }
        }
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
        val postId = contentPostId(item, audio)
        val content = contents.getOrPut(postId) { MutableStateFlow(JrwiItemContent()) }
        // In one go, so the page never shows one version's comments under the other's switch
        _state.update { it.copy(playsAudio = audio, content = content.value) }
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            content.collect { loaded -> _state.update { it.copy(content = loaded) } }
        }
        if (content.value.post == null) loadPost(item, postId, content)
        if (content.value.comments.page == 0) loadMoreComments()
    }

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
        val list = commentLists.getOrPut(postId) { MutableStateFlow(TaListState()) }
        viewModelScope.launch {
            list.loadNextPage(key = { it.id }) { page ->
                if (item.hasBoth) jrwiApi.getPostComments(postId, page).asTaPage()
                else jrwiApi.getComments(postId, page).asTaPage()
            }
            content.update { it.copy(comments = list.value) }
        }
    }

    /** Plays the video: from Jellyfin, or from TubeArchivist when it's only on YouTube. */
    fun playVideo(fromStart: Boolean = false) {
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
            val episodeId = jrwiProgress.episodeId(item)
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

data class JrwiServerState(
    val status: JrwiStatus? = null,
    val problems: List<JrwiProblem> = emptyList(),
    val error: String? = null,
)

/** The archiver itself: what it's doing, a check now, and posts it couldn't save. */
@HiltViewModel
class JrwiServerViewModel @Inject constructor(private val jrwiApi: JrwiApi) : ViewModel() {
    private val _state = MutableStateFlow(JrwiServerState())
    val state = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun load() {
        viewModelScope.launch {
            try {
                val status = jrwiApi.getStatus()
                val problems = jrwiApi.getProblems()
                _state.update { it.copy(status = status, problems = problems, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.errorText()) }
            }
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
