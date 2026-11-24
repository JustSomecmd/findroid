package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.jrwi.JrwiDownloads
import dev.jdtech.jellyfin.jrwi.JrwiProgressSync
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.MediaChanges
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaApiException
import dev.jdtech.jellyfin.tubearchivist.TaComment
import dev.jdtech.jellyfin.tubearchivist.TaJellyfinLookup
import dev.jdtech.jellyfin.tubearchivist.TaOfflineStore
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** Shown from what was saved with the download, as TubeArchivist can't be reached. */
    val offline: Boolean = false,
    /** The JRWI item the video is an episode's YouTube version of, for a button to its page. */
    val jrwiItemId: Long? = null,
)

@HiltViewModel
class TaVideoViewModel
@Inject
constructor(
    private val taApi: TaApi,
    private val taJellyfinLookup: TaJellyfinLookup,
    private val repository: JellyfinRepository,
    private val taOfflineStore: TaOfflineStore,
    private val appPreferences: AppPreferences,
    private val jrwiProgress: JrwiProgressSync,
    private val jrwiDownloads: JrwiDownloads,
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
            // In offline mode the saved copy is all there is
            if (firstLoad) launch { findJrwiItem(videoId) }
            if (appPreferences.getValue(appPreferences.offlineMode) && loadSaved(videoId)) {
                return@launch
            }
            // Progress made offline goes first, so the position below is the newest
            taOfflineStore.flushPendingProgress()
            val video =
                try {
                    taApi.getVideo(videoId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!loadSaved(videoId)) _state.update { it.copy(error = e.errorText()) }
                    return@launch
                }
            _state.update { it.copy(video = video, error = null, offline = false) }
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
            MediaChanges.watched(videoId = video.youtubeId)
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

    /** Asks TubeArchivist to look the video up on YouTube again. */
    private val _customPlaylists = MutableStateFlow<List<TaPlaylist>?>(null)
    /** TubeArchivist's own playlists, to save the video to. Null while loading. */
    val customPlaylists = _customPlaylists.asStateFlow()

    /** Loads the playlists made in TubeArchivist, which videos can be added to. */
    fun loadCustomPlaylists() {
        viewModelScope.launch {
            try {
                val playlists = mutableListOf<TaPlaylist>()
                var page = 1
                do {
                    val result = taApi.getPlaylists(page = page, type = "custom")
                    playlists += result.data
                    page++
                } while (result.hasMore && result.data.isNotEmpty() && page <= MAX_PLAYLIST_PAGES)
                _customPlaylists.value = playlists
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _customPlaylists.value = _customPlaylists.value ?: emptyList()
                _messages.send(e.errorText())
            }
        }
    }

    /** Adds the video to a playlist of TubeArchivist's own, or takes it out. */
    fun setInPlaylist(playlist: TaPlaylist, inPlaylist: Boolean) {
        val video = _state.value.video ?: return
        val before = video.playlist
        val after = if (inPlaylist) before + playlist.id else before - playlist.id
        _state.update { it.copy(video = video.copy(playlist = after.distinct())) }
        viewModelScope.launch {
            try {
                val updated =
                    if (inPlaylist) taApi.addToCustomPlaylist(playlist.id, video.youtubeId)
                    else taApi.removeFromCustomPlaylist(playlist.id, video.youtubeId)
                _customPlaylists.value =
                    _customPlaylists.value?.map { if (it.id == updated.id) updated else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { state -> state.copy(video = state.video?.copy(playlist = before)) }
                _messages.send(e.errorText())
            }
        }
    }

    /** Makes a new playlist in TubeArchivist and puts the video in it. */
    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                val playlist = taApi.createCustomPlaylist(name)
                _customPlaylists.value = listOf(playlist) + _customPlaylists.value.orEmpty()
                setInPlaylist(playlist, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            }
        }
    }

    /** Tells the page the video was deleted, so it can close. */
    private val _deleted = Channel<Unit>(Channel.CONFLATED)
    val deleted = _deleted.receiveAsFlow()

    /**
     * Reindexes, downloads again or deletes the video on the server. With [ignore], a deleted video
     * isn't downloaded again.
     */
    fun runAction(action: TaVideoAction, ignore: Boolean = false) {
        val videoId = videoId ?: return
        viewModelScope.launch {
            try {
                _messages.send(taApi.runVideoAction(action, listOf(videoId), ignore))
                if (action == TaVideoAction.DELETE) _deleted.send(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
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

    /** Shows what was saved when the video was downloaded. Returns false if nothing was. */
    private suspend fun loadSaved(videoId: String): Boolean {
        val saved = taOfflineStore.load(videoId) ?: return false
        val episode =
            try {
                repository.getEpisode(UUID.fromString(saved.itemId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not load the downloaded episode")
                null
            }
        _state.update {
            it.copy(
                video = saved.video,
                comments = saved.comments,
                episode = episode,
                lookingUp = false,
                offline = true,
                error = null,
            )
        }
        return true
    }

    /**
     * The JRWI episode the video is the YouTube version of, from the archive, or from what was kept
     * with the video's download when the archive can't be reached.
     */
    private suspend fun findJrwiItem(videoId: String) {
        val itemId =
            jrwiProgress.itemIdForYoutube(videoId)
                ?: withContext(Dispatchers.IO) {
                    taOfflineStore.load(videoId)?.itemId?.let {
                        jrwiDownloads.savedVideoForEpisode(it)?.item?.id
                    }
                }
        if (itemId != null) _state.update { it.copy(jrwiItemId = itemId) }
    }

    private suspend fun findEpisode(video: TaVideo) {
        try {
            // A download already knows its Jellyfin item
            val savedItemId = taOfflineStore.load(video.youtubeId)?.itemId
            val episodeId =
                savedItemId?.let { UUID.fromString(it) }
                    ?: taJellyfinLookup.findEpisodeId(
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
                taOfflineStore.load(video.youtubeId)?.comments.orEmpty()
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

// Enough for every playlist made by hand
private const val MAX_PLAYLIST_PAGES = 10
