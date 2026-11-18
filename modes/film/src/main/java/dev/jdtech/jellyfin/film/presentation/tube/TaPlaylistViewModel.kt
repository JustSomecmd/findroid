package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TaPlaylistViewModel @Inject constructor(private val taApi: TaApi) : ViewModel() {
    private val _playlist = MutableStateFlow<TaPlaylist?>(null)
    val playlist = _playlist.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _videos = MutableStateFlow(TaListState<TaVideo>())
    val videos = _videos.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var playlistId: String? = null

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
            try {
                _playlist.value = taApi.getPlaylist(playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.errorText()
            }
        }
        loadMore()
    }

    fun loadMore() {
        val playlistId = playlistId ?: return
        viewModelScope.launch {
            _videos.loadNextPage(key = { it.youtubeId }) { page ->
                taApi.getVideos(page = page, playlist = playlistId)
            }
        }
    }
}
