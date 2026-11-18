package dev.jdtech.jellyfin.presentation.tube

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.tube.TaPlaylistViewModel
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.utils.ObserveAsEvents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaPlaylistScreen(
    playlistId: String,
    onVideoClick: (videoId: String) -> Unit,
    onChannelClick: (channelId: String) -> Unit,
    navigateBack: () -> Unit,
    viewModel: TaPlaylistViewModel = hiltViewModel(),
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    var descriptionExpanded by rememberSaveable { mutableStateOf(false) }
    var serverSide by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    ObserveAsEvents(viewModel.messages) { message ->
        Toast.makeText(context, taMessageText(context, message), Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(playlistId) { viewModel.load(playlistId) }

    val gridState = rememberLazyGridState()
    LoadMoreEffect(gridState) { viewModel.loadMore() }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    TaSideTitle(
                        title = playlist?.name.orEmpty(),
                        onServer = serverSide,
                        onToggle = { serverSide = !serverSide },
                        localIcon = CoreR.drawable.ic_list_video,
                        localLabel = stringResource(CoreR.string.ta_side_playlist),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                actions = { TaOpenInWebButton(path = "/playlist/$playlistId") },
                windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = TA_CARD_MIN_WIDTH),
            state = gridState,
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(MaterialTheme.spacings.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            val current = playlist
            if (serverSide && current != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "server") {
                    TaPlaylistServerSide(
                        playlist = current,
                        onSubscribedChange = viewModel::setSubscribed,
                        onReversedChange = viewModel::setReversed,
                        onRefresh = viewModel::refresh,
                    )
                }
                return@LazyVerticalGrid
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column {
                    error?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
                    playlist?.let { playlist ->
                        playlist.channelName?.let { channelName ->
                            Text(
                                text = channelName,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier =
                                    Modifier.clickable(enabled = playlist.channelId != null) {
                                        playlist.channelId?.let(onChannelClick)
                                    },
                            )
                        }
                        Text(
                            text =
                                stringResource(CoreR.string.ta_video_count, playlist.entries.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        playlist.description
                            ?.takeIf { it.isNotBlank() }
                            ?.let { description ->
                                Text(
                                    text = rememberLinkedText(description),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier =
                                        Modifier.padding(top = MaterialTheme.spacings.small)
                                            .animateContentSize()
                                            .clickable {
                                                descriptionExpanded = !descriptionExpanded
                                            },
                                )
                            }
                    }
                }
            }
            items(videos.items, key = { it.youtubeId }) { video ->
                TaVideoCard(video = video, onClick = { onVideoClick(video.youtubeId) })
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                TaListFooter(
                    state = videos,
                    onRetry = { viewModel.loadMore() },
                    emptyText = stringResource(CoreR.string.ta_no_videos),
                )
            }
        }
    }
}

/** The playlist's side on the server: its details, subscription and refreshing it. */
@Composable
private fun TaPlaylistServerSide(
    playlist: TaPlaylist,
    onSubscribedChange: (Boolean) -> Unit,
    onReversedChange: (Boolean) -> Unit,
    onRefresh: (withVideos: Boolean) -> Unit,
) {
    val custom = playlist.type == "custom"
    Column {
        TaInfoHeading(stringResource(CoreR.string.ta_info_details))
        TaInfoRow(stringResource(CoreR.string.ta_info_playlist_id), playlist.id)
        TaInfoRow(
            stringResource(CoreR.string.ta_info_type),
            stringResource(
                if (custom) CoreR.string.ta_info_custom_playlist
                else CoreR.string.ta_info_youtube_playlist
            ),
        )
        TaInfoRow(
            stringResource(CoreR.string.ta_videos),
            stringResource(
                CoreR.string.ta_info_downloaded_of,
                playlist.entries.count { it.downloaded },
                playlist.entries.size,
            ),
        )
        playlist.active?.let { active ->
            TaInfoRow(
                stringResource(CoreR.string.ta_info_on_youtube),
                stringResource(
                    if (active) CoreR.string.ta_info_available else CoreR.string.ta_info_removed
                ),
            )
        }
        formatTaDate(playlist.lastRefresh)?.let {
            TaInfoRow(stringResource(CoreR.string.ta_info_last_refreshed), it)
        }
        if (!custom) {
            TaInfoHeading(stringResource(CoreR.string.title_settings))
            TaSettingRow(
                label = stringResource(CoreR.string.ta_subscribe),
                value = stringResource(CoreR.string.ta_playlist_subscribe_summary),
                onClick = { onSubscribedChange(!playlist.subscribed) },
                trailing = {
                    Switch(checked = playlist.subscribed, onCheckedChange = onSubscribedChange)
                },
            )
            val reversed = playlist.sortOrder == "bottom"
            TaSettingRow(
                label = stringResource(CoreR.string.ta_playlist_reverse),
                value = stringResource(CoreR.string.ta_playlist_reverse_summary),
                onClick = { onReversedChange(!reversed) },
                trailing = { Switch(checked = reversed, onCheckedChange = onReversedChange) },
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            modifier = Modifier.padding(top = MaterialTheme.spacings.medium),
        ) {
            if (!custom) {
                OutlinedButton(onClick = { onRefresh(false) }) {
                    Text(stringResource(CoreR.string.ta_refresh))
                }
            }
            OutlinedButton(onClick = { onRefresh(true) }) {
                Text(stringResource(CoreR.string.ta_refresh_with_videos))
            }
        }
    }
}
