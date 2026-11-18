package dev.jdtech.jellyfin.presentation.tube

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.tubearchivist.TaChannel
import kotlinx.coroutines.delay

/** One page on a phone, two or three side by side on wider screens, as on Findroid's home page. */
private val carouselPageSize =
    object : PageSize {
        override fun Density.calculateMainAxisPageSize(availableSpace: Int, pageSpacing: Int): Int {
            val pages =
                when {
                    availableSpace.toDp() >= 840.dp -> 3
                    availableSpace.toDp() >= 600.dp -> 2
                    else -> 1
                }
            return (availableSpace - (pages - 1) * pageSpacing) / pages
        }
    }

/**
 * Channels picked at random, turning to the next one every few seconds, like the carousel on
 * Findroid's home page. Each shows the channel's banner as its backdrop.
 */
@Composable
fun TaChannelCarousel(
    channels: List<TaChannel>,
    onChannelClick: (channelId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { channels.size })
    val dragged by pagerState.interactionSource.collectIsDraggedAsState()

    if (!dragged) {
        LaunchedEffect(pagerState) {
            while (true) {
                delay(AUTO_SCROLL_DELAY_MS)
                val next = if (pagerState.canScrollForward) pagerState.currentPage + 1 else 0
                pagerState.animateScrollToPage(next)
            }
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        pageSize = carouselPageSize,
        pageSpacing = MaterialTheme.spacings.medium,
        key = { page -> channels[page].id },
    ) { page ->
        val channel = channels[page]
        TaChannelCarouselItem(channel = channel, onClick = { onChannelClick(channel.id) })
    }
}

@Composable
private fun TaChannelCarouselItem(channel: TaChannel, onClick: () -> Unit) {
    val colorStops =
        arrayOf(
            0.0f to Color.Black.copy(alpha = 0.1f),
            0.5f to Color.Black.copy(alpha = 0.5f),
            1f to Color.Black.copy(alpha = 0.6f),
        )

    Box(
        modifier =
            Modifier.aspectRatio(1.77f)
                .clip(MaterialTheme.shapes.large)
                .clickable(onClick = onClick)
    ) {
        // The full banner as uploaded, which fits this shape, or else the strip shown on YouTube
        TaImage(path = channel.tvArtUrl ?: channel.bannerUrl, modifier = Modifier.fillMaxSize())
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = Brush.verticalGradient(colorStops = colorStops))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.spacings.default,
                        vertical = MaterialTheme.spacings.medium,
                    )
                    .align(Alignment.BottomStart),
        ) {
            channel.subs?.let { subs ->
                Text(
                    text = stringResource(CoreR.string.ta_subscribers, formatCount(subs)),
                    color = Color.LightGray,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = channel.name,
                color = Color.White,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

private const val AUTO_SCROLL_DELAY_MS = 5000L
