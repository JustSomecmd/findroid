package dev.jdtech.jellyfin.presentation.tube

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.tube.TaRemovedViewModel
import dev.jdtech.jellyfin.presentation.theme.spacings

/**
 * Every video on the server that's gone from YouTube, newest first, from "Removed from YouTube"
 * under Video types on Home's server side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaRemovedScreen(
    onVideoClick: (videoId: String) -> Unit,
    navigateBack: () -> Unit,
    viewModel: TaRemovedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(CoreR.string.ta_stats_removed))
                        val count = state.expected ?: state.videos.size.toLong()
                        if (!state.loading || state.expected != null) {
                            Text(
                                text =
                                    pluralStringResource(
                                        CoreR.plurals.ta_video_count,
                                        count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                        formatNumber(count),
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
            when {
                state.videos.isEmpty() && state.loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.videos.isEmpty() && state.error != null ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                            Modifier.align(Alignment.Center)
                                .padding(MaterialTheme.spacings.default),
                    ) {
                        Text(
                            text = state.error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = viewModel::load) {
                            Text(stringResource(CoreR.string.retry))
                        }
                    }
                state.videos.isEmpty() ->
                    Text(
                        text = stringResource(CoreR.string.ta_removed_none),
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier.align(Alignment.Center)
                                .padding(MaterialTheme.spacings.default),
                    )
                else ->
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = TA_CARD_MIN_WIDTH),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(MaterialTheme.spacings.medium),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                    ) {
                        val total = state.scanTotal
                        if (state.loading && total != null) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "scan") {
                                Column(modifier = Modifier.padding(bottom = 4.dp)) {
                                    Text(
                                        text =
                                            stringResource(
                                                CoreR.string.ta_removed_scanning,
                                                formatNumber(state.scanned.toLong()),
                                                formatNumber(total.toLong()),
                                            ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    LinearProgressIndicator(
                                        progress = {
                                            if (total > 0) state.scanned.toFloat() / total else 0f
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                    )
                                }
                            }
                        }
                        state.error?.let { error ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                                Column {
                                    Text(
                                        text = error,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    TextButton(onClick = viewModel::load) {
                                        Text(stringResource(CoreR.string.retry))
                                    }
                                }
                            }
                        }
                        items(state.videos, key = { it.youtubeId }) { video ->
                            TaVideoCard(
                                video = video,
                                onClick = { onVideoClick(video.youtubeId) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
            }
        }
    }
}
