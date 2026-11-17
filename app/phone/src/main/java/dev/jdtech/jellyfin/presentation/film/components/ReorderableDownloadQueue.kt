package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import dev.jdtech.jellyfin.film.presentation.downloads.ActiveDownload
import dev.jdtech.jellyfin.presentation.components.ReorderableColumn
import dev.jdtech.jellyfin.presentation.theme.spacings
import java.util.UUID

/**
 * Queued downloads in queue order. Holding one lifts it so it can be dragged to another place;
 * letting go saves the new order with [onReorder].
 */
@Composable
fun ReorderableDownloadQueue(
    downloads: List<ActiveDownload>,
    onReorder: (List<UUID>) -> Unit,
    onClick: (ActiveDownload) -> Unit,
    // The list the queue sits in, scrolled while a row is dragged near the edge
    scrollState: ScrollableState? = null,
) {
    val byId = downloads.associateBy { it.item.id }

    ReorderableColumn(
        keys = downloads.map { it.item.id },
        onReorder = onReorder,
        spacing = MaterialTheme.spacings.small,
        scrollState = scrollState,
    ) { id ->
        val download = byId[id]
        if (download != null) {
            ActiveDownloadRow(download = download, onClick = { onClick(download) })
        }
    }
}
