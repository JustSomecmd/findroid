package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.tubearchivist.TA_WATCH_LATER
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaChannel
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaPlaylistSession
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.Section
import dev.jdtech.tubedroid.desktop.WatchHistory
import dev.jdtech.tubedroid.desktop.player.PlayerRequest
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.ChannelItem
import dev.jdtech.tubedroid.desktop.ui.ConfirmDialog
import dev.jdtech.tubedroid.desktop.ui.ItemAction
import dev.jdtech.tubedroid.desktop.ui.LibraryTile
import dev.jdtech.tubedroid.desktop.ui.ListFooter
import dev.jdtech.tubedroid.desktop.ui.ListScrollbar
import dev.jdtech.tubedroid.desktop.ui.MediaCard
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PagedList
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.SectionRow
import dev.jdtech.tubedroid.desktop.ui.ServerSideTheme
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.VideoCard
import dev.jdtech.tubedroid.desktop.ui.VideoThumbnailContent
import dev.jdtech.tubedroid.desktop.ui.errorText
import dev.jdtech.tubedroid.desktop.ui.formatCount
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What Home shows: channels for the carousel, videos and playlists to carry on with, and the latest
 * videos.
 */
class HomeModel(
    private val api: TaApi,
    private val history: WatchHistory,
    private val scope: CoroutineScope,
) {
    var featured by mutableStateOf<List<TaChannel>>(emptyList())
        private set

    /** Videos part way through, and playlists being watched through, most recent first. */
    var continueWatching by mutableStateOf<List<ContinueItem>>(emptyList())
        private set

    private var shown = false

    val latest =
        PagedList(scope, key = TaVideo::youtubeId) { page ->
            api.getVideos(page = page, sort = "downloaded", order = "desc")
        }

    val listState = LazyListState()

    /** The playlist behind the Watch Later button, once it's been found. */
    var watchLater by mutableStateOf<TaPlaylist?>(null)
        private set

    /** True while Watch Later is being looked up or made after a click. */
    var watchLaterBusy by mutableStateOf(false)
        private set

    /** True when Watch Later was clicked but there's no such playlist, to offer to make it. */
    var offerWatchLater by mutableStateOf(false)

    init {
        refresh()
    }

    /** Looks up Watch Later for the button, which shows how many videos are in it. */
    fun loadWatchLater() {
        scope.launch {
            try {
                watchLater = api.findCustomPlaylist(TA_WATCH_LATER)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Looked up again when the button is clicked
            }
        }
    }

    /**
     * Opens Watch Later. When it isn't known yet it's looked up first, and when there's no such
     * playlist the page offers to make it.
     */
    fun openWatchLater(onOpen: (playlistId: String) -> Unit, onError: (String) -> Unit) {
        watchLater?.let {
            onOpen(it.id)
            return
        }
        watchLaterAction(onError) {
            val found = api.findCustomPlaylist(TA_WATCH_LATER)
            watchLater = found
            if (found != null) {
                onOpen(found.id)
            } else {
                offerWatchLater = true
            }
        }
    }

    /** Makes the Watch Later playlist and opens it. */
    fun createWatchLater(onOpen: (playlistId: String) -> Unit, onError: (String) -> Unit) {
        offerWatchLater = false
        watchLaterAction(onError) {
            // One may have been made somewhere else since the button was clicked
            val playlist =
                api.findCustomPlaylist(TA_WATCH_LATER) ?: api.createCustomPlaylist(TA_WATCH_LATER)
            watchLater = playlist
            onOpen(playlist.id)
        }
    }

    private fun watchLaterAction(onError: (String) -> Unit, action: suspend () -> Unit) {
        if (watchLaterBusy) return
        watchLaterBusy = true
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(errorText(e))
            } finally {
                watchLaterBusy = false
            }
        }
    }

    fun refresh() {
        latest.reload()
        loadWatchLater()
        scope.launch {
            try {
                val first = api.getChannels(page = 1)
                val lastPage = first.paginate?.lastPage ?: 1
                val other =
                    if (lastPage > 1) api.getChannels(page = (2..lastPage).random()).data
                    else emptyList()
                featured =
                    (first.data + other)
                        .distinctBy { it.id }
                        .filter { it.tvArtUrl != null || it.bannerUrl != null }
                        .shuffled()
                        .take(FEATURED_COUNT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The carousel is left out when TubeArchivist can't be reached
            }
        }
        loadContinueWatching()
    }

    /**
     * Catches up with what changed while Home was away, such as where videos and playlists were
     * left off. The player saves its last position as it closes, so this waits a moment for that.
     */
    fun onShown() {
        if (!shown) {
            shown = true
            return
        }
        loadWatchLater()
        scope.launch {
            delay(RETURN_DELAY_MS)
            loadContinueWatching()
        }
    }

    private fun loadContinueWatching() {
        scope.launch {
            try {
                val videos = api.getVideos(watch = "continue").data
                // Playlists watched on the phone count too
                history.sync()
                // A playlist takes the place of its video. Each item is ranked by when it was last
                // played here, with videos played elsewhere after, in TubeArchivist's order
                val playlists =
                    history.playlists.take(MAX_PLAYLISTS).mapNotNull { session ->
                        resolvePlaylist(session)?.let { it to session.updatedAt }
                    }
                val inPlaylists = playlists.flatMap { (item, _) ->
                    listOf(item.video.youtubeId, item.lastVideoId)
                }
                val single =
                    videos
                        .reversed()
                        .filter { it.youtubeId !in inPlaylists }
                        .map { ContinueItem.Video(it) to (history.lastPlayed(it.youtubeId) ?: 0L) }
                continueWatching =
                    (playlists + single).sortedByDescending { (_, time) -> time }.map { it.first }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {}
        }
    }

    /**
     * The video a playlist is at, or the next one if that was watched. Forgets the playlist when it
     * was watched to the end or the video isn't in it anymore.
     */
    private suspend fun resolvePlaylist(session: TaPlaylistSession): ContinueItem.Playlist? =
        try {
            val playlist = api.getPlaylist(session.playlistId)
            val entries = playlist.entries.filter { it.downloaded }
            var index = entries.indexOfFirst { it.youtubeId == session.videoId }
            var video = entries.getOrNull(index)?.let { api.getVideo(it.youtubeId) }
            if (video?.player?.watched == true) {
                index++
                video = entries.getOrNull(index)?.let { api.getVideo(it.youtubeId) }
            }
            if (video == null) {
                history.removePlaylist(session.playlistId)
                null
            } else {
                ContinueItem.Playlist(
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
            null
        }

    companion object {
        private const val FEATURED_COUNT = 10
        private const val MAX_PLAYLISTS = 5
        private const val RETURN_DELAY_MS = 1_000L
    }
}

/** Something on Home's Continue watching row. */
sealed interface ContinueItem {
    val key: String

    data class Video(val video: TaVideo) : ContinueItem {
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
    ) : ContinueItem {
        override val key: String
            get() = "playlist:$playlistId"
    }
}

@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model = rememberPageModel("home") { scope -> HomeModel(app.api, app.history, scope) }
    LaunchedEffect(Unit) { model.onShown() }
    val openPlaylist: (String) -> Unit = { navigator.open(Route.Playlist(it)) }
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = "Home", onBack = null) { RefreshButton(onClick = model::refresh) }
        HomeContent(
            model = model,
            onWatchLater = { model.openWatchLater(openPlaylist, navigator::message) },
        )
    }

    if (model.offerWatchLater) {
        ConfirmDialog(
            title = "No Watch Later playlist",
            text =
                "TubeArchivist doesn't have a playlist called $TA_WATCH_LATER yet. " +
                    "Do you want to make it now?",
            confirmLabel = "Make it",
            onConfirm = { model.createWatchLater(openPlaylist, navigator::message) },
            onDismiss = { model.offerWatchLater = false },
        )
    }
}

/** TubeArchivist's numbers: what's archived, how much was watched, downloads and channels. */
class StatsPageModel(private val api: TaApi, scope: CoroutineScope) {
    val stats = StatsModel(api, scope)
    var version by mutableStateOf<String?>(null)
        private set

    init {
        scope.launch {
            try {
                version = api.ping(api.baseUrl.orEmpty(), api.token.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {}
        }
    }
}

/** The server's stats, in the server's colors as all of the server's pages. */
@Composable
fun StatsScreen() {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model = rememberPageModel("stats") { scope -> StatsPageModel(app.api, scope) }
    LaunchedEffect(Unit) { model.stats.onShown() }
    ServerSideTheme(onServer = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                PageBar(title = "Stats", onBack = null) {
                    RefreshButton(onClick = model.stats::refresh)
                }
                TaHomeStatsPage(
                    stats = model.stats.stats,
                    version = model.version,
                    onRefresh = model.stats::refresh,
                    onChannelClick = { navigator.open(Route.Channel(it)) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Home as Jellyfin has it: rows that scroll sideways. My media first, with the libraries and Watch
 * Later, then what to carry on with, the latest videos and the channels.
 */
@Composable
fun HomeContent(model: HomeModel, onWatchLater: () -> Unit) {
    val navigator = LocalNavigator.current
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = model.listState,
            verticalArrangement = Arrangement.spacedBy(28.dp),
            contentPadding = PaddingValues(top = Spacings.small, bottom = Spacings.large),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "media") {
                SectionRow("My media") {
                    item {
                        LibraryTile(
                            name = "Videos",
                            icon = "ic_film",
                            detail =
                                model.latest.total?.let { "${formatCount(it.toLong())} videos" },
                            onClick = { navigator.select(Section.VIDEOS) },
                            modifier = Modifier.width(TILE_WIDTH),
                        )
                    }
                    item {
                        LibraryTile(
                            name = "Channels",
                            icon = "ic_users",
                            detail = null,
                            onClick = { navigator.select(Section.CHANNELS) },
                            modifier = Modifier.width(TILE_WIDTH),
                        )
                    }
                    item {
                        LibraryTile(
                            name = "Playlists",
                            icon = "ic_list_video",
                            detail = null,
                            onClick = { navigator.select(Section.PLAYLISTS) },
                            modifier = Modifier.width(TILE_WIDTH),
                        )
                    }
                    item {
                        LibraryTile(
                            name = TA_WATCH_LATER,
                            icon = "ic_clock",
                            detail =
                                when {
                                    model.watchLaterBusy -> "Looking for it..."
                                    else ->
                                        model.watchLater?.entries?.size?.let {
                                            "$it ${if (it == 1) "video" else "videos"}"
                                        }
                                },
                            onClick = onWatchLater,
                            modifier = Modifier.width(TILE_WIDTH),
                        )
                    }
                    item {
                        LibraryTile(
                            name = "On this PC",
                            icon = "ic_download",
                            detail = null,
                            onClick = { navigator.select(Section.DOWNLOADS) },
                            modifier = Modifier.width(TILE_WIDTH),
                        )
                    }
                }
            }
            if (model.continueWatching.isNotEmpty()) {
                item(key = "continue") {
                    SectionRow("Continue watching") {
                        items(model.continueWatching, key = { it.key }) { item ->
                            when (item) {
                                is ContinueItem.Video ->
                                    VideoCard(
                                        video = item.video,
                                        onClick = {
                                            navigator.open(Route.Video(item.video.youtubeId))
                                        },
                                        modifier = Modifier.width(ROW_CARD_WIDTH),
                                    )
                                is ContinueItem.Playlist ->
                                    PlaylistResumeCard(
                                        item = item,
                                        onPlay = {
                                            navigator.play(
                                                PlayerRequest.Ta(
                                                    videoId = item.video.youtubeId,
                                                    playlistId = item.playlistId,
                                                )
                                            )
                                        },
                                        onOpenPlaylist = {
                                            navigator.open(Route.Playlist(item.playlistId))
                                        },
                                        modifier = Modifier.width(ROW_CARD_WIDTH),
                                    )
                            }
                        }
                    }
                }
            }
            item(key = "latest") {
                SectionRow(
                    "Latest videos",
                    onTitleClick = { navigator.select(Section.VIDEOS) },
                ) {
                    items(model.latest.items.take(LATEST_IN_ROW), key = { it.youtubeId }) { video ->
                        VideoCard(
                            video = video,
                            onClick = { navigator.open(Route.Video(video.youtubeId)) },
                            modifier = Modifier.width(ROW_CARD_WIDTH),
                        )
                    }
                }
                if (model.latest.items.isEmpty())
                    ListFooter(model.latest, emptyText = "No videos yet")
            }
            if (model.featured.isNotEmpty()) {
                item(key = "channels") {
                    SectionRow(
                        "Channels",
                        onTitleClick = { navigator.select(Section.CHANNELS) },
                    ) {
                        items(model.featured, key = { it.id }) { channel ->
                            ChannelItem(
                                channel = channel,
                                onClick = { navigator.open(Route.Channel(channel.id)) },
                                modifier = Modifier.width(CHANNEL_CARD_WIDTH),
                            )
                        }
                    }
                }
            }
        }
        ListScrollbar(model.listState)
    }
}

private val TILE_WIDTH = 260.dp
val ROW_CARD_WIDTH = 300.dp
val CHANNEL_CARD_WIDTH = 170.dp
private const val LATEST_IN_ROW = 24

/**
 * A playlist being watched through, on Continue watching: the video it's at, with the playlist's
 * name and how far into it that is. Clicking opens the playlist; its play button carries on from
 * there.
 */
@Composable
private fun PlaylistResumeCard(
    item: ContinueItem.Playlist,
    onPlay: () -> Unit,
    onOpenPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MediaCard(
        title = item.name,
        subtitle = item.video.title,
        onClick = onOpenPlaylist,
        onPlay = onPlay,
        actions =
            listOf(ItemAction("Carry on", onPlay), ItemAction("Open playlist", onOpenPlaylist)),
        modifier = modifier,
    ) {
        VideoThumbnailContent(item.video)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.align(Alignment.TopStart)
                    .padding(Spacings.small)
                    .background(Color.Black.copy(alpha = 0.75f), MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            AppIcon(
                name = "ic_list_video",
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "${item.number} of ${item.count}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

/** How wide video cards are at least, so wider windows fit more side by side. */
val VIDEO_CARD_MIN_WIDTH = 260.dp

/** A round button with the refresh icon, for the header. */
@Composable
fun RefreshButton(onClick: () -> Unit) {
    RoundIconButton(icon = "ic_refresh", label = "Refresh", onClick = onClick)
}
