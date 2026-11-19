package dev.jdtech.jellyfin.tubearchivist

/**
 * Loads a playlist with its downloaded videos in the playlist's order. A video TubeArchivist
 * doesn't return in full is still there by its ID and title.
 */
suspend fun TaApi.loadPlaylist(playlistId: String): Pair<TaPlaylist, List<TaVideo>> {
    val playlist = getPlaylist(playlistId)
    val videos = mutableListOf<TaVideo>()
    var page = 1
    while (true) {
        val result = getVideos(page = page, playlist = playlistId)
        videos += result.data
        if (!result.hasMore || page >= MAX_PAGES) break
        page++
    }
    val loaded = videos.associateBy { it.youtubeId }
    val ordered =
        playlist.entries
            .filter { it.downloaded }
            .map { entry ->
                loaded[entry.youtubeId] ?: TaVideo(youtubeId = entry.youtubeId, title = entry.title)
            }
            .ifEmpty { videos }
    return playlist to ordered
}

private const val MAX_PAGES = 20
