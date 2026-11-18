package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaApiException
import dev.jdtech.jellyfin.tubearchivist.TaComment
import dev.jdtech.jellyfin.tubearchivist.TaJellyfinLookup
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class TaVideoState(
    val video: TaVideo? = null,
    val error: String? = null,
    /** The Jellyfin copy, which is what plays and downloads. */
    val episode: FindroidEpisode? = null,
    val lookingUp: Boolean = true,
    /** Null while loading. */
    val comments: List<TaComment>? = null,
    val similar: List<TaVideo> = emptyList(),
)

@HiltViewModel
class TaVideoViewModel
@Inject
constructor(
    private val taApi: TaApi,
    private val taJellyfinLookup: TaJellyfinLookup,
    private val repository: JellyfinRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TaVideoState())
    val state = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var videoId: String? = null

    fun load(videoId: String) {
        val firstLoad = this.videoId != videoId
        this.videoId = videoId
        viewModelScope.launch {
            val video =
                try {
                    taApi.getVideo(videoId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.errorText()) }
                    return@launch
                }
            _state.update { it.copy(video = video, error = null) }
            launch { findEpisode(video) }
            if (firstLoad) {
                launch { loadComments(video) }
                launch { loadSimilar(videoId) }
            }
        }
    }

    /** Loads the Jellyfin copy again, after downloading or deleting it. */
    fun reloadEpisode() {
        val video = _state.value.video ?: return
        viewModelScope.launch { findEpisode(video) }
    }

    fun toggleWatched() {
        val video = _state.value.video ?: return
        val player = video.player ?: return
        val watched = !player.watched
        _state.update {
            it.copy(video = video.copy(player = player.copy(watched = watched, progress = null)))
        }
        viewModelScope.launch {
            try {
                taApi.setWatched(video.youtubeId, watched)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(video = video) }
                _messages.send(e.errorText())
                return@launch
            }
            // Keep Jellyfin in step
            _state.value.episode?.let { episode ->
                try {
                    if (watched) repository.markAsPlayed(episode.id)
                    else repository.markAsUnplayed(episode.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e)
                }
                findEpisode(video)
            }
        }
    }

    /** Favorites only exist in Jellyfin. */
    fun toggleFavorite() {
        val episode = _state.value.episode ?: return
        val video = _state.value.video ?: return
        viewModelScope.launch {
            try {
                if (episode.favorite) repository.unmarkAsFavorite(episode.id)
                else repository.markAsFavorite(episode.id)
                findEpisode(video)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            }
        }
    }

    private suspend fun findEpisode(video: TaVideo) {
        try {
            val episodeId =
                taJellyfinLookup.findEpisodeId(
                    videoId = video.youtubeId,
                    title = video.title,
                    channelId = video.channel?.id,
                    channelName = video.channel?.name,
                )
            val episode = episodeId?.let { repository.getEpisode(it) }
            _state.update { it.copy(episode = episode, lookingUp = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not find the video in Jellyfin")
            _state.update { it.copy(lookingUp = false) }
        }
    }

    private suspend fun loadComments(video: TaVideo) {
        val comments =
            try {
                taApi.getComments(video.youtubeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TaApiException) {
                // No comments were saved for this video
                emptyList()
            } catch (e: Exception) {
                Timber.w(e, "Could not load comments")
                emptyList()
            }
        _state.update { it.copy(comments = comments) }
    }

    private suspend fun loadSimilar(videoId: String) {
        try {
            val similar = taApi.getSimilar(videoId).filter { it.youtubeId != videoId }
            _state.update { it.copy(similar = similar) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not load similar videos")
        }
    }
}
