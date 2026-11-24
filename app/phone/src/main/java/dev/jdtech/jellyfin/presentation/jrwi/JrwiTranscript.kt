package dev.jdtech.jellyfin.presentation.jrwi

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.jrwi.JrwiTranscript
import dev.jdtech.jellyfin.jrwi.JrwiTranscriptLine
import dev.jdtech.jellyfin.jrwi.JrwiTranscriptTimes
import dev.jdtech.jellyfin.jrwi.JrwiTranscripts
import dev.jdtech.jellyfin.presentation.theme.spacings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/**
 * How the lines are drawn: [accent] for the speakers, the line being spoken, its bar and the
 * buttons, with [onAccent] on them; [text] and [muted] for what's said and the notes; [lineShape]
 * for the line being spoken and [buttonShape] for the button back to it.
 */
@Immutable
data class TranscriptLook(
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val muted: Color,
    val lineShape: Shape,
    val buttonShape: Shape,
) {
    // The accent behind the line being spoken, as the web app's rgba(244, 228, 52, .14)
    val nowBackground: Color
        get() = accent.copy(alpha = NOW_ALPHA)

    companion object {
        /** The archiver's colours, as on the JRWI pages and in the audio player. */
        val Jrwi =
            TranscriptLook(
                accent = JrwiColors.Accent,
                onAccent = JrwiColors.AccentInk,
                text = JrwiColors.BodyText,
                muted = JrwiColors.Muted,
                lineShape = JrwiShape,
                buttonShape = JrwiShape,
            )
    }
}

/** What there is to show of an episode's transcript. */
private sealed interface TranscriptLoad {
    data object Loading : TranscriptLoad

    data object None : TranscriptLoad

    data object Failed : TranscriptLoad

    @Immutable data class Shown(val transcript: JrwiTranscript) : TranscriptLoad
}

/**
 * An episode's transcript as the web app shows it under its player (v0.23): each speaker's turn
 * with the name in the accent, notes on their own in muted italics, and the line being spoken lit
 * up with the accent's bar, kept a third of the way down. Scrolling the lines stops them following
 * until "Back to the line being spoken"; a tap on a line plays from there. [positionMs] is read
 * four times a second while the page is on screen, in the timeline of the post [postId], which the
 * lines are timed in.
 */
@Composable
fun JrwiTranscriptLines(
    postId: Long,
    transcripts: JrwiTranscripts,
    positionMs: () -> Long,
    onPlayFrom: (positionMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    contentPadding: PaddingValues = PaddingValues(MaterialTheme.spacings.small),
    look: TranscriptLook = TranscriptLook.Jrwi,
) {
    var attempt by remember(postId) { mutableIntStateOf(0) }
    val load by
        produceState<TranscriptLoad>(TranscriptLoad.Loading, postId, attempt) {
            value = TranscriptLoad.Loading
            value =
                try {
                    transcripts.get(postId)?.let { TranscriptLoad.Shown(it) } ?: TranscriptLoad.None
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    TranscriptLoad.Failed
                }
        }
    Box(modifier = modifier) {
        when (val shown = load) {
            TranscriptLoad.Loading ->
                Message(stringResource(CoreR.string.jrwi_transcript_loading), look) {
                    CircularProgressIndicator(
                        color = look.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                }
            TranscriptLoad.None -> Message(stringResource(CoreR.string.jrwi_transcript_none), look)
            TranscriptLoad.Failed ->
                Message(stringResource(CoreR.string.jrwi_transcript_failed), look) {
                    TextButton(onClick = { attempt++ }) {
                        Text(stringResource(CoreR.string.retry), color = look.accent)
                    }
                }
            is TranscriptLoad.Shown ->
                TranscriptList(
                    transcript = shown.transcript,
                    positionMs = positionMs,
                    onPlayFrom = onPlayFrom,
                    textStyle = textStyle,
                    contentPadding = contentPadding,
                    look = look,
                )
        }
    }
}

@Composable
private fun Message(
    text: String,
    look: TranscriptLook,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement =
            Arrangement.spacedBy(MaterialTheme.spacings.small, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize().padding(MaterialTheme.spacings.medium),
    ) {
        extra?.invoke()
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = look.muted)
    }
}

@Composable
private fun TranscriptList(
    transcript: JrwiTranscript,
    positionMs: () -> Long,
    onPlayFrom: (Long) -> Unit,
    textStyle: TextStyle,
    contentPadding: PaddingValues,
    look: TranscriptLook,
) {
    val times = remember(transcript) { JrwiTranscriptTimes(transcript) }
    val lines = transcript.lines
    val listState = rememberLazyListState()
    var current by remember(transcript) { mutableIntStateOf(-1) }
    var follow by remember(transcript) { mutableStateOf(true) }
    // Whether the list was brought to the line yet, which the first time is done without moving
    var placed by remember(transcript) { mutableStateOf(false) }
    val position by rememberUpdatedState(positionMs)

    // The line being said, only while the page is on screen
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(times, lifecycle) {
        if (!times.any) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                current = times.lineAt(position() / 1000.0)
                delay(POSITION_EVERY_MS)
            }
        }
    }
    // Scrolling the lines by hand stops them following along
    LaunchedEffect(listState, times) {
        if (!times.any) return@LaunchedEffect
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) follow = false
        }
    }
    LaunchedEffect(current, follow) {
        if (!follow || current < 0) return@LaunchedEffect
        listState.bringToThird(current, animate = placed)
        placed = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!transcript.aligned) {
            Text(
                text = stringResource(CoreR.string.jrwi_transcript_untimed),
                style = MaterialTheme.typography.bodySmall,
                color = look.muted,
                modifier =
                    Modifier.padding(
                        horizontal = MaterialTheme.spacings.medium,
                        vertical = MaterialTheme.spacings.small,
                    ),
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(lines, contentType = { _, line -> line.speaker == null }) { index, line
                    ->
                    TranscriptLine(
                        line = line,
                        timed = transcript.aligned && line.start != null,
                        untimed = transcript.aligned && line.start == null,
                        now = index == current,
                        textStyle = textStyle,
                        look = look,
                        onClick = {
                            line.start?.let { start ->
                                follow = true
                                onPlayFrom((start * 1000).toLong())
                            }
                        },
                    )
                }
            }
            // The one of its own, not the column's around the list
            androidx.compose.animation.AnimatedVisibility(
                visible = !follow && times.any,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier =
                    Modifier.align(Alignment.BottomEnd).padding(MaterialTheme.spacings.small),
            ) {
                Button(
                    onClick = { follow = true },
                    shape = look.buttonShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = look.accent,
                            contentColor = look.onAccent,
                        ),
                ) {
                    Text(stringResource(CoreR.string.jrwi_transcript_follow))
                }
            }
        }
    }
}

/**
 * A speaker's turn: the name in bold in the accent, then what they said; a note of its own in muted
 * italics; a line that couldn't be placed dimmed. The line being spoken has the accent behind it
 * and its bar at the start, as the web app's `.tl.now`.
 */
@Composable
private fun TranscriptLine(
    line: JrwiTranscriptLine,
    timed: Boolean,
    untimed: Boolean,
    now: Boolean,
    textStyle: TextStyle,
    look: TranscriptLook,
    onClick: () -> Unit,
) {
    val text =
        remember(line, look) {
            buildAnnotatedString {
                val speaker = line.speaker
                if (speaker != null) {
                    withStyle(SpanStyle(color = look.accent, fontWeight = FontWeight.Bold)) {
                        append(speaker)
                    }
                    append(" ")
                    append(line.text)
                } else {
                    withStyle(SpanStyle(color = look.muted, fontStyle = FontStyle.Italic)) {
                        append(line.text)
                    }
                }
            }
        }
    Text(
        text = text,
        style = textStyle.copy(lineHeight = 1.45.em),
        color = look.text.copy(alpha = if (untimed) UNTIMED_ALPHA else 1f),
        modifier =
            Modifier.fillMaxWidth()
                .clip(look.lineShape)
                .then(
                    if (now)
                        Modifier.background(look.nowBackground).drawBehind {
                            drawRect(look.accent, size = Size(NOW_BAR.toPx(), size.height))
                        }
                    else Modifier
                )
                .then(
                    if (timed) {
                        Modifier.clickable(
                            onClickLabel = stringResource(CoreR.string.jrwi_transcript_play_from),
                            onClick = onClick,
                        )
                    } else Modifier
                )
                .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * Brings the line at [index] a third of the way down the list: gliding there when it's on screen
 * and [animate], or else straight there.
 */
private suspend fun LazyListState.bringToThird(index: Int, animate: Boolean) {
    val info = layoutInfo
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    if (viewport <= 0) {
        scrollToItem(index)
        return
    }
    val target = viewport / 3
    val visible = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (visible != null && animate) {
        animateScrollBy((visible.offset - target).toFloat())
    } else {
        scrollToItem(index)
        // The lines before it above, as far as there are any
        scrollBy(-target.toFloat())
    }
}

private const val NOW_ALPHA = 0.14f
private val NOW_BAR = 3.dp
private const val UNTIMED_ALPHA = 0.65f
private const val POSITION_EVERY_MS = 250L
