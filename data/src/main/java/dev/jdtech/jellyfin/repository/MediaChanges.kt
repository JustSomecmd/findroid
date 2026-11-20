package dev.jdtech.jellyfin.repository

import java.util.UUID
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Tells pages when something they show was changed somewhere else in the app. Pages that stay open,
 * such as the main pages under the others, then show a deleted download or a video watched in the
 * player straight away, instead of keeping the copy they loaded until they load again.
 */
object MediaChanges {
    sealed interface Change

    /** Downloads on this phone changed: one was added, finished, cancelled or deleted. */
    data object Downloads : Change

    /**
     * Whether something was watched, or how far, changed. [itemId] is the Jellyfin item and
     * [videoId] the TubeArchivist video, each when known.
     */
    data class Watched(val itemId: UUID? = null, val videoId: String? = null) : Change

    private val _changes =
        MutableSharedFlow<Change>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    fun downloadsChanged() {
        _changes.tryEmit(Downloads)
    }

    fun watched(itemId: UUID? = null, videoId: String? = null) {
        _changes.tryEmit(Watched(itemId = itemId, videoId = videoId))
    }
}
