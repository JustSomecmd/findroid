package dev.jdtech.jellyfin.presentation.film.components

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.downloads.ActiveDownload
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.LocalEpisodeTitleFirst
import dev.jdtech.jellyfin.presentation.utils.episodeTitle
import dev.jdtech.jellyfin.utils.DownloadStatus
import kotlin.math.roundToInt

/** A download that hasn't finished, with its status and progress. Tapping opens the item. */
@Composable
fun ActiveDownloadRow(
    download: ActiveDownload,
    onClick: () -> Unit,
) {
    val item = download.item
    val info = download.info

    val episodeTitleFirst = LocalEpisodeTitleFirst.current
    val title =
        when (item) {
            is FindroidEpisode ->
                if (episodeTitleFirst) episodeTitle(item, includeSeason = true) else item.seriesName
            else -> item.name
        }
    val subtitle =
        (item as? FindroidEpisode)?.let {
            if (episodeTitleFirst) it.seriesName else episodeTitle(it, includeSeason = true)
        }

    val status =
        when (info.status) {
            DownloadStatus.RUNNING -> stringResource(CoreR.string.download_downloading)
            DownloadStatus.PENDING -> stringResource(CoreR.string.download_pending)
            DownloadStatus.QUEUED -> stringResource(CoreR.string.download_queued)
            DownloadStatus.PAUSED -> stringResource(CoreR.string.download_paused)
            DownloadStatus.FAILED -> stringResource(CoreR.string.download_failed)
            else -> ""
        }
    val percent = (info.progress * 100).roundToInt()
    val speed =
        if (info.status == DownloadStatus.RUNNING && info.bytesPerSecond >= 0) {
            stringResource(
                CoreR.string.download_speed,
                Formatter.formatShortFileSize(LocalContext.current, info.bytesPerSecond),
            )
        } else {
            null
        }

    Row(
        modifier =
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemPoster(
            item = item,
            direction = Direction.HORIZONTAL,
            modifier = Modifier.height(64.dp).clip(MaterialTheme.shapes.small),
        )
        Spacer(Modifier.width(MaterialTheme.spacings.medium))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
        ) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    modifier = Modifier.alpha(0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = listOfNotNull(status, "$percent%", speed).joinToString(" · "),
                color =
                    if (info.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            if (info.status == DownloadStatus.PENDING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { info.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
