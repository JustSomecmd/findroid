package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaOfflineStore
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistCache
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class TaPlaylistViewModel
@Inject
constructor(
    private val taApi: TaApi,
    private val cache: TaPlaylistCache,
    private val taOfflineStore: TaOfflineStore,
    private val appPreferences: AppPreferences,
    private val playlistDownloader: TaPlaylistDownloader,
) : ViewModel() {
    private val _playlist = MutableStateFlow<TaPlaylist?>(null)
    val playlist = _playlist.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _videos = MutableStateFlow(TaListState<TaVideo>())
    val videos = _videos.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** True when showing the copy saved on the phone, as TubeArchivist couldn't be reached. */
    private val _offline = MutableStateFlow(false)
    val offline = _offline.asStateFlow()

    /** How far adding this playlist to the phone's downloads has come, while it's going on. */
    val downloadProgress =
        playlistDownloader.progress
            .map { progress -> playlistId?.let { progress[it] } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** What adding the playlist to the phone's downloads came to. */
    val downloadResults = playlistDownloader.results.filter { it.playlistId == playlistId }

    private var playlistId: String? = null

    /** Adds all of the playlist's videos to the phone's download queue. */
    fun downloadAll() {
        val playlist = _playlist.value ?: return
        playlistDownloader.downloadAll(playlist)
    }

    fun setSubscribed(subscribed: Boolean) =
        update({ it.copy(subscribed = subscribed) }) { taApi.setPlaylistSubscribed(it, subscribed) }

    /** Reads the playlist from the bottom up, for playlists that add new videos at the end. */
    fun setReversed(reversed: Boolean) =
        update({ it.copy(sortOrder = if (reversed) "bottom" else "top") }) {
            taApi.setPlaylistSortOrder(it, if (reversed) "bottom" else "top")
        }

    fun refresh(withVideos: Boolean) {
        val playlistId = playlistId ?: return
        viewModelScope.launch {
            try {
                taApi.refresh("playlist", playlistId, withVideos)
                _messages.send(TaMessages.REFRESH_STARTED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            }
        }
    }

    /** Shows a change right away, and puts it back if TubeArchivist refuses it. */
    private fun update(change: (TaPlaylist) -> TaPlaylist, action: suspend (String) -> Unit) {
        val playlistId = playlistId ?: return
        val before = _playlist.value ?: return
        _playlist.value = change(before)
        viewModelScope.launch {
            try {
                action(playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _playlist.value = before
                _messages.send(e.errorText())
            }
        }
    }

    fun load(playlistId: String) {
        if (this.playlistId == playlistId) return
        this.playlistId = playlistId
        viewModelScope.launch {
            // In offline mode the saved copy is all there is
            if (appPreferences.getValue(appPreferences.offlineMode) && loadSaved(playlistId)) {
                return@launch
            }
            try {
                val playlist = taApi.getPlaylist(playlistId)
                _playlist.value = playlist
                cache.updatePlaylist(playlist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!loadSaved(playlistId)) _error.value = e.errorText()
                return@launch
            }
            loadMore()
        }
    }

    /** Shows the playlist as it was last seen. Returns false if it never was. */
    private suspend fun loadSaved(playlistId: String): Boolean {
        val saved = cache.loadPlaylist(playlistId) ?: return false
        _playlist.value = saved.playlist
        val videos =
            withContext(Dispatchers.IO) { saved.orderedVideos { taOfflineStore.savedVideo(it) } }
        _videos.value = TaListState(items = videos, page = 1, hasMore = false)
        _offline.value = true
        _error.value = null
        return true
    }

    fun loadMore() {
        val playlistId = playlistId ?: return
        if (_offline.value) return
        viewModelScope.launch {
            _videos.loadNextPage(key = { it.youtubeId }) { page ->
                taApi.getVideos(page = page, playlist = playlistId)
            }
            if (_offline.value) {
                // A page that failed after the saved copy came up doesn't count
                _videos.update { it.copy(error = null, loading = false, hasMore = false) }
                return@launch
            }
            // Kept for when TubeArchivist can't be reached
            if (_videos.value.error == null) {
                _playlist.value?.let { playlist ->
                    cache.savePlaylist(playlist, _videos.value.items)
                }
            }
        }
    }
}
