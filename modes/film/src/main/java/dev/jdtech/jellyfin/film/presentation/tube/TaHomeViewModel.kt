package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaJellyfinLookup
import dev.jdtech.jellyfin.tubearchivist.TaOfflineStore
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class TaHomeViewModel
@Inject
constructor(
    private val taApi: TaApi,
    private val taOfflineStore: TaOfflineStore,
    private val taJellyfinLookup: TaJellyfinLookup,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _servers = MutableStateFlow(TaServersInfo())
    /** The Jellyfin and TubeArchivist servers the app is connected to. */
    val servers = _servers.asStateFlow()

    private val _continueWatching = MutableStateFlow<List<TaVideo>>(emptyList())
    val continueWatching = _continueWatching.asStateFlow()

    private val _latest = MutableStateFlow(TaListState<TaVideo>())
    val latest = _latest.asStateFlow()

    private var lastLoad = 0L

    /** Loads the page the first time, and refreshes it when coming back after a while. */
    fun onShown() {
        // Anything that couldn't be sent before
        viewModelScope.launch { taOfflineStore.sync() }
        loadServers()
        val now = System.currentTimeMillis()
        if (_latest.value.page == 0) {
            lastLoad = now
            loadMore()
            loadContinueWatching()
        } else if (now - lastLoad > STALE_AFTER_MS) {
            refresh()
        } else {
            // Progress may have changed while watching
            loadContinueWatching()
        }
    }

    fun refresh() {
        lastLoad = System.currentTimeMillis()
        _latest.value = TaListState()
        loadMore()
        loadContinueWatching()
    }

    fun loadMore() {
        viewModelScope.launch {
            _latest.loadNextPage(key = { it.youtubeId }) { page ->
                taApi.getVideos(page = page, sort = "downloaded", order = "desc")
            }
        }
    }

    /** Names and addresses for the header, and whether TubeArchivist answers. */
    fun loadServers() {
        viewModelScope.launch {
            val serverId = appPreferences.getValue(appPreferences.currentServer)
            val server = serverId?.let { database.getServer(it) }
            _servers.update {
                it.copy(
                    jellyfinServerId = serverId,
                    jellyfinName = server?.name,
                    jellyfinAddress =
                        serverId?.let { id -> database.getServerCurrentAddress(id) }?.address,
                    jellyfinUser = serverId?.let { id -> database.getServerCurrentUser(id) }?.name,
                    taAddress = taApi.baseUrl,
                )
            }
            try {
                val version = taApi.ping(taApi.baseUrl.orEmpty(), taApi.token.orEmpty())
                _servers.update { it.copy(taVersion = version, taError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _servers.update { it.copy(taError = e.errorText()) }
            }
        }
    }

    private fun loadContinueWatching() {
        viewModelScope.launch {
            try {
                val videos = taApi.getVideos(watch = "continue").data
                // Most recently watched first. TubeArchivist has no order for these, so Jellyfin's
                // last played dates are used, with anything Jellyfin doesn't know about after
                val recent =
                    try {
                        taJellyfinLookup.recentlyPlayedVideoIds()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Could not get the play order from Jellyfin")
                        emptyList()
                    }
                _continueWatching.value =
                    videos.reversed().sortedBy { video ->
                        recent.indexOf(video.youtubeId).let { if (it < 0) Int.MAX_VALUE else it }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not load continue watching")
            }
        }
    }

    companion object {
        private const val STALE_AFTER_MS = 60_000L
    }
}

data class TaServersInfo(
    val jellyfinServerId: String? = null,
    val jellyfinName: String? = null,
    val jellyfinAddress: String? = null,
    val jellyfinUser: String? = null,
    val taAddress: String? = null,
    val taVersion: String? = null,
    val taError: String? = null,
)
