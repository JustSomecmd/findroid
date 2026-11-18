package dev.jdtech.jellyfin.presentation.tube

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.tube.TaQueueFilter
import dev.jdtech.jellyfin.film.presentation.tube.TaQueueViewModel
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.tubearchivist.TaDownloadItem
import dev.jdtech.jellyfin.tubearchivist.TaNotification
import dev.jdtech.jellyfin.utils.ObserveAsEvents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaQueueScreen(viewModel: TaQueueViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadMore() }

    LifecycleResumeEffect(Unit) {
        viewModel.startWatchingTasks()
        onPauseOrDispose { viewModel.stopWatchingTasks() }
    }

    ObserveAsEvents(viewModel.messages) { message ->
        Toast.makeText(context, taMessageText(context, message), Toast.LENGTH_LONG).show()
    }

    val listState = rememberLazyListState()
    LoadMoreEffect(listState) { viewModel.loadMore() }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier =
            Modifier.fillMaxSize()
                .recalculateWindowInsets()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(CoreR.string.ta_queue)) },
                    actions = {
                        IconButton(onClick = { viewModel.rescanSubscriptions() }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_refresh),
                                contentDescription =
                                    stringResource(CoreR.string.ta_rescan_subscriptions),
                            )
                        }
                        IconButton(onClick = { viewModel.startDownloading() }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_download),
                                contentDescription =
                                    stringResource(CoreR.string.ta_start_downloading),
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_more_vertical),
                                    contentDescription = null,
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(CoreR.string.ta_rescan_subscriptions))
                                    },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.rescanSubscriptions()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(CoreR.string.ta_start_downloading))
                                    },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.startDownloading()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (filter == TaQueueFilter.PENDING)
                                                    CoreR.string.ta_clear_pending
                                                else CoreR.string.ta_clear_ignored
                                            )
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        showClearDialog = true
                                    },
                                )
                            }
                        }
                    },
                    windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                    scrollBehavior = scrollBehavior,
                )
                PrimaryTabRow(selectedTabIndex = filter.ordinal) {
                    TaQueueFilter.entries.forEach { entry ->
                        Tab(
                            selected = filter == entry,
                            onClick = { viewModel.selectFilter(entry) },
                            text = {
                                Text(
                                    stringResource(
                                        when (entry) {
                                            TaQueueFilter.PENDING -> CoreR.string.ta_pending
                                            TaQueueFilter.IGNORED -> CoreR.string.ta_ignored
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
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_plus),
                    contentDescription = stringResource(CoreR.string.ta_add_to_queue),
                )
            }
        },
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = MaterialTheme.spacings.medium,
                        end = MaterialTheme.spacings.medium,
                        top = MaterialTheme.spacings.small,
                        bottom = 88.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                items(tasks, key = { "task" + it.id }) { task -> TaTaskCard(task) }
                items(items.items, key = { it.youtubeId }) { item ->
                    TaQueueItem(
                        item = item,
                        ignored = filter == TaQueueFilter.IGNORED,
                        onDownloadNow = { viewModel.downloadNow(item) },
                        onIgnore = { viewModel.ignore(item) },
                        onUnignore = { viewModel.unignore(item) },
                        onRemove = { viewModel.remove(item) },
                        modifier = Modifier.animateItem(),
                    )
                }
                item(key = "footer") {
                    TaListFooter(
                        state = items,
                        onRetry = { viewModel.loadMore() },
                        emptyText = stringResource(CoreR.string.ta_queue_empty),
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        var downloadNow by rememberSaveable { mutableStateOf(false) }
        TaUrlDialog(
            title = stringResource(CoreR.string.ta_add_to_queue),
            hint = stringResource(CoreR.string.ta_add_to_queue_hint),
            confirmText = stringResource(CoreR.string.add),
            onConfirm = { text ->
                viewModel.add(text, downloadNow)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
            extraContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { downloadNow = !downloadNow },
                ) {
                    Checkbox(checked = downloadNow, onCheckedChange = { downloadNow = it })
                    Text(stringResource(CoreR.string.ta_download_right_away))
                }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text(
                    stringResource(
                        if (filter == TaQueueFilter.PENDING) CoreR.string.ta_clear_pending
                        else CoreR.string.ta_clear_ignored
                    )
                )
            },
            text = { Text(stringResource(CoreR.string.ta_clear_queue_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearAll()
                    }
                ) {
                    Text(stringResource(CoreR.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(CoreR.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun TaTaskCard(task: TaNotification) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(MaterialTheme.spacings.medium)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleSmall,
                color =
                    if (task.level == "error") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
            task.messages.lastOrNull()?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val progress = task.progress
            Spacer(Modifier.padding(top = MaterialTheme.spacings.small))
            if (progress != null && progress > 0) {
                LinearProgressIndicator(
                    progress = { progress.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TaQueueItem(
    item: TaDownloadItem,
    ignored: Boolean,
    onDownloadNow: () -> Unit,
    onIgnore: () -> Unit,
    onUnignore: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier =
            modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { menuOpen = true },
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.width(144.dp).aspectRatio(16f / 9f)) {
            TaImage(
                path = item.thumbUrl,
                modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.small),
            )
            item.duration
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    TaOverlayLabel(
                        text = it,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    )
                }
        }
        Spacer(Modifier.width(MaterialTheme.spacings.small))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    listOfNotNull(item.channelName, fullDate(item.published)).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.message
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_more_vertical),
                    contentDescription = null,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (ignored) {
                    DropdownMenuItem(
                        text = { Text(stringResource(CoreR.string.ta_add_back)) },
                        onClick = {
                            menuOpen = false
                            onUnignore()
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(CoreR.string.ta_download_now)) },
                        onClick = {
                            menuOpen = false
                            onDownloadNow()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(CoreR.string.ta_ignore)) },
                        onClick = {
                            menuOpen = false
                            onIgnore()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(CoreR.string.remove)) },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
}
