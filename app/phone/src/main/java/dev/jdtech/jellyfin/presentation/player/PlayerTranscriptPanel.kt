package dev.jdtech.jellyfin.presentation.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.jrwi.JrwiTranscripts
import dev.jdtech.jellyfin.presentation.jrwi.JrwiColors
import dev.jdtech.jellyfin.presentation.jrwi.JrwiTheme
import dev.jdtech.jellyfin.presentation.jrwi.JrwiTranscriptLines
import dev.jdtech.jellyfin.presentation.theme.spacings

/**
 * A JRWI episode's transcript beside the video, in the archiver's colours as the JRWI pages have
 * them: at the side when the screen is wider than tall, at the bottom otherwise, [size] wide or
 * tall. It takes up its own part of the screen rather than lying over the video, which the player
 * keeps whole in the rest. It slides in as long as [slideMillis], as the video makes room.
 */
@Composable
fun PlayerTranscriptPanel(
    open: Boolean,
    wide: Boolean,
    size: Dp,
    postId: Long?,
    transcripts: JrwiTranscripts,
    positionMs: () -> Long,
    onPlayFrom: (positionMs: Long) -> Unit,
    onClose: () -> Unit,
    slideMillis: Int,
) {
    BackHandler(enabled = open, onBack = onClose)
    val slide = tween<IntOffset>(durationMillis = slideMillis, easing = FastOutSlowInEasing)
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = open && postId != null,
            enter =
                if (wide) slideInHorizontally(slide) { it } else slideInVertically(slide) { it },
            exit =
                if (wide) slideOutHorizontally(slide) { it } else slideOutVertically(slide) { it },
            modifier = Modifier.align(if (wide) Alignment.CenterEnd else Alignment.BottomCenter),
        ) {
            JrwiTheme {
                Surface(
                    color = JrwiColors.Background,
                    modifier =
                        if (wide) Modifier.fillMaxHeight().width(size)
                        else Modifier.fillMaxWidth().height(size),
                ) {
                    // Clear of a notch, on the sides that reach the screen's edges
                    val edges =
                        if (wide) WindowInsetsSides.End + WindowInsetsSides.Vertical
                        else WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                    Column(
                        modifier =
                            Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(edges))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .background(JrwiColors.Header)
                                    .padding(
                                        start = MaterialTheme.spacings.medium,
                                        end = MaterialTheme.spacings.extraSmall,
                                    ),
                        ) {
                            Text(
                                text = stringResource(CoreR.string.jrwi_transcript),
                                style = MaterialTheme.typography.titleMedium,
                                color = JrwiColors.Text,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onClose) {
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_x),
                                    contentDescription =
                                        stringResource(CoreR.string.jrwi_transcript_hide),
                                    tint = JrwiColors.Text,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        HorizontalDivider(color = JrwiColors.LineSoft)
                        if (postId != null) {
                            JrwiTranscriptLines(
                                postId = postId,
                                transcripts = transcripts,
                                positionMs = positionMs,
                                onPlayFrom = onPlayFrom,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                contentPadding = PaddingValues(MaterialTheme.spacings.small),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
