package dev.jdtech.jellyfin.presentation.tube

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.tube.TaHomeViewModel
import dev.jdtech.jellyfin.presentation.theme.spacings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaHomeScreen(
    onVideoClick: (videoId: String) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: TaHomeViewModel = hiltViewModel(),
) {
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
                title = { Text(text = stringResource(CoreR.string.title_home)) },
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
