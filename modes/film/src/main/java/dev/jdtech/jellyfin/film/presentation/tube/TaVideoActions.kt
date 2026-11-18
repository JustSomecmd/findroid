package dev.jdtech.jellyfin.film.presentation.tube

import dev.jdtech.jellyfin.tubearchivist.TaApi

/** What can be done to videos on the server, as on TubeArchivist's web pages. */
enum class TaVideoAction {
    /** Look the videos up on YouTube again. */
    REINDEX,

    /** Download the videos again, replacing their files. */
    REDOWNLOAD,

    /** Remove the videos and their files from the server. */
    DELETE,
}

/**
 * Does [action] to the videos with these IDs. With [ignore], deleted videos are also put on the
 * ignore list so they aren't downloaded again. Returns the message to show afterwards.
 */
suspend fun TaApi.runVideoAction(
    action: TaVideoAction,
    videoIds: Collection<String>,
    ignore: Boolean = false,
): String {
    val ids = videoIds.toList()
    when (action) {
        TaVideoAction.REINDEX -> refresh("video", ids)
        TaVideoAction.REDOWNLOAD -> addToQueue(ids, autostart = true, force = true)
        TaVideoAction.DELETE ->
            ids.forEach { id ->
                deleteVideo(id)
                if (ignore) setDownloadStatus(id, "ignore-force")
            }
    }
    return when (action) {
        TaVideoAction.REINDEX -> TaMessages.REFRESH_STARTED
        TaVideoAction.REDOWNLOAD -> TaMessages.REDOWNLOAD_STARTED
        TaVideoAction.DELETE -> TaMessages.DELETED
    }
}
