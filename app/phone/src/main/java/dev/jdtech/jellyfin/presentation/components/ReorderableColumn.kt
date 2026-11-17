package dev.jdtech.jellyfin.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A column whose rows can be put in another order. Holding a row lifts it, and it follows the
 * finger for as far as it is dragged while the other rows slide out of the way. Letting go drops it
 * into the gap and saves the new order with [onReorder].
 *
 * The rows keep their places in the layout during a drag and are only moved on screen, so the drag
 * isn't interrupted by the layout changing under the finger.
 *
 * When the column sits in a scrolling list, pass that list's [scrollState]: holding a row near the
 * top or bottom edge of the visible area then scrolls the list, so a row can be taken past what
 * fits on screen.
 */
@Composable
fun <K : Any> ReorderableColumn(
    keys: List<K>,
    onReorder: (List<K>) -> Unit,
    spacing: Dp,
    modifier: Modifier = Modifier,
    scrollState: ScrollableState? = null,
    row: @Composable (key: K) -> Unit,
) {
    val currentKeys by rememberUpdatedState(keys)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val spacingPx by rememberUpdatedState(with(LocalDensity.current) { spacing.toPx() })
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var order by remember { mutableStateOf(keys) }
    var draggedKey by remember { mutableStateOf<K?>(null) }
    // How far the dragged row has moved from its own spot
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // Where the dragged row would land if let go now
    var targetIndex by remember { mutableIntStateOf(-1) }
    var settling by remember { mutableStateOf(false) }
    // Changes after every drop, so the rows' slide animations start fresh from the new layout
    var generation by remember { mutableIntStateOf(0) }
    // Top and bottom of each row in the layout
    val bounds = remember { mutableMapOf<K, Pair<Float, Float>>() }
    // Where the column is on screen, read live while scrolling
    val columnPosition = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val density = LocalDensity.current

    // Follows the saved order, except in the middle of a drag
    LaunchedEffect(keys) { if (draggedKey == null) order = keys }

    fun visibleOrder() = order.filter { it in currentKeys }

    fun height(rowKey: K) = bounds[rowKey]?.let { it.second - it.first } ?: 0f

    /** Distance from the spot at [from] to the spot the row takes when dropped at [target]. */
    fun slotOffset(rows: List<K>, from: Int, target: Int): Float {
        var offset = 0f
        if (target > from) {
            for (i in from + 1..target) offset += height(rows[i]) + spacingPx
        } else {
            for (i in target until from) offset -= height(rows[i]) + spacingPx
        }
        return offset
    }

    fun startDrag(rowKey: K) {
        if (draggedKey != null || settling) return
        draggedKey = rowKey
        dragOffset = 0f
        targetIndex = visibleOrder().indexOf(rowKey)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun drag(rowKey: K, amount: Float) {
        if (draggedKey != rowKey || settling) return
        val rows = visibleOrder()
        // The row may have left the list during the drag
        if (rowKey !in rows) return
        val own = bounds[rowKey] ?: return
        val first = bounds[rows.first()] ?: return
        val last = bounds[rows.last()] ?: return

        // Stays within the list
        dragOffset =
            (dragOffset + amount).coerceIn(first.first - own.first, last.second - own.second)

        // The row lands after every other row whose middle it has passed
        val center = (own.first + own.second) / 2 + dragOffset
        val target = rows.count { other ->
            other != rowKey && bounds[other]?.let { (it.first + it.second) / 2 < center } == true
        }
        if (target != targetIndex) {
            targetIndex = target
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun endDrag(rowKey: K) {
        if (draggedKey != rowKey || settling) return
        settling = true
        scope.launch {
            val rows = visibleOrder()
            val from = rows.indexOf(rowKey)
            val target = targetIndex.coerceIn(0, maxOf(rows.lastIndex, 0))

            // Glides into the gap before the layout changes
            if (from >= 0) {
                animate(
                    initialValue = dragOffset,
                    targetValue = slotOffset(rows, from, target),
                    animationSpec = tween(SETTLE_MS),
                ) { value, _ ->
                    dragOffset = value
                }
            }

            val newOrder =
                if (from >= 0 && target != from) {
                    rows.toMutableList().apply { add(target, removeAt(from)) }
                } else {
                    rows
                }
            // Also adds rows that appeared during the drag
            order = newOrder + currentKeys.filter { it !in newOrder }
            draggedKey = null
            dragOffset = 0f
            targetIndex = -1
            generation++
            settling = false
            if (order != currentKeys) currentOnReorder(order)
        }
    }

    // Scrolls the list while the dragged row is held near an edge of the visible area. The row
    // moves along with the list so it stays under the finger.
    LaunchedEffect(draggedKey, scrollState) {
        val state = scrollState ?: return@LaunchedEffect
        val dragged = draggedKey ?: return@LaunchedEffect
        val edge = with(density) { AUTO_SCROLL_EDGE.toPx() }
        val maxStep = with(density) { AUTO_SCROLL_MAX_STEP.toPx() }

        while (isActive && !settling) {
            withFrameNanos {}
            val coordinates = columnPosition[0]?.takeIf { it.isAttached } ?: continue
            val own = bounds[dragged] ?: continue

            val columnTop = coordinates.positionInWindow().y
            val columnBottom = columnTop + coordinates.size.height
            // The part of the column that can be seen
            val visible = coordinates.boundsInWindow()
            val rowCenter = columnTop + (own.first + own.second) / 2 + dragOffset

            val step =
                when {
                    // More of the list below and the row is near the bottom
                    columnBottom > visible.bottom + 1 && rowCenter > visible.bottom - edge ->
                        maxStep * ((rowCenter - (visible.bottom - edge)) / edge).coerceIn(0f, 1f)
                    // More of the list above and the row is near the top
                    columnTop < visible.top - 1 && rowCenter < visible.top + edge ->
                        -maxStep * (((visible.top + edge) - rowCenter) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
            if (step != 0f) {
                val scrolled = state.scrollBy(step)
                if (scrolled != 0f) drag(dragged, scrolled)
            }
        }
    }

    val rows = order.filter { it in keys }
    val draggedIndex = draggedKey?.let { rows.indexOf(it) } ?: -1
    val gap = draggedKey?.let { height(it) + spacingPx } ?: 0f

    Column(
        modifier = modifier.onGloballyPositioned { columnPosition[0] = it },
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        rows.forEachIndexed { index, rowKey ->
            key(rowKey) {
                val isDragged = rowKey == draggedKey

                // Rows between the dragged row's spot and where it would land make room for it
                val shift =
                    when {
                        draggedIndex < 0 || isDragged -> 0f
                        draggedIndex < targetIndex && index in draggedIndex + 1..targetIndex -> -gap
                        targetIndex < draggedIndex && index in targetIndex until draggedIndex -> gap
                        else -> 0f
                    }
                val shiftAnimation = remember(generation) { Animatable(0f) }
                LaunchedEffect(shift, generation) {
                    shiftAnimation.animateTo(shift, tween(SHIFT_MS))
                }

                Box(
                    modifier =
                        Modifier.zIndex(if (isDragged) 1f else 0f)
                            .onGloballyPositioned { coordinates ->
                                val top = coordinates.positionInParent().y
                                bounds[rowKey] = top to top + coordinates.size.height
                            }
                            .pointerInput(rowKey) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { startDrag(rowKey) },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        drag(rowKey, dragAmount.y)
                                    },
                                    onDragEnd = { endDrag(rowKey) },
                                    onDragCancel = { endDrag(rowKey) },
                                )
                            }
                ) {
                    // Only the drawing moves; the layout spot stays put until the drop
                    Box(
                        modifier =
                            Modifier.graphicsLayer {
                                translationY = if (isDragged) dragOffset else shiftAnimation.value
                                shadowElevation = if (isDragged) 8.dp.toPx() else 0f
                                scaleX = if (isDragged) 1.02f else 1f
                                scaleY = if (isDragged) 1.02f else 1f
                            }
                    ) {
                        row(rowKey)
                    }
                }
            }
        }
    }
}

private const val SETTLE_MS = 150
// How close to an edge of the visible area the dragged row must be to scroll the list
private val AUTO_SCROLL_EDGE = 72.dp
// The fastest the list scrolls, per frame, when the row is right at the edge
private val AUTO_SCROLL_MAX_STEP = 14.dp
private const val SHIFT_MS = 150
