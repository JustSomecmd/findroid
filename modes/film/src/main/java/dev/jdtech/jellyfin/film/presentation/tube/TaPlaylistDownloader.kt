package dev.jdtech.jellyfin.film.presentation.tube

import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.isDownloading
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaJellyfinLookup
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.utils.Downloader
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** How far adding a playlist to the phone's downloads has come. */
data class TaPlaylistDownloadProgress(val done: Int, val total: Int)

/** What adding a playlist to the phone's downloads came to. */
data class TaPlaylistDownloadResult(
    val playlistId: String,
    /** Added to the download queue now. */
    val added: Int,
    /** Already on the phone or in the download queue. */
    val already: Int,
    /** Not found in Jellyfin, or couldn't be added. */
    val failed: Int,
)

/**
 * Adds all the downloaded videos of a TubeArchivist playlist to the phone's download queue. Keeps
 * going when the playlist's page is closed.
 */
@Singleton
class TaPlaylistDownloader
@Inject
constructor(
    private val taApi: TaApi,
    private val lookup: TaJellyfinLookup,
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow<Map<String, TaPlaylistDownloadProgress>>(emptyMap())
    /** Playlists being added right now, by ID. */
    val progress = _progress.asStateFlow()

    private val _results = MutableSharedFlow<TaPlaylistDownloadResult>(extraBufferCapacity = 8)
    val results = _results.asSharedFlow()

    fun downloadAll(playlist: TaPlaylist, storageIndex: Int = 0) {
        if (playlist.id in _progress.value) return
        val entries = playlist.entries.filter { it.downloaded }
        _progress.update { it + (playlist.id to TaPlaylistDownloadProgress(0, entries.size)) }
        scope.launch {
            var added = 0
            var already = 0
            var failed = 0
            try {
                entries.forEachIndexed { index, entry ->
                    try {
                        val episodeId =
                            lookup.findEpisodeId(entry.youtubeId, entry.title, null, null)
                                ?: taApi.getVideo(entry.youtubeId).let { video ->
                                    lookup.findEpisodeId(
                                        videoId = entry.youtubeId,
                                        title = video.title,
                                        channelId = video.channel?.id,
                                        channelName = video.channel?.name,
                                    )
                                }
                        val item = episodeId?.let { repository.getEpisode(it) }
                        when {
                            item == null || !item.canDownload -> failed++
                            item.isDownloaded() || item.isDownloading() -> already++
                            else -> {
                                val error =
                                    downloader.downloadItem(
                                        item = item,
                                        sourceId = item.sources.first().id,
                                        storageIndex = storageIndex,
                                    )
                                if (error == null) added++ else failed++
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Could not add ${entry.youtubeId} to the downloads")
                        failed++
                    }
                    _progress.update {
                        it + (playlist.id to TaPlaylistDownloadProgress(index + 1, entries.size))
                    }
                }
            } finally {
                _progress.update { it - playlist.id }
                _results.tryEmit(TaPlaylistDownloadResult(playlist.id, added, already, failed))
            }
        }
    }
}
