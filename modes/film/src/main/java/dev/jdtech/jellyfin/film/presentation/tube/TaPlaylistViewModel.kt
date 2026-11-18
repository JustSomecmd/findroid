package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TaPlaylistViewModel @Inject constructor(private val taApi: TaApi) : ViewModel() {
    private val _playlist = MutableStateFlow<TaPlaylist?>(null)
    val playlist = _playlist.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _videos = MutableStateFlow(TaListState<TaVideo>())
    val videos = _videos.asStateFlow()

    private var playlistId: String? = null

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
