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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.jdtech.jellyfin.film.presentation.downloads.StorageEntry
import dev.jdtech.jellyfin.film.presentation.downloads.StorageState
import dev.jdtech.jellyfin.film.presentation.downloads.StorageViewModel
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.presentation.film.components.Direction
import dev.jdtech.jellyfin.presentation.film.components.ItemPoster
import dev.jdtech.jellyfin.presentation.theme.spacings

/** How much space downloads take, in total and per show or movie. */
@Composable
fun DownloadStorageScreen(
    navigateBack: () -> Unit,
    onItemClick: (FindroidItem) -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.load() }

    DownloadStorageLayout(
        state = state,
        navigateBack = navigateBack,
        onItemClick = onItemClick,
        onDelete = { viewModel.delete(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadStorageLayout(
    state: StorageState,
    navigateBack: () -> Unit,
    onItemClick: (FindroidItem) -> Unit,
    onDelete: (StorageEntry) -> Unit,
) {
    var entryToDelete by remember { mutableStateOf<StorageEntry?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize().recalculateWindowInsets(),
        topBar = {
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
                item(key = "summary") { StorageSummary(state) }

                if (!state.isLoading && state.entries.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(CoreR.string.no_downloads),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                if (state.entries.isNotEmpty()) {
                    item(key = "header") {
                        Text(
                            text = stringResource(CoreR.string.storage_items_header),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = MaterialTheme.spacings.small),
                        )
                    }
                }

                items(items = state.entries, key = { it.item.id }) { entry ->
                    StorageRow(
                        entry = entry,
                        onClick = { onItemClick(entry.item) },
                        onDelete = { entryToDelete = entry },
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

@Composable
private fun StorageSummary(state: StorageState) {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacings.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            Text(
                text = stringResource(CoreR.string.storage_downloads_total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatSize(state.totalBytes),
                style = MaterialTheme.typography.headlineMedium,
            )

            if (state.deviceTotalBytes > 0) {
                val total = state.deviceTotalBytes.toFloat()
                val downloads = (state.totalBytes / total).coerceIn(0f, 1f)
                val free = (state.deviceFreeBytes / total).coerceIn(0f, 1f - downloads)
                val other = (1f - downloads - free).coerceAtLeast(0f)

                val downloadsColor = MaterialTheme.colorScheme.primary
                val otherColor = MaterialTheme.colorScheme.tertiary
                val freeColor = MaterialTheme.colorScheme.surfaceVariant

                // The whole device: downloads, everything else, and what is left
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(12.dp)
                            .clip(CircleShape)
                            .background(freeColor)
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
                    LegendItem(
                        downloadsColor,
                        stringResource(CoreR.string.storage_legend_downloads),
                    )
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

@Composable
private fun StorageRow(entry: StorageEntry, onClick: () -> Unit, onDelete: () -> Unit) {
    val isShow = entry.item is FindroidShow

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
                item = entry.item,
                direction = Direction.VERTICAL,
                modifier = Modifier.height(72.dp).clip(MaterialTheme.shapes.small),
            )
            Spacer(Modifier.width(MaterialTheme.spacings.medium))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        if (isShow) {
                            quantityString(
                                CoreR.plurals.storage_episodes_local,
                                entry.downloads.size,
                                entry.downloads.size,
                                formatSize(entry.localBytes),
                            )
                        } else {
                            formatSize(entry.localBytes)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (isShow) {
                    val serverSize = entry.serverSize
                    if (entry.isLoadingServerSize) {
                        Text(
                            text = stringResource(CoreR.string.storage_checking_server),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (serverSize != null) {
                        Text(
                            text =
                                quantityString(
                                    CoreR.plurals.storage_episodes_server,
                                    serverSize.episodeCount,
                                    serverSize.episodeCount,
                                    formatSize(serverSize.bytes),
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (serverSize.bytes > 0) {
                            // How much of the whole show is on this device
                            LinearProgressIndicator(
                                progress = {
                                    (entry.localBytes.toFloat() / serverSize.bytes).coerceIn(0f, 1f)
                                },
                                modifier =
                                    Modifier.fillMaxWidth().padding(top = 4.dp).clip(CircleShape),
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_trash),
                    contentDescription = stringResource(CoreR.string.storage_delete_title),
                )
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
