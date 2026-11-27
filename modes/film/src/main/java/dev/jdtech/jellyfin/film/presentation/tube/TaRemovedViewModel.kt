package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaRemovedState(
    /** Newest first. */
    val videos: List<TaVideo> = emptyList(),
    /** How many the server's stats count as removed, once read. */
    val expected: Long? = null,
    val loading: Boolean = true,
    /**
     * While every video is looked through for those the search leaves out: how many have been, of
     * how many there are.
     */
    val scanned: Int = 0,
    val scanTotal: Int? = null,
    val error: String? = null,
)

/**
 * Every video on the server that's gone from YouTube, as counted under Video types on Home's server
 * side. TubeArchivist's video list can't be filtered by that, but its search can (`active:no`),
 * though it gives 30 at most; when the stats count more, every video is looked through for the
 * rest, a few pages at a time.
 */
@HiltViewModel
class TaRemovedViewModel @Inject constructor(private val taApi: TaApi) : ViewModel() {
    private val _state = MutableStateFlow(TaRemovedState())
    val state = _state.asStateFlow()

    private var job: Job? = null

    init {
        // Watched in the player or on a video's page
        taApi.followWatchedVideos(
            viewModelScope,
            shows = { id -> _state.value.videos.any { it.youtubeId == id } },
        ) { id, player ->
            _state.update { it.copy(videos = it.videos.withPlayer(id, player)) }
        }
        load()
    }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = TaRemovedState()
            try {
                val expected =
                    try {
                        taApi.getVideoStats().inactive?.count
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                _state.update { it.copy(expected = expected) }
                val found = taApi.search(REMOVED_QUERY).videos
                add(found)
                val more =
                    if (expected != null) _state.value.videos.size < expected
                    else found.size >= SEARCH_LIMIT
                if (more) scan(expected)
                _state.update { it.copy(loading = false, scanTotal = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, scanTotal = null, error = e.errorText()) }
            }
        }
    }

    /** Looks through every video, newest first, until all [expected] are found. */
    private suspend fun scan(expected: Long?) {
        val first = taApi.getVideos(page = 1, sort = SORT, order = ORDER)
        val lastPage = first.paginate?.lastPage ?: 1
        var scanned = first.data.size
        add(first.data)
        _state.update { it.copy(scanned = scanned, scanTotal = first.paginate?.totalHits) }
        var next = 2
        while (next <= lastPage && (expected == null || _state.value.videos.size < expected)) {
            val pages = (next..minOf(lastPage, next + PAGES_AT_ONCE - 1)).toList()
            val results = coroutineScope {
                pages
                    .map { page ->
                        async { taApi.getVideos(page = page, sort = SORT, order = ORDER) }
                    }
                    .awaitAll()
            }
            results.forEach {
                add(it.data)
                scanned += it.data.size
            }
            _state.update { it.copy(scanned = scanned) }
            next += pages.size
        }
    }

    private fun add(videos: List<TaVideo>) {
        val removed = videos.filter { it.active == false }
        if (removed.isEmpty()) return
        _state.update { state ->
            state.copy(
                videos =
                    (state.videos + removed)
                        .distinctBy { it.youtubeId }
                        .sortedWith(
                            compareByDescending<TaVideo> { it.published.orEmpty() }
                                .thenByDescending { it.dateDownloaded ?: 0L }
                        )
            )
        }
    }

    companion object {
        /** TubeArchivist's search for videos no longer on YouTube. */
        private const val REMOVED_QUERY = "video: active:no"
        /** The most TubeArchivist's search gives. */
        private const val SEARCH_LIMIT = 30
        private const val PAGES_AT_ONCE = 4
        private const val SORT = "published"
        private const val ORDER = "desc"
    }
}
