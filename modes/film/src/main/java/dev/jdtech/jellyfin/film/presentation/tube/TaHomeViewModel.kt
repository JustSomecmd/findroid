package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.tubearchivist.TA_WATCH_LATER
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaChannel
import dev.jdtech.jellyfin.tubearchivist.TaJellyfinLookup
import dev.jdtech.jellyfin.tubearchivist.TaOfflineStore
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistCache
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistSession
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistSessions
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

@HiltViewModel
class TaHomeViewModel
@Inject
constructor(
    private val taApi: TaApi,
    private val taOfflineStore: TaOfflineStore,
    private val taJellyfinLookup: TaJellyfinLookup,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    private val playlistSessions: TaPlaylistSessions,
    private val playlistCache: TaPlaylistCache,
) : ViewModel() {
    private val _servers = MutableStateFlow(TaServersInfo())
    /** The Jellyfin and TubeArchivist servers the app is connected to. */
    val servers = _servers.asStateFlow()

    private val _continueWatching = MutableStateFlow<List<TaContinueItem>>(emptyList())
    /** Videos part way through, and playlists being watched through, most recent first. */
    val continueWatching = _continueWatching.asStateFlow()

    /** Asks the page to start the player, to carry on with a playlist. */
    private val _play = Channel<TaPlayRequest>(Channel.BUFFERED)
    val play = _play.receiveAsFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val _latest = MutableStateFlow(TaListState<TaVideo>())
    val latest = _latest.asStateFlow()

    private val _featured = MutableStateFlow<List<TaChannel>>(emptyList())
    /** A few channels picked at random for the carousel at the top. */
    val featured = _featured.asStateFlow()

    /** The playlist behind Home's Watch Later button, once looked up. */
    private val _watchLater = MutableStateFlow<TaWatchLater>(TaWatchLater.Unknown)

    private val _watchLaterBusy = MutableStateFlow(false)
    /** True while Watch Later is being looked up or made after a tap. */
    val watchLaterBusy = _watchLaterBusy.asStateFlow()

    private val _offerWatchLater = MutableStateFlow(false)
    /** True when Watch Later was tapped but there's no such playlist, to offer to make it. */
    val offerWatchLater = _offerWatchLater.asStateFlow()

    /** Asks the page to open a playlist. */
    private val _openPlaylist = Channel<String>(Channel.BUFFERED)
    val openPlaylist = _openPlaylist.receiveAsFlow()

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
            loadFeatured()
            loadWatchLater()
        } else if (now - lastLoad > STALE_AFTER_MS) {
            refresh()
        } else {
            // Progress may have changed while watching, and videos may have been saved for later
            loadContinueWatching()
            loadWatchLater()
        }
    }

    fun refresh() {
        lastLoad = System.currentTimeMillis()
        _latest.value = TaListState()
        loadMore()
        loadContinueWatching()
        loadFeatured()
        loadWatchLater()
    }

    /**
     * Picks channels at random for the carousel. TubeArchivist can't sort at random, so this takes
     * the channels of the first page and of one other page, and shuffles them.
     */
    private fun loadFeatured() {
        viewModelScope.launch {
            try {
                val first = taApi.getChannels(page = 1)
                val lastPage = first.paginate?.lastPage ?: 1
                val other =
                    if (lastPage > 1) taApi.getChannels(page = (2..lastPage).random()).data
                    else emptyList()
                _featured.value =
                    (first.data + other)
                        .distinctBy { it.id }
                        .filter { it.tvArtUrl != null || it.bannerUrl != null }
                        .shuffled()
                        .take(FEATURED_COUNT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The carousel is left out when TubeArchivist can't be reached
                Timber.w(e, "Couldn't load channels for the carousel")
            }
        }
    }

    fun loadMore() {
        viewModelScope.launch {
            _latest.loadNextPage(key = { it.youtubeId }) { page ->
                taApi.getVideos(page = page, sort = "downloaded", order = "desc")
            }
        }
    }

    /** Looks up Watch Later ahead of time, so the button opens it straight away. */
    private fun loadWatchLater() {
        viewModelScope.launch {
            _watchLater.value =
                try {
                    lookUpWatchLater()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Couldn't look up Watch Later")
                    savedWatchLater() ?: TaWatchLater.Unknown
                }
        }
    }

    private suspend fun lookUpWatchLater(): TaWatchLater =
        taApi.findCustomPlaylist(TA_WATCH_LATER)?.let { TaWatchLater.Found(it) }
            ?: TaWatchLater.Missing

    /**
     * Watch Later from the playlists saved on the phone, for when TubeArchivist can't be reached.
     */
    private suspend fun savedWatchLater(): TaWatchLater.Found? =
        playlistCache
            .loadPlaylists()
            .firstOrNull {
                it.type != "regular" && it.name.trim().equals(TA_WATCH_LATER, ignoreCase = true)
            }
            ?.let { TaWatchLater.Found(it) }

    /**
     * Opens Watch Later. When it isn't known yet it's looked up first, and when there's no such
     * playlist the page is asked to offer to make it.
     */
    fun openWatchLater() {
        val known = _watchLater.value
        if (known is TaWatchLater.Found) {
            viewModelScope.launch { _openPlaylist.send(known.playlistId) }
            return
        }
        if (_watchLaterBusy.value) return
        _watchLaterBusy.value = true
        viewModelScope.launch {
            try {
                val state = lookUpWatchLater()
                _watchLater.value = state
                if (state is TaWatchLater.Found) {
                    _openPlaylist.send(state.playlistId)
                } else {
                    _offerWatchLater.value = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val saved = savedWatchLater()
                if (saved != null) {
                    _watchLater.value = saved
                    _openPlaylist.send(saved.playlistId)
                } else {
                    _messages.send(e.errorText())
                }
            } finally {
                _watchLaterBusy.value = false
            }
        }
    }

    fun dismissWatchLaterOffer() {
        _offerWatchLater.value = false
    }

    /** Makes the Watch Later playlist and opens it. */
    fun createWatchLater() {
        _offerWatchLater.value = false
        if (_watchLaterBusy.value) return
        _watchLaterBusy.value = true
        viewModelScope.launch {
            try {
                // One may have been made somewhere else since the button was tapped
                val playlist =
                    taApi.findCustomPlaylist(TA_WATCH_LATER)
                        ?: taApi.createCustomPlaylist(TA_WATCH_LATER)
                _watchLater.value = TaWatchLater.Found(playlist)
                _openPlaylist.send(playlist.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            } finally {
                _watchLaterBusy.value = false
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
                fun rank(videoId: String): Int =
                    recent.indexOf(videoId).let { if (it < 0) Int.MAX_VALUE else it }

                // A playlist takes the place of its video, ranked by the last video played in it
                val playlists =
                    playlistSessions.sessions.value.take(MAX_PLAYLISTS).mapNotNull { session ->
                        resolvePlaylist(session)?.let { it to rank(session.videoId) }
                    }
                val inPlaylists = playlists.flatMap { (item, _) ->
                    listOf(item.video.youtubeId, item.lastVideoId)
                }
                val single =
                    videos
                        .reversed()
                        .filter { it.youtubeId !in inPlaylists }
                        .map { TaContinueItem.Video(it) to rank(it.youtubeId) }

                _continueWatching.value =
                    (playlists + single).sortedBy { (_, rank) -> rank }.map { (item, _) -> item }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not load continue watching")
                // Without a connection, playlists can still go on with what's on the phone
                _continueWatching.value =
                    playlistSessions.sessions.value.take(MAX_PLAYLISTS).mapNotNull {
                        savedPlaylist(it)
                    }
            }
        }
    }

    /**
     * A playlist being watched through, from the copies saved on the phone. Only shown when the
     * video it's at is on the phone, as nothing else can be played without a connection.
     */
    private suspend fun savedPlaylist(session: TaPlaylistSession): TaContinueItem.Playlist? {
        val saved = playlistCache.loadPlaylist(session.playlistId) ?: return null
        val entries = saved.playlist.entries.filter { it.downloaded }
        val index = entries.indexOfFirst { it.youtubeId == session.videoId }
        if (index < 0) return null
        val video =
            withContext(Dispatchers.IO) { taOfflineStore.savedVideo(session.videoId) }
                ?: return null
        return TaContinueItem.Playlist(
            playlistId = saved.playlist.id,
            name = saved.playlist.name.ifBlank { session.playlistName },
            video = video,
            number = index + 1,
            count = entries.size,
            lastVideoId = session.videoId,
            saved = true,
        )
    }

    /**
     * The video a playlist is at, or the next one if that was watched. Forgets the playlist when
     * it's gone or was watched to the end.
     */
    private suspend fun resolvePlaylist(session: TaPlaylistSession): TaContinueItem.Playlist? =
        try {
            val playlist = taApi.getPlaylist(session.playlistId)
            val entries = playlist.entries.filter { it.downloaded }
            var index = entries.indexOfFirst { it.youtubeId == session.videoId }
            var video = entries.getOrNull(index)?.let { taApi.getVideo(it.youtubeId) }
            if (video?.player?.watched == true) {
                index++
                video = entries.getOrNull(index)?.let { taApi.getVideo(it.youtubeId) }
            }
            if (video == null) {
                playlistSessions.remove(session.playlistId)
                null
            } else {
                TaContinueItem.Playlist(
                    playlistId = playlist.id,
                    name = playlist.name.ifBlank { session.playlistName },
                    video = video,
                    number = index + 1,
                    count = entries.size,
                    lastVideoId = session.videoId,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not load playlist ${session.playlistId}")
            null
        }

    /** Starts the player at the playlist's video, with the rest of the playlist after it. */
    fun resumePlaylist(item: TaContinueItem.Playlist) {
        viewModelScope.launch {
            try {
                val video = item.video
                val episodeId =
                    withContext(Dispatchers.IO) { taOfflineStore.savedItemId(video.youtubeId) }
                        ?: taJellyfinLookup.findEpisodeId(
                            videoId = video.youtubeId,
                            title = video.title,
                            channelId = video.channel?.id,
                            channelName = video.channel?.name,
                        )
                if (episodeId == null) {
                    _messages.send(TaMessages.NOT_IN_JELLYFIN)
                    return@launch
                }
                _play.send(
                    TaPlayRequest(
                        episodeId = episodeId,
                        positionSeconds = if (item.saved) null else video.player?.position,
                        playlistId = item.playlistId,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            }
        }
    }

    companion object {
        private const val STALE_AFTER_MS = 60_000L
        private const val FEATURED_COUNT = 8
        private const val MAX_PLAYLISTS = 5
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

/** Something on Home's Continue watching row. */
sealed interface TaContinueItem {
    val key: String

    data class Video(val video: TaVideo) : TaContinueItem {
        override val key: String
            get() = "video:" + video.youtubeId
    }

    /**
     * A playlist being watched through, at [video], number [number] of its [count] videos.
     * [lastVideoId] is the video last played in it.
     */
    data class Playlist(
        val playlistId: String,
        val name: String,
        val video: TaVideo,
        val number: Int,
        val count: Int,
        val lastVideoId: String,
        /** From the copy on the phone, whose position is older than the phone's own. */
        val saved: Boolean = false,
    ) : TaContinueItem {
        override val key: String
            get() = "playlist:$playlistId"
    }
}

/** The playlist behind Home's Watch Later button. */
sealed interface TaWatchLater {
    /** Not looked up yet, or TubeArchivist couldn't be reached. */
    data object Unknown : TaWatchLater

    /** TubeArchivist has no playlist of its own called Watch Later. */
    data object Missing : TaWatchLater

    data class Found(val playlistId: String, val count: Int) : TaWatchLater {
        constructor(playlist: TaPlaylist) : this(playlist.id, playlist.entries.size)
    }
}

/** Play a video's Jellyfin episode from [positionSeconds], with its playlist after it. */
data class TaPlayRequest(val episodeId: UUID, val positionSeconds: Double?, val playlistId: String?)
