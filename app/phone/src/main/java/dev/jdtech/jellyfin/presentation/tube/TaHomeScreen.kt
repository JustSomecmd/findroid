package dev.jdtech.jellyfin.presentation.tube

import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.tube.TaHomeViewModel
import dev.jdtech.jellyfin.film.presentation.tube.TaServersInfo
import dev.jdtech.jellyfin.presentation.film.components.ServerSelectionBottomSheet
import dev.jdtech.jellyfin.presentation.theme.spacings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaHomeScreen(
    onVideoClick: (videoId: String) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onManageServers: () -> Unit,
    onTubeArchivistSettings: () -> Unit,
    viewModel: TaHomeViewModel = hiltViewModel(),
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    var showServers by rememberSaveable { mutableStateOf(false) }
    var showJellyfinServers by rememberSaveable { mutableStateOf(false) }
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val latest by viewModel.latest.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.onShown()
        onPauseOrDispose {}
    }

    val gridState = rememberLazyGridState()
    LoadMoreEffect(gridState) { viewModel.loadMore() }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier =
            Modifier.fillMaxSize()
                .recalculateWindowInsets()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    TaServerButton(
                        name =
                            servers.jellyfinName ?: stringResource(CoreR.string.ta_tubearchivist),
                        hasError = servers.taError != null,
                        onClick = { showServers = true },
                    )
                },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_search),
                            contentDescription = stringResource(CoreR.string.search),
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_settings),
                            contentDescription = stringResource(CoreR.string.title_settings),
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = TA_CARD_MIN_WIDTH),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(MaterialTheme.spacings.medium),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                // Keeps the page at the top when continue watching loads after the rest
                item(span = { GridItemSpan(maxLineSpan) }, key = "top") { Spacer(Modifier) }
                if (continueWatching.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                        Column {
                            TaSectionTitle(stringResource(CoreR.string.ta_continue_watching))
                            LazyRow(
                                horizontalArrangement =
                                    Arrangement.spacedBy(MaterialTheme.spacings.medium)
                            ) {
                                items(continueWatching, key = { it.youtubeId }) { video ->
                                    TaVideoSmallCard(
                                        video = video,
                                        onClick = { onVideoClick(video.youtubeId) },
                                    )
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

/** The connected server's name, as on Findroid's home page. */
@Composable
private fun TaServerButton(name: String, hasError: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            modifier =
                Modifier.padding(horizontal = MaterialTheme.spacings.medium, vertical = 6.dp),
        ) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_logo),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasError) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_alert_circle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

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
                icon = CoreR.drawable.ic_logo,
                tintIcon = false,
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
                icon = CoreR.drawable.ic_youtube,
                tintIcon = true,
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
    @DrawableRes icon: Int,
    tintIcon: Boolean,
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
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (tintIcon) MaterialTheme.colorScheme.primary else Color.Unspecified,
                modifier = Modifier.size(36.dp),
            )
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
