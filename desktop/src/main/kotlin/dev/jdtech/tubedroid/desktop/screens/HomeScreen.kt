package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.tubearchivist.TA_WATCH_LATER
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaChannel
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.ConfirmDialog
import dev.jdtech.tubedroid.desktop.ui.GridScrollbar
import dev.jdtech.tubedroid.desktop.ui.ListFooter
import dev.jdtech.tubedroid.desktop.ui.LoadMoreEffect
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PagedList
import dev.jdtech.tubedroid.desktop.ui.SectionTitle
import dev.jdtech.tubedroid.desktop.ui.ServerSideTheme
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.TaImage
import dev.jdtech.tubedroid.desktop.ui.VideoCard
import dev.jdtech.tubedroid.desktop.ui.errorText
import dev.jdtech.tubedroid.desktop.ui.formatCount
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What Home shows: channels for the carousel, videos to carry on with, and the latest videos. */
class HomeModel(private val api: TaApi, private val scope: CoroutineScope) {
    var featured by mutableStateOf<List<TaChannel>>(emptyList())
        private set

    var continueWatching by mutableStateOf<List<TaVideo>>(emptyList())
        private set

    val latest =
        PagedList(scope, key = TaVideo::youtubeId) { page ->
            api.getVideos(page = page, sort = "downloaded", order = "desc")
        }

    val gridState = LazyGridState()

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
        scope.launch {
            try {
                continueWatching = api.getVideos(watch = "continue").data.reversed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {}
        }
    }

    companion object {
        private const val FEATURED_COUNT = 10
    }
}

@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model = rememberPageModel("home") { scope -> HomeModel(app.api, scope) }
    // Videos may have been saved for later since Home was last shown
    LaunchedEffect(Unit) { model.loadWatchLater() }
    val openPlaylist: (String) -> Unit = { navigator.open(Route.Playlist(it)) }
    Column(modifier = Modifier.fillMaxSize()) {
        PageBar(title = "Home", onBack = null) {
            WatchLaterButton(
                playlist = model.watchLater,
                busy = model.watchLaterBusy,
                onClick = { model.openWatchLater(openPlaylist, navigator::message) },
            )
            RefreshButton(onClick = model::refresh)
        }
        HomeContent(
            featured = model.featured,
            continueWatching = model.continueWatching,
            latest = model.latest,
            gridState = model.gridState,
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

/**
 * Opens the Watch Later playlist, with how many videos are in it once that's known. A spinner takes
 * the place of the clock while it's being looked up or made.
 */
@Composable
private fun WatchLaterButton(playlist: TaPlaylist?, busy: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) {
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
            } else {
                AppIcon(
                    name = "ic_clock",
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(Spacings.small))
        Text(TA_WATCH_LATER)
        if (playlist != null) {
            val count = playlist.entries.size
            Text(
                text = "  ·  $count ${if (count == 1) "video" else "videos"}",
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
        }
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

@Composable
fun HomeContent(
    featured: List<TaChannel>,
    continueWatching: List<TaVideo>,
    latest: PagedList<TaVideo>,
    gridState: LazyGridState,
) {
    val navigator = LocalNavigator.current
    LoadMoreEffect(gridState) { latest.loadMore() }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = VIDEO_CARD_MIN_WIDTH),
            contentPadding = PaddingValues(Spacings.default),
            horizontalArrangement = Arrangement.spacedBy(Spacings.medium),
            verticalArrangement = Arrangement.spacedBy(Spacings.small),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Always there, even while empty, so the page opens at the top once the carousel
            // and the videos to carry on with arrive after the latest videos
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column {
                    if (featured.isNotEmpty()) {
                        ChannelCarousel(
                            channels = featured,
                            onChannelClick = { navigator.open(Route.Channel(it)) },
                            modifier = Modifier.padding(bottom = Spacings.small),
                        )
                    }
                    if (continueWatching.isNotEmpty()) {
                        SectionTitle("Continue watching")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacings.medium)) {
                            items(continueWatching, key = { it.youtubeId }) { video ->
                                VideoCard(
                                    video = video,
                                    onClick = { navigator.open(Route.Video(video.youtubeId)) },
                                    modifier = Modifier.width(300.dp),
                                )
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "latest_title") {
                SectionTitle("Latest videos")
            }
            items(latest.items, key = { it.youtubeId }) { video ->
                VideoCard(video = video, onClick = { navigator.open(Route.Video(video.youtubeId)) })
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                ListFooter(latest, emptyText = "No videos yet")
            }
        }
        GridScrollbar(gridState)
    }
}

/** How wide video cards are at least, so wider windows fit more side by side. */
val VIDEO_CARD_MIN_WIDTH = 260.dp

/** How many carousel pages sit side by side: one in a narrow window, up to three in a wide one. */
fun carouselPages(width: Dp): Int =
    when {
        width >= 840.dp -> 3
        width >= 600.dp -> 2
        else -> 1
    }

private val carouselPageSize =
    object : PageSize {
        override fun Density.calculateMainAxisPageSize(availableSpace: Int, pageSpacing: Int): Int {
            val pages = carouselPages(availableSpace.toDp())
            return (availableSpace - (pages - 1) * pageSpacing) / pages
        }
    }

/**
 * Channels picked at random, turning to the next ones every few seconds, with their banners as the
 * backdrop. The arrows at the ends turn it by hand.
 */
@Composable
fun ChannelCarousel(
    channels: List<TaChannel>,
    onChannelClick: (channelId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { channels.size })
    val scope = rememberCoroutineScope()
    LaunchedEffect(pagerState) {
        while (true) {
            delay(AUTO_SCROLL_DELAY_MS)
            val next = if (pagerState.canScrollForward) pagerState.currentPage + 1 else 0
            pagerState.animateScrollToPage(next)
        }
    }
    Box(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            pageSize = carouselPageSize,
            pageSpacing = Spacings.medium,
            key = { page -> channels[page].id },
        ) { page ->
            val channel = channels[page]
            ChannelCarouselItem(channel = channel, onClick = { onChannelClick(channel.id) })
        }
        CarouselArrow(
            icon = "ic_arrow_left",
            onClick = {
                scope.launch {
                    pagerState.animateScrollToPage(
                        if (pagerState.canScrollBackward) pagerState.currentPage - 1
                        else channels.lastIndex
                    )
                }
            },
            modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacings.small),
        )
        CarouselArrow(
            icon = "ic_arrow_right",
            onClick = {
                scope.launch {
                    pagerState.animateScrollToPage(
                        if (pagerState.canScrollForward) pagerState.currentPage + 1 else 0
                    )
                }
            },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = Spacings.small),
        )
    }
}

@Composable
private fun CarouselArrow(icon: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f),
        modifier = modifier.clip(CircleShape).clickable(onClick = onClick),
    ) {
        AppIcon(
            name = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.padding(Spacings.small).size(20.dp),
        )
    }
}

@Composable
private fun ChannelCarouselItem(channel: TaChannel, onClick: () -> Unit) {
    val colorStops =
        arrayOf(
            0.0f to Color.Black.copy(alpha = 0.1f),
            0.5f to Color.Black.copy(alpha = 0.5f),
            1f to Color.Black.copy(alpha = 0.6f),
        )
    Box(
        modifier =
            Modifier.aspectRatio(1.77f)
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onClick)
    ) {
        // The full banner as uploaded, which fits this shape, or else the strip shown on YouTube
        TaImage(path = channel.tvArtUrl ?: channel.bannerUrl, modifier = Modifier.fillMaxSize())
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = Brush.verticalGradient(colorStops = colorStops))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacings.small),
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = Spacings.default, vertical = Spacings.medium)
                    .align(Alignment.BottomStart),
        ) {
            channel.subs?.let { subs ->
                Text(
                    text = "${formatCount(subs)} subscribers",
                    color = Color.LightGray,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = channel.name,
                color = Color.White,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

/** A round button with the refresh icon, for the top bar. */
@Composable
fun RefreshButton(onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
    ) {
        AppIcon(
            name = "ic_refresh",
            contentDescription = "Refresh",
            modifier = Modifier.padding(Spacings.small).size(20.dp),
        )
    }
}

private const val AUTO_SCROLL_DELAY_MS = 6000L
