package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.presentation.dummy.dummyEpisode
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.episodeTitle
import dev.jdtech.jellyfin.presentation.utils.rememberFormattedText
import dev.jdtech.jellyfin.utils.format

@Composable
fun EpisodeCard(
    episode: FindroidEpisode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Used when episodes from several seasons are listed together
    showSeasonNumber: Boolean = false,
    showReleaseDate: Boolean = false,
    // Shows the whole title, letting the row grow taller instead of cutting the title off
    fullTitle: Boolean = false,
) {
    val backgroundColor = MaterialTheme.colorScheme.background

    Row(
        modifier =
            modifier
                .then(if (fullTitle) Modifier.heightIn(min = 84.dp) else Modifier.height(84.dp))
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
    ) {
        // The thumbnail takes its size from the height, so it keeps 84dp when the row grows
        Box(modifier = if (fullTitle) Modifier.height(84.dp) else Modifier) {
            ItemPoster(
                item = episode,
                direction = Direction.HORIZONTAL,
                modifier = Modifier.clip(MaterialTheme.shapes.small),
            )
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(MaterialTheme.spacings.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            ) {
                if (episode.isDownloaded()) DownloadedBadge()
                if (episode.played) PlayedBadge()
            }
        }
        Spacer(Modifier.width(MaterialTheme.spacings.default / 2))
        Box(modifier = if (fullTitle) Modifier else Modifier.fillMaxHeight()) {
            Column {
                Text(
                    text = episodeTitle(episode, includeSeason = showSeasonNumber),
                    maxLines = if (fullTitle) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (showReleaseDate) {
                    episode.premiereDate?.let { premiereDate ->
                        Text(
                            text = premiereDate.format(),
                            modifier = Modifier.alpha(0.7f),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    text = rememberFormattedText(episode.overview),
                    modifier = Modifier.alpha(0.7f),
                    maxLines = if (fullTitle) 2 else 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Fades out the description where the fixed-height row cuts it off
            if (!fullTitle) {
                Canvas(
                    modifier =
                        Modifier.align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(MaterialTheme.spacings.default)
                ) {
                    drawRect(
                        brush =
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, backgroundColor),
                                startY = 0f,
                            )
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EpisodeCardPreview() {
    FindroidTheme { EpisodeCard(episode = dummyEpisode, onClick = {}) }
}
