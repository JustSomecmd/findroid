package dev.jdtech.jellyfin.film.presentation.jrwi

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.jrwi.JrwiApi
import dev.jdtech.jellyfin.jrwi.JrwiDownloader
import dev.jdtech.jellyfin.jrwi.JrwiDownloads
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.isDownloading
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaJellyfinLookup
import dev.jdtech.jellyfin.utils.Downloader
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Follows the archive's hand-overs to TubeArchivist on the phone. When the archive deletes an
 * episode's video because TubeArchivist has it from YouTube, a copy of that video downloaded to the
 * phone through Jellyfin goes too, and TubeArchivist's video is put in the download queue instead,
 * on the same storage, once Jellyfin has it. Until then it waits, and each check tries again.
 */
@Singleton
class JrwiHandOvers
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val jrwiApi: JrwiApi,
    private val jrwiDownloads: JrwiDownloads,
    private val jrwiDownloader: JrwiDownloader,
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
    private val taApi: TaApi,
    private val lookup: TaJellyfinLookup,
) {
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Looks up each downloaded archived video on the archive, takes off the phone those that were
     * handed over, and queues the TubeArchivist videos still waiting. Without a connection to the
     * archive, nothing is taken off.
     */
    suspend fun check() {
        if (!mutex.tryLock()) return
        try {
            withContext(Dispatchers.IO) {
                if (jrwiApi.isConfigured) removeHandedOver()
                if (taApi.isConfigured) queueWaiting()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not check the JRWI hand-overs")
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun removeHandedOver() {
        val saved = jrwiDownloads.savedVideos()
        if (saved.isEmpty()) return
        // The phone's downloaded episodes, read once there's a hand-over
        var downloaded: List<FindroidEpisode>? = null
        for (video in saved) {
            val item =
                try {
                    jrwiApi.getItem(video.item.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Offline, or the archive can't say: the copy stays
                    Timber.d(e, "Could not look up JRWI item ${video.item.id}")
                    continue
                }
            val now = item.video ?: continue
            if (now.handedOver == null && !now.isYoutube) continue
            val youtubeId = now.youtubeId ?: now.handedOver?.youtubeId
            val all = downloaded ?: repository.getDownloadedEpisodes().also { downloaded = it }
            val episode = all.firstOrNull { it.id.toString() == video.episodeId }
            val local = episode?.sources?.firstOrNull { it.type == FindroidSourceType.LOCAL }
            val storageIndex = local?.let { storageIndexOf(it.path) } ?: 0
            if (episode != null && local != null) {
                downloader.deleteItem(episode, local)
            }
            jrwiDownloader.forgetVideo(video.item.id)
            Timber.i("JRWI item ${video.item.id} was handed over to TubeArchivist ($youtubeId)")
            if (youtubeId != null) addWaiting(youtubeId, storageIndex)
        }
    }

    private suspend fun queueWaiting() {
        val waiting = readWaiting()
        if (waiting.isEmpty()) return
        val now = System.currentTimeMillis()
        val left = mutableListOf<Waiting>()
        for (entry in waiting) {
            if (now - entry.since > GIVE_UP_MILLIS) continue
            val done =
                try {
                    queue(entry)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.d(e, "Could not queue TubeArchivist video ${entry.youtubeId}")
                    false
                }
            if (!done) left += entry
        }
        writeWaiting(left)
    }

    /** Puts the video in the download queue: true once it's queued or on the phone already. */
    private suspend fun queue(entry: Waiting): Boolean {
        val video = taApi.getVideo(entry.youtubeId)
        val episodeId =
            lookup.findEpisodeId(
                videoId = entry.youtubeId,
                title = video.title,
                channelId = video.channel?.id,
                channelName = video.channel?.name,
            ) ?: return false
        val episode = repository.getEpisode(episodeId)
        if (episode.isDownloaded() || episode.isDownloading()) return true
        if (!episode.canDownload) return false
        val source = episode.sources.firstOrNull() ?: return false
        val error = downloader.downloadItem(episode, source.id, entry.storageIndex)
        if (error != null) Timber.w("TubeArchivist video ${entry.youtubeId} wasn't queued")
        return error == null
    }

    /** Which of the phone's storages a downloaded file is on, as the downloads count them. */
    private fun storageIndexOf(path: String): Int {
        val dirs = context.getExternalFilesDirs(null)
        val index = dirs.indexOfFirst { dir ->
            dir != null && path.startsWith(dir.path + File.separator)
        }
        return index.coerceAtLeast(0)
    }

    private data class Waiting(val youtubeId: String, val storageIndex: Int, val since: Long)

    private fun addWaiting(youtubeId: String, storageIndex: Int) {
        val waiting = readWaiting().filter { it.youtubeId != youtubeId }
        writeWaiting(waiting + Waiting(youtubeId, storageIndex, System.currentTimeMillis()))
    }

    private fun readWaiting(): List<Waiting> =
        prefs.getStringSet(KEY_WAITING, emptySet()).orEmpty().mapNotNull { line ->
            val parts = line.split('|')
            val id = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Waiting(
                youtubeId = id,
                storageIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                since = parts.getOrNull(2)?.toLongOrNull() ?: System.currentTimeMillis(),
            )
        }

    private fun writeWaiting(waiting: List<Waiting>) {
        prefs
            .edit()
            .putStringSet(
                KEY_WAITING,
                waiting.map { "${it.youtubeId}|${it.storageIndex}|${it.since}" }.toSet(),
            )
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "jrwi_hand_overs"
        const val KEY_WAITING = "waiting"
        // A TubeArchivist video Jellyfin still doesn't have after this long isn't waited for
        val GIVE_UP_MILLIS = TimeUnit.DAYS.toMillis(14)
    }
}
