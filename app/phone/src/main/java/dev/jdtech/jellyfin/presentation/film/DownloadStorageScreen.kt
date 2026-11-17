package dev.jdtech.jellyfin.presentation.film

import android.text.format.Formatter
import androidx.annotation.PluralsRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.downloads.ServerStorageState
import dev.jdtech.jellyfin.film.presentation.downloads.StorageEntry
import dev.jdtech.jellyfin.film.presentation.downloads.StorageState
import dev.jdtech.jellyfin.film.presentation.downloads.StorageViewModel
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.ItemPoster
import dev.jdtech.jellyfin.presentation.theme.spacings

/**
 * How much space things take: downloads on this phone, and every show on the server. The server tab
 * only adds things up once it is opened, as that goes through every episode.
 */
@Composable
fun DownloadStorageScreen(
    navigateBack: () -> Unit,
    onItemClick: (FindroidItem) -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(true) { viewModel.load() }
    LaunchedEffect(selectedTab) { if (selectedTab == 1) viewModel.loadServer() }

    StorageLayout(
        state = state,
        serverState = serverState,
        selectedTab = selectedTab,
        onSelectTab = { selectedTab = it },
        navigateBack = navigateBack,
        onItemClick = onItemClick,
        onDelete = { viewModel.delete(it) },
        onRetryServer = { viewModel.loadServer(retry = true) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageLayout(
    state: StorageState,
    serverState: ServerStorageState,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    navigateBack: () -> Unit,
    onItemClick: (FindroidItem) -> Unit,
    onDelete: (StorageEntry) -> Unit,
    onRetryServer: () -> Unit,
) {
    var entryToDelete by remember { mutableStateOf<StorageEntry?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize().recalculateWindowInsets(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(CoreR.string.storage_title)) },
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_arrow_left),
                                contentDescription = null,
                            )
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { onSelectTab(0) },
                        text = { Text(stringResource(CoreR.string.storage_tab_phone)) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { onSelectTab(1) },
                        text = { Text(stringResource(CoreR.string.storage_tab_server)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(MaterialTheme.spacings.default),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            ) {
                if (selectedTab == 0) {
                    phoneTab(
                        state = state,
                        onItemClick = onItemClick,
                        onDelete = { entryToDelete = it },
                    )
                } else {
                    serverTab(
                        state = serverState,
                        onItemClick = onItemClick,
                        onRetry = onRetryServer,
                    )
                }
            }
        }
    }

    entryToDelete?.let { entry ->
        val size = formatSize(entry.localBytes)
        AlertDialog(
            title = { Text(stringResource(CoreR.string.storage_delete_title)) },
            text = {
                Text(
                    if (entry.item is FindroidShow) {
                        quantityString(
                            CoreR.plurals.storage_delete_show_message,
                            entry.downloads.size,
                            entry.downloads.size,
                            entry.item.name,
                            size,
                        )
                    } else {
                        stringResource(
                            CoreR.string.storage_delete_movie_message,
                            entry.item.name,
                            size,
                        )
                    }
                )
            },
            onDismissRequest = { entryToDelete = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        entryToDelete = null
                        onDelete(entry)
                    }
                ) {
                    Text(stringResource(CoreR.string.storage_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) {
                    Text(stringResource(CoreR.string.cancel))
                }
            },
        )
    }
}

private fun LazyListScope.phoneTab(
    state: StorageState,
    onItemClick: (FindroidItem) -> Unit,
    onDelete: (StorageEntry) -> Unit,
) {
    item(key = "phone_summary") { PhoneSummary(state) }

    if (!state.isLoading && state.entries.isEmpty()) {
        item(key = "phone_empty") {
            Text(
                text = stringResource(CoreR.string.no_downloads),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (state.entries.isNotEmpty()) {
        item(key = "phone_header") {
            SectionHeader(stringResource(CoreR.string.storage_items_header))
        }
    }

    items(items = state.entries, key = { "phone_${it.item.id}" }) { entry ->
        val details =
            if (entry.item is FindroidShow) {
                quantityString(
                    CoreR.plurals.storage_episodes_local,
                    entry.downloads.size,
                    entry.downloads.size,
                    formatSize(entry.localBytes),
                )
            } else {
                formatSize(entry.localBytes)
            }
        StorageRow(
            item = entry.item,
            details = details,
            onClick = { onItemClick(entry.item) },
            onDelete = { onDelete(entry) },
        )
    }
}

private fun LazyListScope.serverTab(
    state: ServerStorageState,
    onItemClick: (FindroidItem) -> Unit,
    onRetry: () -> Unit,
) {
    item(key = "server_summary") { ServerSummary(state, onRetry) }

    if (state.shows.isNotEmpty()) {
        item(key = "server_header") {
            SectionHeader(stringResource(CoreR.string.storage_server_shows_header))
        }
    }

    // Bars compare each show with the biggest one
    val biggest = state.shows.firstOrNull()?.size?.bytes ?: 0L
    items(items = state.shows, key = { "server_${it.show.id}" }) { entry ->
        val details =
            quantityString(
                CoreR.plurals.storage_episodes_local,
                entry.size.episodeCount,
                entry.size.episodeCount,
                formatSize(entry.size.bytes),
            )
        StorageRow(
            item = entry.show,
            details = details,
            share = if (biggest > 0) entry.size.bytes.toFloat() / biggest else null,
            onClick = { onItemClick(entry.show) },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = MaterialTheme.spacings.small),
    )
}

@Composable
private fun SummaryCard(content: @Composable () -> Unit) {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            content()
        }
    }
}

@Composable
private fun PhoneSummary(state: StorageState) {
    SummaryCard {
        Text(
            text = stringResource(CoreR.string.storage_downloads_total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = formatSize(state.totalBytes), style = MaterialTheme.typography.headlineMedium)

        if (state.deviceTotalBytes > 0) {
            val total = state.deviceTotalBytes.toFloat()
            val downloads = (state.totalBytes / total).coerceIn(0f, 1f)
            val free = (state.deviceFreeBytes / total).coerceIn(0f, 1f - downloads)
            val other = (1f - downloads - free).coerceAtLeast(0f)

            val downloadsColor = MaterialTheme.colorScheme.primary
            val otherColor = MaterialTheme.colorScheme.tertiary
            val freeColor = MaterialTheme.colorScheme.surfaceVariant

            // The whole phone: downloads, everything else, and what is left
            Row(
                modifier =
                    Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(freeColor)
            ) {
                if (downloads > 0f) {
                    Box(Modifier.weight(downloads).fillMaxSize().background(downloadsColor))
                }
                if (other > 0f) {
                    Box(Modifier.weight(other).fillMaxSize().background(otherColor))
                }
                if (free > 0f) {
                    Box(Modifier.weight(free).fillMaxSize())
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium)) {
                LegendItem(downloadsColor, stringResource(CoreR.string.storage_legend_downloads))
                LegendItem(otherColor, stringResource(CoreR.string.storage_legend_other))
                LegendItem(freeColor, stringResource(CoreR.string.storage_legend_free))
            }

            Text(
                text =
                    stringResource(
                        CoreR.string.storage_device_free,
                        formatSize(state.deviceFreeBytes),
                        formatSize(state.deviceTotalBytes),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ServerSummary(state: ServerStorageState, onRetry: () -> Unit) {
    SummaryCard {
        Text(
            text = stringResource(CoreR.string.storage_server_total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            state.failed -> {
                Text(
                    text = stringResource(CoreR.string.storage_server_failed),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(CoreR.string.storage_server_retry))
                }
            }
            state.isLoaded -> {
                Text(
                    text = formatSize(state.totalBytes),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text =
                        quantityString(
                            CoreR.plurals.storage_server_shows_count,
                            state.shows.size,
                            state.shows.size,
                            state.episodeCount,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                // Counting goes through every episode, which can take a while on big servers
                val progress = if (state.total > 0) state.counted.toFloat() / state.total else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                )
                val counting =
                    if (state.total > 0) {
                        stringResource(
                            CoreR.string.storage_server_counting_progress,
                            state.counted,
                            state.total,
                        )
                    } else {
                        stringResource(CoreR.string.storage_server_counting)
                    }
                Text(
                    text = counting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A show or movie with its size. [share] draws a bar of how it compares, and [onDelete] adds a
 * delete button.
 */
@Composable
private fun StorageRow(
    item: FindroidItem,
    details: String,
    onClick: () -> Unit,
    share: Float? = null,
    onDelete: (() -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ItemPoster(
                item = item,
                direction = Direction.VERTICAL,
                modifier = Modifier.height(72.dp).clip(MaterialTheme.shapes.small),
            )
            Spacer(Modifier.width(MaterialTheme.spacings.medium))
            Column(
                modifier = Modifier.weight(1f).padding(end = MaterialTheme.spacings.small),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = details, style = MaterialTheme.typography.bodyMedium)
                if (share != null) {
                    LinearProgressIndicator(
                        progress = { share.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(CircleShape),
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_trash),
                        contentDescription = stringResource(CoreR.string.storage_delete_title),
                    )
                }
            }
        }
    }
}

@Composable
private fun formatSize(bytes: Long): String =
    Formatter.formatShortFileSize(LocalContext.current, bytes)

@Composable
private fun quantityString(@PluralsRes id: Int, count: Int, vararg formatArgs: Any): String =
    LocalContext.current.resources.getQuantityString(id, count, *formatArgs)
