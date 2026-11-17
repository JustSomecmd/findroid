package dev.jdtech.jellyfin.presentation.film

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovies
import dev.jdtech.jellyfin.film.presentation.collection.CollectionAction
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.film.presentation.downloads.ActiveDownload
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadsViewModel
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.presentation.film.components.ActiveDownloadRow
import dev.jdtech.jellyfin.presentation.film.components.CollectionGrid
import dev.jdtech.jellyfin.presentation.film.components.ReorderableDownloadQueue
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.utils.DownloadStatus
import java.util.UUID

@Composable
fun DownloadsScreen(
    onItemClick: (item: FindroidItem) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadItems() }

    DownloadsScreenLayout(
        state = state,
        activeDownloads = activeDownloads,
        onActiveDownloadClick = { item -> onItemClick(item) },
        onReorderQueue = { itemIds -> viewModel.reorderQueue(itemIds) },
        onAction = { action ->
            when (action) {
                is CollectionAction.OnItemClick -> onItemClick(action.item)
                is CollectionAction.OnBackClick -> Unit
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsScreenLayout(
    state: CollectionState,
    onAction: (CollectionAction) -> Unit,
    activeDownloads: List<ActiveDownload> = emptyList(),
    onActiveDownloadClick: (FindroidItem) -> Unit = {},
    onReorderQueue: (List<UUID>) -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val gridState = rememberLazyGridState()

    Scaffold(
        modifier =
            Modifier.fillMaxSize()
                .recalculateWindowInsets()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(CoreR.string.title_download)) },
                windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.sections.isEmpty() && activeDownloads.isEmpty()) {
                Text(
                    text = stringResource(CoreR.string.no_downloads),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        CollectionGrid(
            sections = state.sections,
            innerPadding = innerPadding,
            onAction = onAction,
            gridState = gridState,
            header = {
                if (activeDownloads.isNotEmpty()) {
                    item(key = "active_downloads", span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)
                        ) {
                            Card {
                                Row(
                                    modifier =
                                        Modifier.padding(
                                            horizontal = MaterialTheme.spacings.medium,
                                            vertical = MaterialTheme.spacings.medium,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(CoreR.string.downloads_in_progress),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Spacer(Modifier.width(MaterialTheme.spacings.small))
                                    // How many downloads are running, waiting, paused or failed
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ) {
                                        Text(
                                            text = activeDownloads.size.toString(),
                                            modifier =
                                                Modifier.padding(
                                                    horizontal = 8.dp,
                                                    vertical = 2.dp,
                                                ),
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                }
                            }
                            // Running first, then the queue (which can be reordered, with paused
                            // downloads keeping their place), then the rest
                            val queued = activeDownloads.filter { it.inQueue }
                            activeDownloads
                                .filter {
                                    it.info.status == DownloadStatus.RUNNING ||
                                        it.info.status == DownloadStatus.PENDING
                                }
                                .forEach { download ->
                                    ActiveDownloadRow(
                                        download = download,
                                        onClick = { onActiveDownloadClick(download.item) },
                                    )
                                }
                            if (queued.isNotEmpty()) {
                                ReorderableDownloadQueue(
                                    downloads = queued,
                                    onReorder = onReorderQueue,
                                    onClick = { download -> onActiveDownloadClick(download.item) },
                                    scrollState = gridState,
                                )
                            }
                            activeDownloads
                                .filter {
                                    !it.inQueue &&
                                        (it.info.status == DownloadStatus.PAUSED ||
                                            it.info.status == DownloadStatus.FAILED)
                                }
                                .forEach { download ->
                                    ActiveDownloadRow(
                                        download = download,
                                        onClick = { onActiveDownloadClick(download.item) },
                                    )
                                }
                        }
                    }
                }
            },
        )
    }
}

@PreviewScreenSizes
@Composable
private fun DownloadsScreenLayoutPreview() {
    FindroidTheme {
        DownloadsScreenLayout(
            state =
                CollectionState(
                    sections =
                        listOf(
                            CollectionSection(
                                id = 0,
                                name = UiText.StringResource(CoreR.string.movies_label),
                                items = dummyMovies,
                            )
                        )
                ),
            onAction = {},
        )
    }
}
