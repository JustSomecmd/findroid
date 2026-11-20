package dev.jdtech.jellyfin.film.presentation.tube

import dev.jdtech.jellyfin.repository.MediaChanges
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaPlayer
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps a page's videos in step when they're watched elsewhere in the app, such as in the player or
 * on a video's page. Each video watched that [shows] is asked for again, and [onChange] gets its
 * new watched state and position from TubeArchivist. Runs as long as [scope].
 */
fun TaApi.followWatchedVideos(
    scope: CoroutineScope,
    shows: (videoId: String) -> Boolean,
    onChange: (videoId: String, player: TaPlayer) -> Unit,
) {
    val pending = mutableSetOf<String>()
    scope.launch {
        MediaChanges.changes
            .filterIsInstance<MediaChanges.Watched>()
            .mapNotNull { it.videoId }
            .collectLatest { videoId ->
                pending += videoId
                // A stop is often reported more than once, so they're gathered first
                delay(WATCHED_SETTLE_MS)
                val videoIds = pending.filter(shows)
                pending.clear()
                if (videoIds.isEmpty()) return@collectLatest
                // Not cancelled by the next change, which would lose these
                scope.launch {
                    for (id in videoIds) {
                        val player =
                            try {
                                getVideo(id).player
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Left as it was until the page is loaded again
                                Timber.w(e, "Couldn't get the watched state of $id")
                                null
                            } ?: continue
                        onChange(id, player)
                    }
                }
            }
    }
}

/** The list with the watched state and position of [videoId] changed to [player]. */
fun TaListState<TaVideo>.withPlayer(videoId: String, player: TaPlayer): TaListState<TaVideo> =
    copy(items = items.withPlayer(videoId, player))

/** The videos with the watched state and position of [videoId] changed to [player]. */
fun List<TaVideo>.withPlayer(videoId: String, player: TaPlayer): List<TaVideo> = map {
    if (it.youtubeId == videoId) it.copy(player = player) else it
}

// How long to wait for more changes before asking TubeArchivist
private const val WATCHED_SETTLE_MS = 300L
