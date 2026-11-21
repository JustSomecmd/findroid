package dev.jdtech.jellyfin.presentation.jrwi

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.spacings
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The archiver sheet on the JRWI tab: how far it's pulled up, from 0 when it's away to 1 when it's
 * all the way up. The bottom bar pulls it up, and its own handle and list push it back down.
 */
@Stable
class JrwiSheetState(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f)
        private set

    // Where it's going or resting, to tell open from closed while it moves
    private var target by mutableFloatStateOf(0f)

    /** How tall the sheet is when it's all the way up, in pixels, once it's been laid out. */
    var heightPx by mutableFloatStateOf(0f)
        internal set

    private var job: Job? = null

    val isOpen: Boolean
        get() = target > 0.5f

    /** Shows the sheet with true, puts it away with false, or with null does the other. */
    fun show(open: Boolean?) {
        val value = open ?: !isOpen
        animateTo(if (value) 1f else 0f)
    }

    fun close() = animateTo(0f)

    /** Puts it away at once, as when the tab is left. */
    fun closeNow() {
        job?.cancel()
        target = 0f
        progress = 0f
    }

    /** Moves it with a finger: [deltaPx] down puts it away, up pulls it out. */
    fun dragBy(deltaPx: Float) {
        if (heightPx <= 0f) return
        job?.cancel()
        progress = (progress - deltaPx / heightPx).coerceIn(0f, 1f)
        target = progress
    }

    /** Lets it go after a drag: up or down with a flick, else to the nearer end. */
    fun settle(velocityPx: Float) {
        val open =
            when {
                velocityPx < -FLING_PX_PER_SECOND -> true
                velocityPx > FLING_PX_PER_SECOND -> false
                else -> progress > 0.5f
            }
        val velocity = if (heightPx > 0f) -velocityPx / heightPx else 0f
        animateTo(if (open) 1f else 0f, velocity)
    }

    private fun animateTo(value: Float, velocity: Float = 0f) {
        job?.cancel()
        target = value
        if (progress == value) return
        job = scope.launch {
            animate(
                initialValue = progress,
                targetValue = value,
                initialVelocity = velocity,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
            ) { current, _ ->
                progress = current.coerceIn(0f, 1f)
            }
            progress = value
        }
    }

    private companion object {
        const val FLING_PX_PER_SECOND = 1200f
    }
}

@Composable
fun rememberJrwiSheetState(): JrwiSheetState {
    val scope = rememberCoroutineScope()
    return remember(scope) { JrwiSheetState(scope) }
}

/**
 * Pulls the archiver sheet up with a swipe up from the bottom bar, while [enabled]. A tap, or a
 * swipe any other way, is left to the bar.
 */
fun Modifier.jrwiSheetSwipe(sheet: JrwiSheetState, enabled: Boolean): Modifier =
    if (!enabled) this
    else
        pointerInput(sheet) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val velocity = VelocityTracker()
                velocity.addPointerInputChange(down)
                var upwards = false
                val start =
                    awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                        // Only up; the bar's tap is called off by the consumed move
                        if (over < 0) {
                            upwards = true
                            change.consume()
                            sheet.dragBy(over)
                        }
                    }
                if (start == null || !upwards) return@awaitEachGesture
                velocity.addPointerInputChange(start)
                verticalDrag(start.id) { change ->
                    sheet.dragBy(change.positionChange().y)
                    velocity.addPointerInputChange(change)
                    change.consume()
                }
                sheet.settle(velocity.calculateVelocity().y)
            }
        }

/**
 * The archiver's page as a sheet over the JRWI tab, about half as tall as the tab, in purple. It
 * comes up out of the bottom bar, and goes back down with its handle, a swipe down the list once
 * it's at the top, a tap above it, or Back.
 */
@Composable
fun JrwiServerSheet(sheet: JrwiSheetState, modifier: Modifier = Modifier) {
    val shown = sheet.progress > 0f
    BackHandler(enabled = sheet.isOpen) { sheet.close() }
    Box(
        modifier =
            modifier.fillMaxSize().clipToBounds().onSizeChanged {
                sheet.heightPx = it.height * SHEET_PART
            }
    ) {
        if (!shown) return@Box
        // Dims the tab, and puts the sheet away when tapped
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = SCRIM_ALPHA * sheet.progress))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { sheet.close() },
                    )
        )
        val listScroll =
            remember(sheet) {
                object : NestedScrollConnection {
                    override fun onPreScroll(
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        // Partly down, moving up takes the sheet back up before the list
                        if (
                            source == NestedScrollSource.UserInput &&
                                available.y < 0 &&
                                sheet.progress < 1f
                        ) {
                            sheet.dragBy(available.y)
                            return Offset(0f, available.y)
                        }
                        return Offset.Zero
                    }

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        // Down past the top of the list takes the sheet down
                        if (source == NestedScrollSource.UserInput && available.y > 0) {
                            sheet.dragBy(available.y)
                            return Offset(0f, available.y)
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (sheet.progress < 1f) {
                            sheet.settle(available.y)
                            return available
                        }
                        return Velocity.Zero
                    }
                }
            }
        JrwiSheetTheme {
            Column(
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(SHEET_PART)
                        .offset {
                            IntOffset(0, ((1f - sheet.progress) * sheet.heightPx).roundToInt())
                        }
                        .clip(SheetShape)
                        .background(MaterialTheme.colorScheme.background)
                        // Taps on the sheet stay on it, rather than reaching the dimmed tab
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
            ) {
                // The handle and title, to drag it by
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier.fillMaxWidth()
                            .draggable(
                                state = rememberDraggableState { sheet.dragBy(it) },
                                orientation = Orientation.Vertical,
                                onDragStopped = { velocity -> sheet.settle(velocity) },
                            )
                            .padding(
                                start = MaterialTheme.spacings.medium,
                                end = MaterialTheme.spacings.medium,
                                top = MaterialTheme.spacings.small,
                            ),
                ) {
                    Box(
                        Modifier.width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
                        modifier =
                            Modifier.fillMaxWidth().padding(top = MaterialTheme.spacings.small),
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_server),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(CoreR.string.jrwi_archiver),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                // Only while it's out, so the archiver is asked only then
                JrwiServerContent(modifier = Modifier.fillMaxSize().nestedScroll(listScroll))
            }
        }
    }
}

// About half of the tab, as asked
private const val SHEET_PART = 0.5f
private const val SCRIM_ALPHA = 0.5f
private val SheetShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
