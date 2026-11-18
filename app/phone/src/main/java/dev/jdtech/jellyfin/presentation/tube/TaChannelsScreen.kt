package dev.jdtech.jellyfin.presentation.tube

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.tube.TaChannelsTab
import dev.jdtech.jellyfin.film.presentation.tube.TaChannelsViewModel
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaChannelsScreen(
    onChannelClick: (channelId: String) -> Unit,
    onPlaylistClick: (playlistId: String) -> Unit,
    viewModel: TaChannelsViewModel = hiltViewModel(),
    /** The tabs' pages, when the main pager needs to turn them, such as to the last one. */
    tabsPagerState: PagerState? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tab by viewModel.tab.collectAsStateWithLifecycle()

    var showSubscribeDialog by rememberSaveable { mutableStateOf(false) }
    // On Playlists: a new playlist of TubeArchivist's own, or one from YouTube
    var showPlaylistChoice by rememberSaveable { mutableStateOf(false) }
    var showNewPlaylistDialog by rememberSaveable { mutableStateOf(false) }

    ObserveAsEvents(viewModel.messages) { message ->
        Toast.makeText(context, taMessageText(context, message), Toast.LENGTH_LONG).show()
    }

    // Swiping moves between the tabs, with each list sliding along with the finger
    val ownPagerState = rememberPagerState(initialPage = tab.ordinal) { TaChannelsTab.entries.size }
    val pagerState = tabsPagerState ?: ownPagerState
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .collect { page -> viewModel.selectTab(TaChannelsTab.entries[page]) }
    }
    val onPlaylists = TaChannelsTab.entries[pagerState.currentPage] == TaChannelsTab.PLAYLISTS

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier =
            Modifier.fillMaxSize()
                .recalculateWindowInsetsUnlessPaged()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                // One grey header, the same as on Home and Downloads, whether scrolled or not
                TopAppBar(
                    title = { Text(text = stringResource(CoreR.string.ta_channels)) },
                    windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                    scrollBehavior = scrollBehavior,
                    colors = alwaysShownTopAppBarColors(),
                )
                // The bar's grey with a touch of the accent, to set the tabs apart from the bar
                // in a way that follows the chosen accent color
                PrimaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor =
                        lerp(
                            MaterialTheme.colorScheme.surfaceContainer,
                            MaterialTheme.colorScheme.primary,
                            0.1f,
                        ),
                ) {
                    TaChannelsTab.entries.forEach { entry ->
                        Tab(
                            selected = pagerState.currentPage == entry.ordinal,
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(entry.ordinal) }
                            },
                            text = {
                                Text(
                                    stringResource(
                                        when (entry) {
                                            TaChannelsTab.SUBSCRIBED -> CoreR.string.ta_subscribed
                                            TaChannelsTab.ALL -> CoreR.string.ta_all_channels
                                            TaChannelsTab.PLAYLISTS -> CoreR.string.ta_playlists
                                        }
                                    )
                                )
                            },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (onPlaylists) showPlaylistChoice = true else showSubscribeDialog = true
                }
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_plus),
                    contentDescription =
                        stringResource(
                            if (onPlaylists) CoreR.string.ta_subscribe_to_playlist
                            else CoreR.string.ta_subscribe_to_channel
                        ),
                )
            }
        },
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            key = { page -> TaChannelsTab.entries[page].name },
        ) { page ->
            TaChannelsPage(
                tab = TaChannelsTab.entries[page],
                viewModel = viewModel,
                onChannelClick = onChannelClick,
                onPlaylistClick = onPlaylistClick,
            )
        }
    }

    if (showPlaylistChoice) {
        AlertDialog(
            onDismissRequest = { showPlaylistChoice = false },
            title = { Text(stringResource(CoreR.string.ta_add_playlist)) },
            text = {
                Column {
                    TaChoiceRow(
                        icon = CoreR.drawable.ic_list_plus,
                        text = stringResource(CoreR.string.ta_new_playlist),
                        onClick = {
                            showPlaylistChoice = false
                            showNewPlaylistDialog = true
                        },
                    )
                    TaChoiceRow(
                        icon = CoreR.drawable.ic_youtube,
                        text = stringResource(CoreR.string.ta_subscribe_youtube_playlist),
                        onClick = {
                            showPlaylistChoice = false
                            showSubscribeDialog = true
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPlaylistChoice = false }) {
                    Text(stringResource(CoreR.string.cancel))
                }
            },
        )
    }

    if (showNewPlaylistDialog) {
        TaUrlDialog(
            title = stringResource(CoreR.string.ta_new_playlist),
            hint = stringResource(CoreR.string.ta_playlist_name),
            confirmText = stringResource(CoreR.string.ta_create),
            onConfirm = { name ->
                viewModel.createPlaylist(name)
                showNewPlaylistDialog = false
            },
            onDismiss = { showNewPlaylistDialog = false },
        )
    }

    if (showSubscribeDialog) {
        TaUrlDialog(
            title =
                stringResource(
                    if (onPlaylists) CoreR.string.ta_subscribe_youtube_playlist
                    else CoreR.string.ta_subscribe_to_channel
                ),
            hint =
                stringResource(
                    if (onPlaylists) CoreR.string.ta_subscribe_playlist_hint
                    else CoreR.string.ta_subscribe_hint
                ),
            confirmText = stringResource(CoreR.string.ta_subscribe),
            onConfirm = { text ->
                viewModel.subscribe(text, playlists = onPlaylists)
                showSubscribeDialog = false
            },
            onDismiss = { showSubscribeDialog = false },
        )
    }
}

/** One tab's list, with its own scroll position. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaChannelsPage(
    tab: TaChannelsTab,
    viewModel: TaChannelsViewModel,
    onChannelClick: (channelId: String) -> Unit,
    onPlaylistClick: (playlistId: String) -> Unit,
) {
    val subscribed by viewModel.subscribed.collectAsStateWithLifecycle()
    val all by viewModel.all.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    // Also loads the first page when the tab is first shown
    val gridState = rememberLazyGridState()
    LoadMoreEffect(gridState) { viewModel.loadMore(tab) }

    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            columns =
                GridCells.Adaptive(
                    minSize = if (tab == TaChannelsTab.PLAYLISTS) TA_CARD_MIN_WIDTH else 120.dp
                ),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(MaterialTheme.spacings.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            // Keeps the list at the top while pages load below, instead of following the
            // loading indicator to the bottom
            item(span = { GridItemSpan(maxLineSpan) }, key = "top") { Spacer(Modifier) }
            when (tab) {
                TaChannelsTab.SUBSCRIBED,
                TaChannelsTab.ALL -> {
                    val list = if (tab == TaChannelsTab.SUBSCRIBED) subscribed else all
                    items(list.items, key = { it.id }) { channel ->
                        TaChannelItem(channel = channel, onClick = { onChannelClick(channel.id) })
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                        TaListFooter(
                            state = list,
                            onRetry = { viewModel.loadMore(tab) },
                            emptyText = stringResource(CoreR.string.ta_no_channels),
                        )
                    }
                }
                TaChannelsTab.PLAYLISTS -> {
                    items(playlists.items, key = { it.id }) { playlist ->
                        TaPlaylistCard(
                            playlist = playlist,
                            onClick = { onPlaylistClick(playlist.id) },
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                        TaListFooter(
                            state = playlists,
                            onRetry = { viewModel.loadMore(tab) },
                            emptyText = stringResource(CoreR.string.ta_no_playlists),
                        )
                    }
                }
            }
        }
    }
}

/** Asks for one or more YouTube links, separated by spaces or new lines. */
@Composable
fun TaUrlDialog(
    title: String,
    hint: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    extraContent: @Composable () -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(hint) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                extraContent()
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) }
        },
    )
}

/** One of the choices in a dialog: an icon and a line of text. */
@Composable
private fun TaChoiceRow(@DrawableRes icon: Int, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onClick)
                .padding(vertical = MaterialTheme.spacings.medium),
    ) {
        Icon(painter = painterResource(icon), contentDescription = null)
        Spacer(Modifier.width(MaterialTheme.spacings.medium))
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}
