package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.presentation.dummy.dummyMovie
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme

/**
 * How far [item] was played, as a bar along the width it's given. It's a share of the card's real
 * width, so it's right however wide the card turns out: in a grid, a wide card is squeezed to its
 * column, narrower than its usual 260 dp.
 */
@Composable
fun ProgressBar(item: FindroidItem, modifier: Modifier = Modifier) {
    val played = playedShare(item.playbackPositionTicks, item.runtimeTicks)
    if (played <= 0f) return
    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier.height(4.dp)
                    .fillMaxWidth(played)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/** How much of [length] [position] is, from 0 to 1; 0 when the length isn't known. */
private fun playedShare(position: Long, length: Long): Float =
    if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f

@Preview(showBackground = true)
@Composable
private fun ProgressBarPreview() {
    FindroidTheme { ProgressBar(item = dummyMovie, modifier = Modifier.width(142.dp)) }
}
