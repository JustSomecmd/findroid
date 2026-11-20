package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaChannel
import dev.jdtech.jellyfin.tubearchivist.TaChannelNav
import dev.jdtech.jellyfin.tubearchivist.TaChannelTotals
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalCanGoBack
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.player.PlayerRequest
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.ui.ConfirmDialog
import dev.jdtech.tubedroid.desktop.ui.DetailButton
import dev.jdtech.tubedroid.desktop.ui.DetailHeader
import dev.jdtech.tubedroid.desktop.ui.DetailMoreButton
import dev.jdtech.tubedroid.desktop.ui.GridScrollbar
import dev.jdtech.tubedroid.desktop.ui.HeaderTabs
import dev.jdtech.tubedroid.desktop.ui.InfoRow
import dev.jdtech.tubedroid.desktop.ui.ItemAction
import dev.jdtech.tubedroid.desktop.ui.ListFooter
import dev.jdtech.tubedroid.desktop.ui.LoadMoreEffect
import dev.jdtech.tubedroid.desktop.ui.ManageButton
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PagePadding
import dev.jdtech.tubedroid.desktop.ui.PageState
import dev.jdtech.tubedroid.desktop.ui.PagedList
import dev.jdtech.tubedroid.desktop.ui.PlaylistCard
import dev.jdtech.tubedroid.desktop.ui.RoundIconButton
import dev.jdtech.tubedroid.desktop.ui.SectionCard
import dev.jdtech.tubedroid.desktop.ui.ServerSideTheme
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.TaImage
import dev.jdtech.tubedroid.desktop.ui.VideoCard
import dev.jdtech.tubedroid.desktop.ui.VideoRow
import dev.jdtech.tubedroid.desktop.ui.anyDate
import dev.jdtech.tubedroid.desktop.ui.bleed
import dev.jdtech.tubedroid.desktop.ui.errorText
import dev.jdtech.tubedroid.desktop.ui.formatCount
import dev.jdtech.tubedroid.desktop.ui.formatNumber
import dev.jdtech.tubedroid.desktop.ui.formatPlaytime
import dev.jdtech.tubedroid.desktop.ui.formatSize
import dev.jdtech.tubedroid.desktop.ui.openInBrowser
import dev.jdtech.tubedroid.desktop.ui.rememberLinkedText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

enum class ChannelTab(val label: String, val videoType: String?) {
    VIDEOS("Videos", "videos"),
    SHORTS("Shorts", "shorts"),
    STREAMS("Streams", "streams"),
    PLAYLISTS("Playlists", null),
}

/** What can be done to videos on the server, as on TubeArchivist's web pages. */
enum class VideoAction(val label: String, val confirm: String) {
    REINDEX("Reindex", "Look the videos up on YouTube again and update their details?"),
    REDOWNLOAD("Redownload", "Download the videos again, replacing their files?"),
    DELETE("Delete", "Delete the videos and their files from the server? This can't be undone."),
}

/** Does [action] to the videos with these IDs. Returns the message to show afterwards. */
suspend fun TaApi.runVideoAction(action: VideoAction, ids: List<String>): String {
    when (action) {
        VideoAction.REINDEX -> refresh("video", ids)
        VideoAction.REDOWNLOAD -> addToQueue(ids, autostart = true, force = true)
        VideoAction.DELETE -> ids.forEach { deleteVideo(it) }
    }
    return when (action) {
        VideoAction.REINDEX -> "TubeArchivist is reindexing"
        VideoAction.REDOWNLOAD -> "Added to the top of the download queue"
        VideoAction.DELETE -> "Deleted"
    }
}

class ChannelModel(
    private val api: TaApi,
    private val scope: CoroutineScope,
    val channelId: String,
) {
    var channel by mutableStateOf<TaChannel?>(null)
        private set

    var nav by mutableStateOf(TaChannelNav(hasVideos = true))
        private set

    var totals by mutableStateOf<TaChannelTotals?>(null)
        private set

    var metricSizes by mutableStateOf(false)
        private set

    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var tab by mutableStateOf(ChannelTab.VIDEOS)
    var onServer by mutableStateOf(false)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var working by mutableStateOf(false)
        private set

    val tabs: List<ChannelTab>
        get() =
            listOfNotNull(
                ChannelTab.VIDEOS.takeIf { nav.hasVideos || !(nav.hasShorts || nav.hasStreams) },
                ChannelTab.SHORTS.takeIf { nav.hasShorts },
                ChannelTab.STREAMS.takeIf { nav.hasStreams },
                ChannelTab.PLAYLISTS.takeIf { nav.hasPlaylists },
            )

    private val videoLists = mutableMapOf<ChannelTab, PagedList<TaVideo>>()

    fun videos(tab: ChannelTab): PagedList<TaVideo> =
        videoLists.getOrPut(tab) {
            PagedList(scope, key = TaVideo::youtubeId) { page ->
                api.getVideos(page = page, channel = channelId, type = tab.videoType)
            }
        }

    val playlists =
        PagedList(scope, key = TaPlaylist::id) { page ->
            api.getPlaylists(page = page, channel = channelId)
        }

    /** Every video of the channel, for choosing some on the server side. */
    val serverVideos =
        PagedList(scope, key = TaVideo::youtubeId) { page ->
            api.getVideos(page = page, channel = channelId)
        }

    val grids = mutableMapOf<ChannelTab, LazyGridState>()
    val serverGrid = LazyGridState()

    init {
        load()
    }

    fun load() {
        loading = true
        error = null
        scope.launch {
            try {
                val channelCall = async { api.getChannel(channelId) }
                val navCall = async {
                    try {
                        api.getChannelNav(channelId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        TaChannelNav(hasVideos = true)
                    }
                }
                channel = channelCall.await()
                nav = navCall.await()
                if (tab !in tabs) tab = tabs.firstOrNull() ?: ChannelTab.VIDEOS
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
        loadTotals()
    }

    private fun loadTotals() {
        scope.launch {
            try {
                val metric = async { api.usesMetricSizes() }
                totals = api.getChannelTotals(channelId)
                metricSizes = metric.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {}
        }
    }

    fun setSubscribed(subscribed: Boolean, onMessage: (String) -> Unit) {
        scope.launch {
            try {
                api.setChannelSubscribed(channelId, subscribed)
                channel = channel?.copy(subscribed = subscribed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(errorText(e))
            }
        }
    }

    fun refreshChannel(withVideos: Boolean, onMessage: (String) -> Unit) {
        scope.launch {
            onMessage(
                try {
                    api.refresh("channel", channelId, withVideos)
                    "TubeArchivist is refreshing the channel"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorText(e)
                }
            )
        }
    }

    fun runOnSelected(action: VideoAction, onMessage: (String) -> Unit) {
        val ids = selected.toList()
        if (ids.isEmpty()) return
        working = true
        scope.launch {
            try {
                onMessage(api.runVideoAction(action, ids))
                selected = emptySet()
                if (action == VideoAction.DELETE) {
                    serverVideos.reload()
                    videoLists.values.forEach { it.reload() }
                    loadTotals()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(errorText(e))
            } finally {
                working = false
            }
        }
    }
}

@Composable
fun ChannelScreen(channelId: String) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model = rememberPageModel("channel") { scope -> ChannelModel(app.api, scope, channelId) }
    val canGoBack = LocalCanGoBack.current

    ServerSideTheme(onServer = model.onServer) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                PageBar(title = null, onBack = if (canGoBack) navigator::back else null) {
                    ManageButton(
                        on = model.onServer,
                        onClick = { model.onServer = !model.onServer },
                    )
                    OpenInWebButton("/channel/$channelId/")
                }
                val channel = model.channel
                if (channel == null) {
                    PageState(loading = model.loading, error = model.error, onRetry = model::load)
                } else {
                    AnimatedContent(
                        targetState = model.onServer,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "channel side",
                    ) { server ->
                        if (server) ChannelServerSide(model, channel)
                        else ChannelContent(model, channel)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelContent(model: ChannelModel, channel: TaChannel) {
    val navigator = LocalNavigator.current
    val tab = model.tab
    val state = model.grids.getOrPut(tab) { LazyGridState() }
    val videos = if (tab == ChannelTab.PLAYLISTS) null else model.videos(tab)
    LoadMoreEffect(state) { videos?.loadMore() ?: model.playlists.loadMore() }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Adaptive(minSize = VIDEO_CARD_MIN_WIDTH),
            contentPadding =
                PaddingValues(start = PagePadding, end = PagePadding, bottom = Spacings.large),
            horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
            verticalArrangement = Arrangement.spacedBy(Spacings.small),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                ChannelHeader(model, channel)
            }
            if (videos != null) {
                items(videos.items, key = { it.youtubeId }) { video ->
                    VideoCard(
                        video = video,
                        showChannel = false,
                        onClick = { navigator.open(Route.Video(video.youtubeId)) },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                    ListFooter(videos, emptyText = "No videos")
                }
            } else {
                items(model.playlists.items, key = { it.id }) { playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        onClick = { navigator.open(Route.Playlist(playlist.id)) },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                    ListFooter(model.playlists, emptyText = "No playlists")
                }
            }
        }
        GridScrollbar(state)
    }
}

/**
 * The channel as Jellyfin shows a show: its banner across the top, its picture, name, numbers and
 * round buttons, its description, then the tabs of what it has.
 */
@Composable
private fun ChannelHeader(model: ChannelModel, channel: TaChannel) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    var descriptionExpanded by remember { mutableStateOf(false) }
    val totals = model.totals
    Column {
        DetailHeader(
            title = channel.name,
            info =
                listOfNotNull(
                    channel.subs?.let { "${formatCount(it)} subscribers" },
                    totals?.let { "${formatNumber(it.count)} videos" },
                    totals?.let { formatPlaytime(it.duration) },
                    totals?.let { formatSize(it.mediaSize, model.metricSizes) },
                ),
            backdrop = {
                if (channel.bannerUrl != null) {
                    TaImage(path = channel.bannerUrl, modifier = Modifier.fillMaxSize())
                } else {
                    TaImage(path = channel.thumbUrl, modifier = Modifier.fillMaxSize().blur(24.dp))
                }
            },
            poster = {
                TaImage(
                    path = channel.thumbUrl,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
            },
            posterWidth = 220.dp,
            buttons = {
                val newest = model.videos(ChannelTab.VIDEOS).items.firstOrNull()
                DetailButton(
                    icon = "ic_play",
                    label = newest?.let { "Play the newest: ${it.title}" } ?: "Play the newest",
                    onClick = {
                        newest?.let { navigator.play(PlayerRequest.Ta(videoId = it.youtubeId)) }
                    },
                    main = true,
                    enabled = newest != null,
                )
                DetailButton(
                    icon = if (channel.subscribed) "ic_heart_filled" else "ic_heart",
                    label = if (channel.subscribed) "Subscribed: unsubscribe" else "Subscribe",
                    onClick = { model.setSubscribed(!channel.subscribed, navigator::message) },
                    on = channel.subscribed,
                )
                DetailMoreButton(
                    actions =
                        listOf(
                            ItemAction("Manage on the server") { model.onServer = true },
                            ItemAction("Open in TubeArchivist") {
                                app.api.mediaUrl("/channel/${channel.id}/")?.let(::openInBrowser)
                            },
                        )
                )
            },
            details = {
                channel.description
                    ?.takeIf { it.isNotBlank() }
                    ?.let { description ->
                        Text(
                            text = rememberLinkedText(description),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = if (descriptionExpanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier.widthIn(max = 900.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { descriptionExpanded = !descriptionExpanded },
                        )
                    }
            },
            modifier = Modifier.bleed(PagePadding),
        )
        HeaderTabs(
            tabs = model.tabs.map { it.label },
            selected = model.tabs.indexOf(model.tab).coerceAtLeast(0),
            onSelect = { model.tab = model.tabs[it] },
            modifier =
                Modifier.padding(top = Spacings.large, bottom = Spacings.small)
                    .offset(x = (-14).dp),
        )
    }
}

/** The channel's side on the server: its details, refreshing it, and actions on its videos. */
@Composable
private fun ChannelServerSide(model: ChannelModel, channel: TaChannel) {
    val navigator = LocalNavigator.current
    var confirm by remember { mutableStateOf<VideoAction?>(null) }
    val list = model.serverVideos
    LoadMoreEffect(model.serverGrid) { list.loadMore() }
    LaunchedEffect(Unit) { if (list.items.isEmpty()) list.loadMore() }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = model.serverGrid,
            columns = GridCells.Adaptive(minSize = 520.dp),
            contentPadding =
                PaddingValues(
                    start = Spacings.default,
                    end = Spacings.default,
                    bottom = Spacings.default,
                ),
            horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
            verticalArrangement = Arrangement.spacedBy(Spacings.small),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                ChannelHeader(model, channel)
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "details") {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacings.medium)) {
                    SectionCard("Details", modifier = Modifier.weight(1f)) {
                        InfoRow("Channel ID", channel.id)
                        InfoRow("Subscribed", if (channel.subscribed) "Yes" else "No")
                        anyDate(channel.lastRefresh)?.let { InfoRow("Last refreshed", it) }
                        channel.active?.let {
                            InfoRow("On YouTube", if (it) "Yes" else "No, removed")
                        }
                        model.totals?.let { totals ->
                            InfoRow("Videos", formatNumber(totals.count))
                            InfoRow("Playing time", formatPlaytime(totals.duration))
                            InfoRow("Storage used", formatSize(totals.mediaSize, model.metricSizes))
                        }
                    }
                    SectionCard("Refresh", modifier = Modifier.weight(1f)) {
                        Text(
                            text =
                                "Looks the channel up on YouTube again. With videos, every video's details are updated too, which takes longer.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(Spacings.medium))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacings.small)) {
                            FilledTonalButton(
                                onClick = { model.refreshChannel(false, navigator::message) }
                            ) {
                                Text("Refresh channel")
                            }
                            OutlinedButton(
                                onClick = { model.refreshChannel(true, navigator::message) }
                            ) {
                                Text("Refresh with videos")
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "actions") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacings.small),
                    modifier = Modifier.padding(top = Spacings.medium),
                ) {
                    Text(
                        text =
                            if (model.selected.isEmpty())
                                "Choose videos to reindex, redownload or delete"
                            else "${model.selected.size} chosen",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    VideoAction.entries.forEach { action ->
                        OutlinedButton(
                            onClick = { confirm = action },
                            enabled = model.selected.isNotEmpty() && !model.working,
                        ) {
                            Text(action.label)
                        }
                    }
                }
            }
            items(list.items, key = { it.youtubeId }) { video ->
                val checked = video.youtubeId in model.selected
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = {
                            model.selected =
                                if (it) model.selected + video.youtubeId
                                else model.selected - video.youtubeId
                        },
                    )
                    VideoRow(
                        video = video,
                        onClick = {
                            model.selected =
                                if (checked) model.selected - video.youtubeId
                                else model.selected + video.youtubeId
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                ListFooter(list, emptyText = "No videos")
            }
        }
        GridScrollbar(model.serverGrid)
    }

    confirm?.let { action ->
        ConfirmDialog(
            title = "${action.label} ${model.selected.size} videos?",
            text = action.confirm,
            confirmLabel = action.label,
            onConfirm = { model.runOnSelected(action, navigator::message) },
            onDismiss = { confirm = null },
        )
    }
}

/** A banner as wide as the page, cutting a little off the ends of YouTube's strip. */

/** A top bar button that opens the page in TubeArchivist's web interface. */
@Composable
fun OpenInWebButton(path: String) {
    val api = LocalApp.current.api
    RoundIconButton(
        icon = "ic_external_link",
        label = "Open in TubeArchivist",
        onClick = { api.mediaUrl(path)?.let(::openInBrowser) },
    )
}
