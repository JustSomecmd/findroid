package dev.jdtech.jellyfin.presentation.film

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovies
import dev.jdtech.jellyfin.film.presentation.collection.CollectionAction
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.film.presentation.downloads.ActiveDownload
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadsScope
import dev.jdtech.jellyfin.film.presentation.downloads.DownloadsViewModel
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiDownloadEntry
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiDownloadsViewModel
import dev.jdtech.jellyfin.jrwi.JrwiProgress
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.presentation.film.components.ActiveDownloadRow
import dev.jdtech.jellyfin.presentation.film.components.CollectionGrid
import dev.jdtech.jellyfin.presentation.film.components.ReorderableDownloadQueue
import dev.jdtech.jellyfin.presentation.jrwi.JrwiDownloadsSection
import dev.jdtech.jellyfin.presentation.jrwi.jrwiDownloadsByShow
import dev.jdtech.jellyfin.presentation.jrwi.jrwiShowSections
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.tube.alwaysShownTopAppBarColors
import dev.jdtech.jellyfin.presentation.tube.recalculateWindowInsetsUnlessPaged
import dev.jdtech.jellyfin.utils.DownloadStatus
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onItemClick: (item: FindroidItem) -> Unit,
    /** Opens the page of a JRWI episode whose audio is on the phone. */
    onJrwiItemClick: (itemId: Long) -> Unit = {},
    viewModel: DownloadsViewModel = hiltViewModel(),
    jrwiViewModel: JrwiDownloadsViewModel = hiltViewModel(),
    /**
     * Shown inside a page that has the top bar, such as next to the TubeArchivist download queue,
     * so this leaves out its own bar and the space for the status bar.
     */
    embedded: Boolean = false,
    /**
     * Which downloads it lists, for the TubeArchivist and JRWI modes; JRWI's audio only in JRWI.
     */
    scope: DownloadsScope = DownloadsScope.ALL,
    /** The top bar's height, such as JRWI mode's, whose tabs share Home's taller header. */
    topBarHeight: Dp = TopAppBarDefaults.TopAppBarExpandedHeight,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val jrwiDownloads by jrwiViewModel.entries.collectAsStateWithLifecycle()
    val jrwiVideoWatch by jrwiViewModel.videoWatch.collectAsStateWithLifecycle()

    LaunchedEffect(scope) {
        viewModel.setScope(scope)
        viewModel.loadItems()
    }
    // How far the JRWI episodes were played, as the archive has it, each time the page shows
    LifecycleResumeEffect(scope) {
        if (scope == DownloadsScope.JRWI) jrwiViewModel.loadWatch()
        onPauseOrDispose {}
    }

    DownloadsScreenLayout(
        state = state,
        activeDownloads = activeDownloads,
        jrwiDownloads = if (scope == DownloadsScope.NOT_JRWI) emptyList() else jrwiDownloads,
        jrwiVideoWatch = jrwiVideoWatch,
        onJrwiItemClick = onJrwiItemClick,
        onJrwiRemove = jrwiViewModel::remove,
        onActiveDownloadClick = { item -> onItemClick(item) },
        onReorderQueue = { itemIds -> viewModel.reorderQueue(itemIds) },
        embedded = embedded,
        topBarHeight = topBarHeight,
        byShow = scope == DownloadsScope.JRWI,
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
    embedded: Boolean = false,
    topBarHeight: Dp = TopAppBarDefaults.TopAppBarExpandedHeight,
    /**
     * JRWI mode's downloads, by show as TubeArchivist's are by channel, each show's audio with its
     * videos.
     */
    byShow: Boolean = false,
    jrwiDownloads: List<JrwiDownloadEntry> = emptyList(),
    /** How far JRWI's downloaded videos were played, as the archive has it, by episode ID. */
    jrwiVideoWatch: Map<String, JrwiProgress> = emptyMap(),
    onJrwiItemClick: (Long) -> Unit = {},
    onJrwiRemove: (Long) -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val gridState = rememberLazyGridState()

    Scaffold(
        // Insets worked out from where this is on screen would change while it slides in a pager
        modifier =
            if (embedded) Modifier.fillMaxSize()
            else
                Modifier.fillMaxSize()
                    // Not in JRWI mode's pager, which works them out for its pages
                    .recalculateWindowInsetsUnlessPaged()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (!embedded) {
                TopAppBar(
                    title = { Text(text = stringResource(CoreR.string.title_download)) },
                    expandedHeight = topBarHeight,
                    windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                    scrollBehavior = scrollBehavior,
                    colors = alwaysShownTopAppBarColors(),
                )
            }
        },
        contentWindowInsets =
            if (embedded) WindowInsets(0, 0, 0, 0)
            else WindowInsets.statusBars.union(WindowInsets.displayCutout),
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.sections.isEmpty() && activeDownloads.isEmpty() && jrwiDownloads.isEmpty()) {
                Text(
                    text = stringResource(CoreR.string.no_downloads),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        val other = stringResource(CoreR.string.jrwi_downloads_other)
        val shows =
            remember(byShow, state.sections, jrwiDownloads, jrwiVideoWatch, other) {
                if (byShow) {
                    jrwiDownloadsByShow(state.sections, jrwiDownloads, other, jrwiVideoWatch)
                } else {
                    emptyList()
                }
            }
        CollectionGrid(
            sections = if (byShow) emptyList() else state.sections,
            innerPadding = innerPadding,
            onAction = onAction,
            gridState = gridState,
            plainHeaders = true,
            header = {
                if (activeDownloads.isNotEmpty()) {
                    item(key = "active_downloads", span = { GridItemSpan(maxLineSpan) }) {
                        // Grows and shrinks smoothly as downloads start, finish or leave the list
                        Column(
                            modifier = Modifier.animateContentSize(),
                            verticalArrangement =
                                Arrangement.spacedBy(MaterialTheme.spacings.small),
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
                            // The running download and the queue can be reordered together (paused
                            // downloads keep their place). Putting a waiting download above the
                            // running one makes it start. The rest follows below.
                            val reorderable = activeDownloads.filter {
                                it.inQueue ||
                                    it.info.status == DownloadStatus.RUNNING ||
                                    it.info.status == DownloadStatus.PENDING
                            }
                            if (reorderable.isNotEmpty()) {
                                ReorderableDownloadQueue(
                                    downloads = reorderable,
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
                if (byShow) {
                    jrwiShowSections(
                        shows = shows,
                        onVideoClick = { item -> onAction(CollectionAction.OnItemClick(item)) },
                        onAudioClick = onJrwiItemClick,
                        onAudioRemove = onJrwiRemove,
                    )
                } else if (jrwiDownloads.isNotEmpty()) {
                    item(key = "jrwi_downloads", span = { GridItemSpan(maxLineSpan) }) {
                        JrwiDownloadsSection(
                            entries = jrwiDownloads,
                            onClick = onJrwiItemClick,
                            onRemove = onJrwiRemove,
                        )
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
