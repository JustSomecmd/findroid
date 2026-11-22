package dev.jdtech.jellyfin.film.presentation.jrwi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.presentation.tube.errorText
import dev.jdtech.jellyfin.jrwi.JrwiApi
import dev.jdtech.jellyfin.jrwi.JrwiApiException
import dev.jdtech.jellyfin.jrwi.JrwiItem
import dev.jdtech.jellyfin.jrwi.JrwiProgressSync
import dev.jdtech.jellyfin.jrwi.JrwiShow
import dev.jdtech.jellyfin.jrwi.JrwiTagCount
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class JrwiSearchState(
    val query: String = "",
    /** Only the posts the site's tags put in this show, or in every show when null. */
    val show: String? = null,
    /** Only the posts in this tab: episodes, aftershow, extras, art or posts. */
    val tab: String? = null,
    /** Only the posts with this tag, on archivers that list their tags. */
    val tag: String? = null,
    /** desc for newest first, asc for oldest first. */
    val order: String = "desc",
    val shows: List<JrwiShow> = emptyList(),
    /** The site's tags, once the archiver has listed them; null until then. */
    val tags: List<JrwiTagCount>? = null,
    /** The archiver doesn't list its tags, so posts can't be kept to one. */
    val tagsUnsupported: Boolean = false,
    /** Every post found so far, newest first unless [order] says otherwise. */
    val results: List<JrwiItem> = emptyList(),
    /** How many posts were found in all. */
    val total: Int = 0,
    /** A search was made with what's set now. */
    val searched: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val filtered: Boolean
        get() = show != null || tab != null || tag != null

    /** Something to search for: words, or a show, tab or tag. */
    val active: Boolean
        get() = query.isNotBlank() || filtered
}

/**
 * Searches the archive's posts as the web app's feed does: by their title and text, kept to a show,
 * tab or tag, newest or oldest first. Every post is listed on its own, as in the feed.
 */
@HiltViewModel
class JrwiSearchViewModel
@Inject
constructor(private val jrwiApi: JrwiApi, private val jrwiProgress: JrwiProgressSync) :
    ViewModel() {
    private val _state = MutableStateFlow(JrwiSearchState())
    val state = _state.asStateFlow()

    private var searchJob: Job? = null
    private var page = 0
    // Posts whose progress is being asked for, or was
    private val progressAsked = mutableSetOf<Long>()

    init {
        viewModelScope.launch { loadFilters() }
        // Marks what's been played since the results came
        viewModelScope.launch {
            jrwiProgress.changes.collect { change ->
                _state.update { state ->
                    state.copy(results = state.results.map { it.withEpisodeProgress(change) })
                }
            }
        }
    }

    /** The shows and tags to pick from. */
    private suspend fun loadFilters() {
        try {
            val shows = jrwiApi.getShows()
            _state.update { it.copy(shows = shows) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "The shows could not be loaded for the search")
        }
        try {
            val tags = jrwiApi.getTags().sortedBy { it.name.lowercase() }
            _state.update { it.copy(tags = tags, tagsUnsupported = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: JrwiApiException) {
            // Archivers without the tag list answer 404, and can't keep posts to a tag either
            if (e.code == NOT_FOUND) _state.update { it.copy(tagsUnsupported = true) }
        } catch (e: Exception) {
            Timber.d(e, "The tags could not be loaded for the search")
        }
    }

    /** Searches for what's typed, once typing stops for a moment. */
    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        search(wait = true)
    }

    fun setShow(show: String?) {
        _state.update { it.copy(show = show) }
        search()
    }

    fun setTab(tab: String?) {
        _state.update { it.copy(tab = tab) }
        search()
    }

    fun setTag(tag: String?) {
        _state.update { it.copy(tag = tag) }
        search()
    }

    /** Newest first, or oldest first. */
    fun toggleOrder() {
        _state.update { it.copy(order = if (it.order == "asc") "desc" else "asc") }
        search()
    }

    fun clearFilters() {
        _state.update { it.copy(show = null, tab = null, tag = null) }
        search()
    }

    /** Searches right away, as when the keyboard's search key is pressed. */
    fun searchNow() = search()

    private fun search(wait: Boolean = false) {
        searchJob?.cancel()
        progressAsked.clear()
        if (!_state.value.active) {
            _state.update {
                it.copy(
                    results = emptyList(),
                    total = 0,
                    searched = false,
                    loading = false,
                    error = null,
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            if (wait) delay(TYPING_PAUSE_MS)
            page = 0
            loadPage(reset = true)
        }
    }

    /** The next page of results, as the list is scrolled near its end. */
    fun loadMore() {
        val state = _state.value
        if (searchJob?.isActive == true || !state.searched || state.results.size >= state.total) {
            return
        }
        searchJob = viewModelScope.launch { loadPage(reset = false) }
    }

    private suspend fun loadPage(reset: Boolean) {
        val state = _state.value
        val next = page + 1
        _state.update { it.copy(loading = true, error = null) }
        try {
            val result =
                jrwiApi.getPosts(
                    page = next,
                    pageSize = PAGE_SIZE,
                    order = state.order,
                    query = state.query.trim().takeIf { it.isNotEmpty() },
                    show = state.show,
                    tab = state.tab,
                    // Only archivers that list their tags keep posts to one
                    tag = state.tag?.takeIf { state.tags != null },
                )
            page = next
            _state.update { current ->
                val shown = if (reset) emptyList() else current.results
                val ids = shown.map { it.id }.toSet()
                current.copy(
                    results = shown + result.items.filter { it.id !in ids },
                    total = result.total,
                    searched = true,
                    loading = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, searched = true, error = e.errorText()) }
        }
    }

    /** A result's progress, for its watched mark, once it shows. Asked for once per search. */
    fun loadProgress(postId: Long) {
        val post = _state.value.results.firstOrNull { it.id == postId } ?: return
        if (post.progress != null || !progressAsked.add(postId)) return
        viewModelScope.launch {
            val progress = jrwiApi.postProgress(post) ?: return@launch
            _state.update { state ->
                state.copy(
                    results =
                        state.results.map {
                            if (it.id == postId) it.copy(progress = progress) else it
                        }
                )
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val TYPING_PAUSE_MS = 400L
        const val NOT_FOUND = 404
    }
}
