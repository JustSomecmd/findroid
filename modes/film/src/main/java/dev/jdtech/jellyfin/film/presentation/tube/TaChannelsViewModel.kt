package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaChannel
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistCache
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

enum class TaChannelsTab {
    SUBSCRIBED,
    ALL,
    PLAYLISTS,
}

@HiltViewModel
class TaChannelsViewModel
@Inject
constructor(private val taApi: TaApi, private val playlistCache: TaPlaylistCache) : ViewModel() {
    private val _tab = MutableStateFlow(TaChannelsTab.SUBSCRIBED)
    val tab = _tab.asStateFlow()

    private val _subscribed = MutableStateFlow(TaListState<TaChannel>())
    val subscribed = _subscribed.asStateFlow()

    private val _all = MutableStateFlow(TaListState<TaChannel>())
    val all = _all.asStateFlow()

    private val _playlists = MutableStateFlow(TaListState<TaPlaylist>())
    val playlists = _playlists.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /**
     * Loads the next page of playlists, and keeps the list for when TubeArchivist can't be reached.
     * Without a connection the list saved before shows instead.
     */
    private suspend fun loadMorePlaylists() {
        _playlists.loadNextPage(key = { it.id }) { page -> taApi.getPlaylists(page = page) }
        val state = _playlists.value
        if (state.error == null) {
            playlistCache.savePlaylists(state.items)
        } else if (state.items.isEmpty()) {
            val saved = playlistCache.loadPlaylists()
            if (saved.isNotEmpty()) {
                _playlists.value = TaListState(items = saved, page = 0, hasMore = false)
            }
        }
    }

    /** Makes a new playlist in TubeArchivist, to add videos to from their pages. */
    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                taApi.createCustomPlaylist(name)
                _messages.send(TaMessages.PLAYLIST_CREATED)
                _playlists.value = TaListState()
                loadMorePlaylists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            }
        }
    }

    /** Remembers the shown tab. Each tab's list loads its pages as it scrolls. */
    fun selectTab(tab: TaChannelsTab) {
        _tab.value = tab
    }

    /** Loads the next page of a tab's list, the shown tab's by default. */
    fun loadMore(tab: TaChannelsTab = _tab.value) {
        viewModelScope.launch {
            when (tab) {
                TaChannelsTab.SUBSCRIBED ->
                    _subscribed.loadNextPage(key = { it.id }) { page ->
                        taApi.getChannels(page = page, subscribedOnly = true)
                    }
                TaChannelsTab.ALL ->
                    _all.loadNextPage(key = { it.id }) { page -> taApi.getChannels(page = page) }
                TaChannelsTab.PLAYLISTS -> loadMorePlaylists()
            }
        }
    }

    fun refresh() {
        _subscribed.value = TaListState()
        _all.value = TaListState()
        _playlists.value = TaListState()
        loadMore()
    }

    /**
     * Subscribes to one or more channels, given as URLs, handles or IDs, or to playlists, given as
     * URLs or IDs.
     */
    fun subscribe(input: String, playlists: Boolean = false) {
        val urls = input.split(Regex("""[\s,]+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) return
        viewModelScope.launch {
            try {
                if (playlists) {
                    taApi.subscribeToPlaylists(urls)
                    _messages.send(TaMessages.PLAYLIST_SUBSCRIBE_STARTED)
                } else {
                    taApi.subscribeToChannels(urls)
                    _messages.send(TaMessages.SUBSCRIBE_STARTED)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _messages.send(e.errorText())
            }
        }
    }
}

/** Fixed messages the screens turn into text. Anything else is shown as it is. */
object TaMessages {
    const val SUBSCRIBE_STARTED = "ta:subscribe_started"
    const val PLAYLIST_SUBSCRIBE_STARTED = "ta:playlist_subscribe_started"
    const val ADDED_TO_QUEUE = "ta:added_to_queue"
    const val TASK_STARTED = "ta:task_started"
    const val SAVED = "ta:saved"
    const val REFRESH_STARTED = "ta:refresh_started"
    const val REDOWNLOAD_STARTED = "ta:redownload_started"
    const val DELETED = "ta:deleted"
    const val NOT_IN_JELLYFIN = "ta:not_in_jellyfin"
    const val PLAYLIST_CREATED = "ta:playlist_created"
}
