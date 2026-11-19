package dev.jdtech.jellyfin.presentation.tube

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.tube.TaContinueItem
import dev.jdtech.jellyfin.film.presentation.tube.TaHomeViewModel
import dev.jdtech.jellyfin.film.presentation.tube.TaServersInfo
import dev.jdtech.jellyfin.film.presentation.tube.TaStatsViewModel
import dev.jdtech.jellyfin.presentation.film.components.HomeHeader
import dev.jdtech.jellyfin.presentation.film.components.ServerSelectionBottomSheet
import dev.jdtech.jellyfin.presentation.theme.ServerSideTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.utils.ObserveAsEvents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaHomeScreen(
    onVideoClick: (videoId: String) -> Unit,
    onChannelClick: (channelId: String) -> Unit,
    onPlaylistClick: (playlistId: String) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onManageServers: () -> Unit,
    onTubeArchivistSettings: () -> Unit,
    /** Home and the server's stats, when the main pager needs to turn them. */
    sides: PagerState = rememberSidePagerState(),
    viewModel: TaHomeViewModel = hiltViewModel(),
    statsViewModel: TaStatsViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    var showServers by rememberSaveable { mutableStateOf(false) }
    var showJellyfinServers by rememberSaveable { mutableStateOf(false) }
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Carrying on with a playlist starts the player straight away
    ObserveAsEvents(viewModel.play) { request ->
        startTaPlayback(
            context = context,
            episodeId = request.episodeId,
            startFromBeginning = false,
            positionSeconds = request.positionSeconds,
            playlistId = request.playlistId,
        )
    }
    ObserveAsEvents(viewModel.messages) { message ->
        Toast.makeText(context, taMessageText(context, message), Toast.LENGTH_LONG).show()
    }
    val latest by viewModel.latest.collectAsStateWithLifecycle()
    val featured by viewModel.featured.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.onShown()
        onPauseOrDispose {}
    }

    val gridState = rememberLazyGridState()
    LoadMoreEffect(gridState) { viewModel.loadMore() }

    // The stats load as soon as a swipe starts towards them
    val scope = rememberCoroutineScope()
    val stats by statsViewModel.stats.collectAsStateWithLifecycle()
    LaunchedEffect(sides) {
        snapshotFlow { sides.currentPage + sides.currentPageOffsetFraction > 0.01f }
            .collect { if (it) statsViewModel.onShown() }
    }

    ServerSideTheme(pagerState = sides) {
        Scaffold(
            modifier = Modifier.fillMaxSize().recalculateWindowInsetsUnlessPaged(),
            topBar = {
                // Findroid's own header with TubeArchivist's logo, its background always shown
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    HomeHeader(
                        serverName =
                            servers.jellyfinName ?: stringResource(CoreR.string.ta_tubearchivist),
                        isLoading = servers.taVersion == null && servers.taError == null,
                        isError = servers.taError != null,
                        onServerClick = { showServers = true },
                        onErrorClick = { showServers = true },
                        onRetryClick = {
                            viewModel.loadServers()
                            viewModel.refresh()
                        },
                        onSearchClick = onSearchClick,
                        onUserClick = onSettingsClick,
                        logo = { TaLogo(modifier = Modifier.size(32.dp)) },
                        actions = {
                            TaSideSwitch(
                                onServer = sides.onServerSide,
                                onToggle = { sides.switchSide(scope) },
                                localIcon = CoreR.drawable.ic_home,
                                localLabel = stringResource(CoreR.string.ta_side_home),
                                showLabel = false,
                            )
                        },
                        modifier =
                            Modifier.windowInsetsPadding(
                                    WindowInsets.statusBars.union(WindowInsets.displayCutout)
                                )
                                .padding(
                                    horizontal = MaterialTheme.spacings.medium,
                                    vertical = MaterialTheme.spacings.small,
                                ),
                    )
                }
            },
            contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
        ) { innerPadding ->
            TaSidePager(
                state = sides,
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                phone = {
                    PullToRefreshBox(
                        isRefreshing = false,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = TA_CARD_MIN_WIDTH),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(MaterialTheme.spacings.medium),
                            horizontalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.medium),
                            verticalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.small),
                        ) {
                            // Keeps the page at the top when continue watching loads after the rest
                            item(span = { GridItemSpan(maxLineSpan) }, key = "top") {
                                Spacer(Modifier)
                            }
                            if (featured.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "featured") {
                                    TaChannelCarousel(
                                        channels = featured,
                                        onChannelClick = onChannelClick,
                                        modifier =
                                            Modifier.padding(bottom = MaterialTheme.spacings.small),
                                    )
                                }
                            }
                            if (continueWatching.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                                    Column {
                                        TaSectionTitle(
                                            stringResource(CoreR.string.ta_continue_watching)
                                        )
                                        LazyRow(
                                            horizontalArrangement =
                                                Arrangement.spacedBy(MaterialTheme.spacings.medium)
                                        ) {
                                            items(continueWatching, key = { it.key }) { item ->
                                                when (item) {
                                                    is TaContinueItem.Video ->
                                                        TaVideoSmallCard(
                                                            video = item.video,
                                                            onClick = {
                                                                onVideoClick(item.video.youtubeId)
                                                            },
                                                        )
                                                    is TaContinueItem.Playlist ->
                                                        TaPlaylistResumeCard(
                                                            item = item,
                                                            onClick = {
                                                                viewModel.resumePlaylist(item)
                                                            },
                                                            onLongClick = {
                                                                onPlaylistClick(item.playlistId)
                                                            },
                                                        )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            item(span = { GridItemSpan(maxLineSpan) }, key = "latest_title") {
                                TaSectionTitle(stringResource(CoreR.string.ta_latest_videos))
                            }
                            items(latest.items, key = { it.youtubeId }) { video ->
                                TaVideoCard(
                                    video = video,
                                    onClick = { onVideoClick(video.youtubeId) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                            item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                                TaListFooter(
                                    state = latest,
                                    onRetry = { viewModel.loadMore() },
                                    emptyText = stringResource(CoreR.string.ta_no_videos),
                                )
                            }
                        }
                    }
                },
                server = {
                    TaHomeStatsPage(
                        stats = stats,
                        version = servers.taVersion,
                        onRefresh = { statsViewModel.refresh() },
                        onChannelClick = onChannelClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
        }
    }

    if (showServers) {
        TaServersSheet(
            servers = servers,
            onChangeJellyfinServer = {
                showServers = false
                showJellyfinServers = true
            },
            onTubeArchivistSettings = {
                showServers = false
                onTubeArchivistSettings()
            },
            onDismiss = { showServers = false },
        )
    }

    if (showJellyfinServers) {
        ServerSelectionBottomSheet(
            currentServerId = servers.jellyfinServerId.orEmpty(),
            onUpdate = {
                showJellyfinServers = false
                viewModel.loadServers()
            },
            onManage = {
                showJellyfinServers = false
                onManageServers()
            },
            onDismissRequest = { showJellyfinServers = false },
        )
    }
}

@Composable
fun TaSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(vertical = MaterialTheme.spacings.small),
    )
}

/** Cards are at least this wide, so a phone shows one per row and a tablet several. */
val TA_CARD_MIN_WIDTH = 300.dp

/** The Jellyfin and TubeArchivist servers the app is connected to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaServersSheet(
    servers: TaServersInfo,
    onChangeJellyfinServer: () -> Unit,
    onTubeArchivistSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier.padding(
                    start = MaterialTheme.spacings.medium,
                    end = MaterialTheme.spacings.medium,
                    bottom = MaterialTheme.spacings.default,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        ) {
            TaServerCard(
                logo = {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_logo),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(36.dp),
                    )
                },
                kind = stringResource(CoreR.string.ta_jellyfin),
                name = servers.jellyfinName,
                lines =
                    listOfNotNull(
                        servers.jellyfinAddress,
                        servers.jellyfinUser?.let {
                            stringResource(CoreR.string.ta_signed_in_as, it)
                        },
                    ),
                status = null,
                buttonText = stringResource(CoreR.string.ta_change_server),
                onButtonClick = onChangeJellyfinServer,
            )
            TaServerCard(
                logo = { TaLogo(modifier = Modifier.size(36.dp)) },
                kind = stringResource(CoreR.string.ta_tubearchivist),
                name = servers.taAddress,
                lines = listOfNotNull(servers.taVersion?.let { "TubeArchivist $it" }),
                status =
                    servers.taError
                        ?: stringResource(
                            if (servers.taVersion != null) CoreR.string.ta_connected
                            else CoreR.string.ta_checking
                        ),
                isError = servers.taError != null,
                buttonText = stringResource(CoreR.string.title_settings),
                onButtonClick = onTubeArchivistSettings,
            )
        }
    }
}

@Composable
private fun TaServerCard(
    logo: @Composable () -> Unit,
    kind: String,
    name: String?,
    lines: List<String>,
    status: String?,
    buttonText: String,
    onButtonClick: () -> Unit,
    isError: Boolean = false,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(MaterialTheme.spacings.medium),
        ) {
            logo()
            Spacer(Modifier.width(MaterialTheme.spacings.medium))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = kind,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = name ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                lines.forEach {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (isError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            TextButton(onClick = onButtonClick) { Text(buttonText) }
        }
    }
}

/**
 * A playlist being watched through, on Continue watching: the video it's at, with the playlist's
 * name and how far into it that is. Tapping it carries on playing the playlist from there, and
 * holding it opens the playlist's page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaPlaylistResumeCard(
    item: TaContinueItem.Playlist,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier =
            Modifier.width(240.dp)
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                )
    ) {
        Box {
            TaVideoThumbnail(video = item.video, modifier = Modifier.fillMaxWidth())
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.align(Alignment.TopStart)
                        .padding(MaterialTheme.spacings.small)
                        .clip(MaterialTheme.shapes.small)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_list_video),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text =
                        stringResource(CoreR.string.ta_playlist_position, item.number, item.count),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        Column(modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 10.dp)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.video.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
