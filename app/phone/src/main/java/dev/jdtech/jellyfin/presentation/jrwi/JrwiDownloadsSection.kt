package dev.jdtech.jellyfin.presentation.jrwi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.jrwi.JrwiDownloadEntry
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.tube.formatBytes

/**
 * The JRWI episodes whose audio is on the phone, or on its way, on the Downloads page. Tapping one
 * opens its page, which works without a connection from what was kept with the audio.
 */
@Composable
fun JrwiDownloadsSection(
    entries: List<JrwiDownloadEntry>,
    onClick: (itemId: Long) -> Unit,
    onRemove: (itemId: Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
        Text(
            text = stringResource(CoreR.string.jrwi_downloads),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = MaterialTheme.spacings.small),
        )
        entries.forEach { entry ->
            JrwiDownloadListRow(
                entry = entry,
                onClick = { onClick(entry.download.itemId) },
                onRemove = { onRemove(entry.download.itemId) },
            )
        }
    }
}

@Composable
private fun JrwiDownloadListRow(
    entry: JrwiDownloadEntry,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val download = entry.download
    val progress = entry.progress
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
        modifier =
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = entry.picture,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier.width(120.dp)
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = download.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    when {
                        download.done ->
                            listOfNotNull(download.showName, download.size?.let { formatBytes(it) })
                                .joinToString(" · ")
                        download.failed -> stringResource(CoreR.string.jrwi_download_failed)
                        progress != null ->
                            stringResource(
                                CoreR.string.jrwi_download_running,
                                (progress * 100).toInt(),
                            )
                        else -> stringResource(CoreR.string.jrwi_download_waiting)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!download.done && !download.failed) {
                val bar = Modifier.fillMaxWidth().padding(top = 4.dp)
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = bar)
                } else {
                    LinearProgressIndicator(modifier = bar)
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(painterResource(CoreR.drawable.ic_more_vertical), contentDescription = null)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (download.done) CoreR.string.jrwi_download_remove
                                else CoreR.string.jrwi_download_cancel
                            )
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
}
