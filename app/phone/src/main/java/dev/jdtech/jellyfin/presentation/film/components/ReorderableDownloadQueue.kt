package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.jdtech.jellyfin.film.presentation.downloads.ActiveDownload
import dev.jdtech.jellyfin.presentation.theme.spacings
import java.util.UUID

/**
 * Queued downloads in queue order. Holding one lifts it so it can be dragged to another place;
 * letting go saves the new order with [onReorder].
 */
@Composable
fun ReorderableDownloadQueue(
    downloads: List<ActiveDownload>,
    onReorder: (List<UUID>) -> Unit,
    onClick: (ActiveDownload) -> Unit,
) {
    val ids = downloads.map { it.item.id }
    val byId = downloads.associateBy { it.item.id }
    val currentIds by rememberUpdatedState(ids)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val haptic = LocalHapticFeedback.current

    // The order shown, which changes live while dragging
    var order by remember { mutableStateOf(ids) }
    var draggedId by remember { mutableStateOf<UUID?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // Top and bottom of each row, so the dragged row knows when it passes a neighbour
    val bounds = remember { mutableMapOf<UUID, Pair<Float, Float>>() }

    // Follows the saved queue, except in the middle of a drag
    LaunchedEffect(ids) { if (draggedId == null) order = ids }

    fun finishDrag() {
        if (draggedId == null) return
        draggedId = null
        dragOffset = 0f
        if (order != currentIds) currentOnReorder(order)
        // Drops items that started downloading during the drag and adds new ones
        order = order.filter { it in currentIds } + currentIds.filter { it !in order }
    }

    fun onDrag(id: UUID, dragAmount: Float) {
        dragOffset += dragAmount
        val index = order.indexOf(id)
        val own = bounds[id] ?: return
        val center = (own.first + own.second) / 2 + dragOffset

        if (dragOffset > 0 && index in 0 until order.lastIndex) {
            val nextId = order[index + 1]
            val next = bounds[nextId] ?: return
            if (center > (next.first + next.second) / 2) {
                // The dragged row moves down by the height of the row it passes
                val shift = next.second - own.second
                order = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                bounds[id] = (own.first + shift) to (own.second + shift)
                bounds[nextId] = own.first to (own.first + (next.second - next.first))
                dragOffset -= shift
            }
        } else if (dragOffset < 0 && index > 0) {
            val previousId = order[index - 1]
            val previous = bounds[previousId] ?: return
            if (center < (previous.first + previous.second) / 2) {
                val shift = own.first - previous.first
                order = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                bounds[id] = (own.first - shift) to (own.second - shift)
                bounds[previousId] = (own.second - (previous.second - previous.first)) to own.second
                dragOffset += shift
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
        for (id in order) {
            val download = byId[id] ?: continue
            key(id) {
                val isDragged = id == draggedId
                Box(
                    modifier =
                        Modifier.zIndex(if (isDragged) 1f else 0f)
                            .onGloballyPositioned { coordinates ->
                                if (id != draggedId) {
                                    val top = coordinates.positionInParent().y
                                    bounds[id] = top to top + coordinates.size.height
                                }
                            }
                            .pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedId = id
                                        dragOffset = 0f
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        onDrag(id, dragAmount.y)
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
                        ActiveDownloadRow(
                            download = download,
                            onClick = { onClick(download) },
                            showDragHandle = true,
                        )
                    }
                }
            }
        }
    }
}
