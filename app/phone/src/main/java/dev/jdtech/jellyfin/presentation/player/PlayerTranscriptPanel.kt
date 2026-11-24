package dev.jdtech.jellyfin.presentation.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.jrwi.JrwiTranscripts
import dev.jdtech.jellyfin.presentation.jrwi.JrwiTranscriptLines
import dev.jdtech.jellyfin.presentation.jrwi.TranscriptLook
import dev.jdtech.jellyfin.presentation.theme.spacings

/**
 * A JRWI episode's transcript beside the video, in the player's own look: black beside the video,
 * white text, the player's [accent] (with [onAccent] on it) for the speakers and the line being
 * spoken, as on its progress bar, and a bar as tall as the player's top bar with a round white
 * button, as its buttons are. At the side when the screen is wider than tall, at the bottom
 * otherwise, [size] wide or tall. It takes up its own part of the screen rather than lying over the
 * video, which the player keeps whole in the rest. It slides in as long as [slideMillis], as the
 * video makes room. While the player's screen is [locked], it takes no taps or scrolls.
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
    accent: Color,
    onAccent: Color,
    locked: Boolean = false,
) {
    BackHandler(enabled = open, onBack = onClose)
    val slide = tween<IntOffset>(durationMillis = slideMillis, easing = FastOutSlowInEasing)
    val look =
        remember(accent, onAccent) {
            TranscriptLook(
                accent = accent,
                onAccent = onAccent,
                text = Color.White.copy(alpha = TEXT_ALPHA),
                muted = Color.White.copy(alpha = MUTED_ALPHA),
                lineShape = RoundedCornerShape(LINE_CORNER),
                // Pill-shaped, as the player's Up next buttons
                buttonShape = CircleShape,
            )
        }
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = open && postId != null,
            enter =
                if (wide) slideInHorizontally(slide) { it } else slideInVertically(slide) { it },
            exit =
                if (wide) slideOutHorizontally(slide) { it } else slideOutVertically(slide) { it },
            modifier = Modifier.align(if (wide) Alignment.CenterEnd else Alignment.BottomCenter),
        ) {
            Surface(
                color = Color.Black,
                contentColor = Color.White,
                modifier =
                    if (wide) Modifier.fillMaxHeight().width(size)
                    else Modifier.fillMaxWidth().height(size),
            ) {
                // Clear of a notch, on the sides that reach the screen's edges
                val edges =
                    if (wide) WindowInsetsSides.End + WindowInsetsSides.Vertical
                    else WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                Column(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(edges))
                ) {
                    // As the player's top bar: as far from the edge, and as tall, so at the side
                    // the two read as one bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(BAR_MARGIN),
                    ) {
                        Text(
                            text = stringResource(CoreR.string.jrwi_transcript),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            modifier =
                                Modifier.weight(1f).padding(start = MaterialTheme.spacings.medium),
                        )
                        IconButton(onClick = onClose, modifier = Modifier.size(BUTTON_SIZE)) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_x),
                                contentDescription =
                                    stringResource(CoreR.string.jrwi_transcript_hide),
                                tint = Color.White,
                                modifier = Modifier.size(ICON_SIZE),
                            )
                        }
                    }
                    if (postId != null) {
                        JrwiTranscriptLines(
                            postId = postId,
                            transcripts = transcripts,
                            positionMs = positionMs,
                            onPlayFrom = onPlayFrom,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            contentPadding =
                                PaddingValues(
                                    start = MaterialTheme.spacings.small,
                                    end = MaterialTheme.spacings.small,
                                    bottom = MaterialTheme.spacings.small,
                                ),
                            look = look,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                // With the player's screen locked, taps and scrolls on the lines do nothing, as
                // on the rest of the player; the lines still follow what plays
                if (locked) {
                    Box(
                        modifier =
                            Modifier.fillMaxSize().pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial)
                                            .changes
                                            .forEach { it.consume() }
                                    }
                                }
                            }
                    )
                }
            }
        }
    }
}

// The player's top bar sits this far in, and its buttons are this big round their icons
private val BAR_MARGIN = 8.dp
private val BUTTON_SIZE = 56.dp
private val ICON_SIZE = 24.dp
private val LINE_CORNER = 8.dp
private const val TEXT_ALPHA = 0.9f
private const val MUTED_ALPHA = 0.6f
