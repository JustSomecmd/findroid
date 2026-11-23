package dev.jdtech.jellyfin.jrwi

import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.MediaChanges
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Which versions of JRWI's posts are on the phone, by JRWI item ID: [audio] downloaded whole, and
 * [video] downloaded through Jellyfin, the archived video or TubeArchivist's copy of it.
 * [videoItems] gives the item of each downloaded video by its Jellyfin episode ID.
 */
data class JrwiOnPhoneState(
    val audio: Set<Long> = emptySet(),
    val video: Set<Long> = emptySet(),
    val videoItems: Map<String, Long> = emptyMap(),
) {
    fun audioOnPhone(itemId: Long): Boolean = itemId in audio

    fun videoOnPhone(itemId: Long): Boolean = itemId in video
}

/**
 * What's on the phone, for the version marks every post's picture carries in JRWI mode: one record
 * for all the pages, read again whenever a download is added, finishes or is removed.
 */
class JrwiOnPhone(
    private val downloads: JrwiDownloads,
    // Online or offline, as it is at the time
    private val repository: () -> JellyfinRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The JRWI item of each video downloaded through Jellyfin, by Jellyfin episode ID
    private val videos = MutableStateFlow<Map<String, Long>>(emptyMap())

    val state: StateFlow<JrwiOnPhoneState> =
        combine(downloads.downloads, videos) { audio, videos ->
                JrwiOnPhoneState(
                    audio = audio.filter { it.done }.map { it.itemId }.toSet(),
                    video = videos.values.toSet(),
                    videoItems = videos,
                )
            }
            .stateIn(scope, SharingStarted.Eagerly, JrwiOnPhoneState())

    init {
        scope.launch {
            loadVideos()
            MediaChanges.changes.filterIsInstance<MediaChanges.Downloads>().collect { loadVideos() }
        }
    }

    private suspend fun loadVideos() {
        videos.value =
            try {
                val downloaded =
                    repository()
                        .getDownloadedEpisodes()
                        .filter { it.isDownloaded() }
                        .map { it.id.toString() }
                        .toSet()
                downloads
                    .savedVideos()
                    .filter { it.episodeId in downloaded }
                    .associate { it.episodeId to it.item.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not read the downloaded videos")
                videos.value
            }
    }
}
