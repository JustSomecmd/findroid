package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaChannel
import dev.jdtech.jellyfin.tubearchivist.TaChannelNav
import dev.jdtech.jellyfin.tubearchivist.TaChannelTotals
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

enum class TaChannelTab(val videoType: String?) {
    VIDEOS("videos"),
    SHORTS("shorts"),
    STREAMS("streams"),
    PLAYLISTS(null),
}

data class TaChannelState(
    val channel: TaChannel? = null,
    val nav: TaChannelNav? = null,
    val tab: TaChannelTab = TaChannelTab.VIDEOS,
    val error: String? = null,
    /** Showing the channel's side on the server: its settings and details. */
    val serverSide: Boolean = false,
    /** How many videos the channel has on the server, their size and playback time. */
    val totals: TaChannelTotals? = null,
    /** Sizes in GB rather than GiB, as set in TubeArchivist. */
    val metricSizes: Boolean = false,
) {
    /** The tabs this channel has anything in. */
    val tabs: List<TaChannelTab>
        get() {
            val nav = nav ?: return listOf(TaChannelTab.VIDEOS)
            return buildList {
                if (nav.hasVideos || (!nav.hasShorts && !nav.hasStreams)) add(TaChannelTab.VIDEOS)
                if (nav.hasShorts) add(TaChannelTab.SHORTS)
                if (nav.hasStreams) add(TaChannelTab.STREAMS)
                if (nav.hasPlaylists) add(TaChannelTab.PLAYLISTS)
            }
        }
}

@HiltViewModel
class TaChannelViewModel @Inject constructor(private val taApi: TaApi) : ViewModel() {
    private val _state = MutableStateFlow(TaChannelState())
    val state = _state.asStateFlow()

    private val _videos = MutableStateFlow(TaListState<TaVideo>())
    val videos = _videos.asStateFlow()

    private val _playlists = MutableStateFlow(TaListState<TaPlaylist>())
    val playlists = _playlists.asStateFlow()

    /** TubeArchivist's global settings, to show what a channel without its own setting uses. */
    private val _globalConfig = MutableStateFlow<JsonObject?>(null)
    val globalConfig = _globalConfig.asStateFlow()

    /** All the channel's videos on the server side, newest first, to pick some to act on. */
    private val _serverVideos = MutableStateFlow(TaListState<TaVideo>())
    val serverVideos = _serverVideos.asStateFlow()

    /** IDs of the videos picked on the server side. */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected = _selected.asStateFlow()

    /** True while an action on the picked videos is being sent. */
    private val _working = MutableStateFlow(false)
    val working = _working.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var channelId: String? = null

    fun load(channelId: String) {
        if (this.channelId == channelId && _state.value.channel != null) return
        this.channelId = channelId
        viewModelScope.launch {
            try {
                val channel = async { taApi.getChannel(channelId) }
                val nav = async {
                    try {
                        taApi.getChannelNav(channelId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                _state.update {
                    it.copy(channel = channel.await(), nav = nav.await(), error = null)
                }
                val tabs = _state.value.tabs
                if (_state.value.tab !in tabs) {
                    _state.update { it.copy(tab = tabs.firstOrNull() ?: TaChannelTab.VIDEOS) }
                }
                loadMore()
                loadTotals(channelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.errorText()) }
            }
        }
    }

    /** The channel's totals, left out when TubeArchivist can't count them. */
    private suspend fun loadTotals(channelId: String) {
        try {
            coroutineScope {
                val metric = async { taApi.usesMetricSizes() }
                val totals = taApi.getChannelTotals(channelId)
                _state.update { it.copy(totals = totals, metricSizes = metric.await()) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not load the totals of channel $channelId")
        }
    }

    fun selectTab(tab: TaChannelTab) {
        if (tab == _state.value.tab) return
        _state.update { it.copy(tab = tab) }
        if (tab.videoType != null) _videos.value = TaListState()
        loadMore()
    }

    /**
     * Notes which side of the channel is shown, loading the global settings for the server side.
     */
    fun setServerSide(serverSide: Boolean) {
        if (_state.value.serverSide == serverSide) return
        _state.update { it.copy(serverSide = serverSide) }
        if (serverSide) loadGlobalConfig()
    }

    /** Asks TubeArchivist to look the channel up again, and its videos too with [withVideos]. */
    fun refresh(withVideos: Boolean) {
        val channelId = channelId ?: return
        viewModelScope.launch {
            try {
                taApi.refresh("channel", channelId, withVideos)
                _messages.send(TaMessages.REFRESH_STARTED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            }
        }
    }

    private fun loadGlobalConfig() {
        if (_globalConfig.value != null) return
        viewModelScope.launch {
            try {
                _globalConfig.value = taApi.getConfig()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not load TubeArchivist settings")
            }
        }
    }

    /** Changes one of the channel's own settings. JsonNull goes back to the global one. */
    fun setOverwrite(key: String, value: JsonElement) {
        val channel = _state.value.channel ?: return
        val before = channel.overwrites ?: JsonObject(emptyMap())
        val updated =
            if (value is JsonNull) JsonObject(before - key) else JsonObject(before + (key to value))
        _state.update { it.copy(channel = channel.copy(overwrites = updated)) }
        viewModelScope.launch {
            try {
                taApi.setChannelOverwrite(channel.id, key, value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(channel = channel) }
                _messages.send(e.errorText())
            }
        }
    }

    fun loadMore() {
        val channelId = channelId ?: return
        val tab = _state.value.tab
        viewModelScope.launch {
            if (tab == TaChannelTab.PLAYLISTS) {
                _playlists.loadNextPage(key = { it.id }) { page ->
                    taApi.getPlaylists(page = page, channel = channelId)
                }
            } else {
                _videos.loadNextPage(key = { it.youtubeId }) { page ->
                    taApi.getVideos(
                        page = page,
                        channel = channelId,
                        type = tab.videoType,
                        sort = "published",
                        order = "desc",
                    )
                }
            }
        }
    }

    fun setSubscribed(subscribed: Boolean) {
        val channel = _state.value.channel ?: return
        _state.update { it.copy(channel = channel.copy(subscribed = subscribed)) }
        viewModelScope.launch {
            try {
                taApi.setChannelSubscribed(channel.id, subscribed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(channel = channel) }
                _messages.send(e.errorText())
            }
        }
    }

    fun loadMoreServerVideos() {
        val channelId = channelId ?: return
        viewModelScope.launch {
            _serverVideos.loadNextPage(key = { it.youtubeId }) { page ->
                taApi.getVideos(
                    page = page,
                    channel = channelId,
                    sort = "published",
                    order = "desc",
                )
            }
        }
    }

    fun toggleSelected(videoId: String) {
        _selected.update { if (videoId in it) it - videoId else it + videoId }
    }

    /** Picks every video loaded so far, or none if they all are picked already. */
    fun toggleSelectAll() {
        val loaded = _serverVideos.value.items.map { it.youtubeId }.toSet()
        _selected.update { if (it.containsAll(loaded)) emptySet() else loaded }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    /**
     * Reindexes, downloads again or deletes the picked videos. With [ignore], deleted videos are
     * not downloaded again.
     */
    fun runOnSelected(action: TaVideoAction, ignore: Boolean = false) {
        val ids = _selected.value
        if (ids.isEmpty() || _working.value) return
        _working.value = true
        viewModelScope.launch {
            try {
                _messages.send(taApi.runVideoAction(action, ids, ignore))
                _selected.value = emptySet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            } finally {
                _working.value = false
                if (action == TaVideoAction.DELETE) {
                    // What's gone leaves both lists, including any deleted before an error
                    _serverVideos.value = TaListState()
                    loadMoreServerVideos()
                    _videos.value = TaListState()
                    if (_state.value.tab != TaChannelTab.PLAYLISTS) loadMore()
                    channelId?.let { id -> viewModelScope.launch { loadTotals(id) } }
                }
            }
        }
    }

    /** Adds every video of the channel that isn't downloaded yet to the download queue. */
    fun queueChannel() {
        val channel = _state.value.channel ?: return
        viewModelScope.launch {
            try {
                taApi.addToQueue(listOf(channel.id), autostart = false)
                _messages.send(TaMessages.ADDED_TO_QUEUE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            }
        }
    }
}
