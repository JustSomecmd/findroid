package dev.jdtech.jellyfin.presentation.tube

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.tube.TaSearchViewModel
import dev.jdtech.jellyfin.presentation.theme.spacings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaSearchScreen(
    onVideoClick: (videoId: String) -> Unit,
    onChannelClick: (channelId: String) -> Unit,
    onPlaylistClick: (playlistId: String) -> Unit,
    navigateBack: () -> Unit,
    viewModel: TaSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (state.query.isEmpty()) focusRequester.requestFocus()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text(stringResource(CoreR.string.ta_search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
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
                windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
            )
        },
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            val results = state.results
            when {
                state.loading && results == null ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null ->
                    Text(
                        text = state.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier.align(Alignment.Center)
                                .padding(MaterialTheme.spacings.default),
                    )
                results == null -> Unit
                results.videos.isEmpty() &&
                    results.channels.isEmpty() &&
                    results.playlists.isEmpty() ->
                    Text(
                        text = stringResource(CoreR.string.ta_no_results),
                        modifier = Modifier.align(Alignment.Center),
                    )
                else ->
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = TA_CARD_MIN_WIDTH),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(MaterialTheme.spacings.medium),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                    ) {
                        if (results.channels.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "channels") {
                                LazyRow(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(MaterialTheme.spacings.small)
                                ) {
                                    items(results.channels, key = { it.id }) { channel ->
                                        TaChannelItem(
                                            channel = channel,
                                            onClick = { onChannelClick(channel.id) },
                                            modifier = Modifier.padding(end = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                        if (results.playlists.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "playlists_title") {
                                TaSectionTitle(stringResource(CoreR.string.ta_playlists))
                            }
                            items(results.playlists, key = { "p" + it.id }) { playlist ->
                                TaPlaylistCard(
                                    playlist = playlist,
                                    onClick = { onPlaylistClick(playlist.id) },
                                )
                            }
                        }
                        if (results.videos.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "videos_title") {
                                TaSectionTitle(stringResource(CoreR.string.ta_videos))
                            }
                            items(results.videos, key = { "v" + it.youtubeId }) { video ->
                                TaVideoCard(
                                    video = video,
                                    onClick = { onVideoClick(video.youtubeId) },
                                )
                            }
                        }
                    }
            }
        }
    }
}
