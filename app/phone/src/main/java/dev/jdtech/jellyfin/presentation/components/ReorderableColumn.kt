package dev.jdtech.jellyfin.presentation.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A column whose rows can be put in another order. Holding a row lifts it so it can be dragged to
 * another place; letting go saves the new order with [onReorder].
 */
@Composable
fun <K : Any> ReorderableColumn(
    keys: List<K>,
    onReorder: (List<K>) -> Unit,
    spacing: Dp,
    modifier: Modifier = Modifier,
    row: @Composable (key: K) -> Unit,
) {
    val currentKeys by rememberUpdatedState(keys)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val haptic = LocalHapticFeedback.current

    // The order shown, which changes live while dragging
    var order by remember { mutableStateOf(keys) }
    var draggedKey by remember { mutableStateOf<K?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // Top and bottom of each row, so the dragged row knows when it passes a neighbour
    val bounds = remember { mutableMapOf<K, Pair<Float, Float>>() }

    // Follows the saved order, except in the middle of a drag
    LaunchedEffect(keys) { if (draggedKey == null) order = keys }

    fun finishDrag() {
        if (draggedKey == null) return
        draggedKey = null
        dragOffset = 0f
        if (order != currentKeys) currentOnReorder(order)
        // Drops rows that went away during the drag and adds new ones
        order = order.filter { it in currentKeys } + currentKeys.filter { it !in order }
    }

    fun onDrag(dragged: K, dragAmount: Float) {
        dragOffset += dragAmount
        val index = order.indexOf(dragged)
        val own = bounds[dragged] ?: return
        val center = (own.first + own.second) / 2 + dragOffset

        if (dragOffset > 0 && index in 0 until order.lastIndex) {
            val next = order[index + 1]
            val nextBounds = bounds[next] ?: return
            if (center > (nextBounds.first + nextBounds.second) / 2) {
                // The dragged row moves down by the height of the row it passes
                val shift = nextBounds.second - own.second
                order = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                bounds[dragged] = (own.first + shift) to (own.second + shift)
                bounds[next] = own.first to (own.first + (nextBounds.second - nextBounds.first))
                dragOffset -= shift
            }
        } else if (dragOffset < 0 && index > 0) {
            val previous = order[index - 1]
            val previousBounds = bounds[previous] ?: return
            if (center < (previousBounds.first + previousBounds.second) / 2) {
                val shift = own.first - previousBounds.first
                order = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                bounds[dragged] = (own.first - shift) to (own.second - shift)
                bounds[previous] =
                    (own.second - (previousBounds.second - previousBounds.first)) to own.second
                dragOffset += shift
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        for (rowKey in order) {
            if (rowKey !in keys) continue
            key(rowKey) {
                val isDragged = rowKey == draggedKey
                Box(
                    modifier =
                        Modifier.zIndex(if (isDragged) 1f else 0f)
                            .onGloballyPositioned { coordinates ->
                                if (rowKey != draggedKey) {
                                    val top = coordinates.positionInParent().y
                                    bounds[rowKey] = top to top + coordinates.size.height
                                }
                            }
                            .pointerInput(rowKey) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedKey = rowKey
                                        dragOffset = 0f
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        onDrag(rowKey, dragAmount.y)
                                    },
                                    onDragEnd = { finishDrag() },
                                    onDragCancel = { finishDrag() },
                                )
                            }
                ) {
                    // Only the drawing moves with the finger; the layout spot stays put
                    Box(
                        modifier =
                            Modifier.graphicsLayer {
                                translationY = if (isDragged) dragOffset else 0f
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
