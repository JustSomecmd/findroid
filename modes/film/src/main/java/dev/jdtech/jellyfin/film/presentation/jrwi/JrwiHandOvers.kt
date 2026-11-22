package dev.jdtech.jellyfin.film.presentation.jrwi

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.jrwi.JrwiApi
import dev.jdtech.jellyfin.jrwi.JrwiDownloader
import dev.jdtech.jellyfin.jrwi.JrwiDownloads
import dev.jdtech.jellyfin.jrwi.JrwiProgressSync
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.isDownloading
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.repository.MediaChanges
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaJellyfinLookup
import dev.jdtech.jellyfin.tubearchivist.TaOfflineStore
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
 * Keeps the phone's downloads of JRWI episodes in step with TubeArchivist:
 * - When the archive deletes an episode's video because TubeArchivist has it from YouTube, a copy
 *   of that video downloaded through Jellyfin goes too, and TubeArchivist's video is put in the
 *   download queue instead, on the same storage, once Jellyfin has it. Until then it waits, and
 *   each check tries again.
 * - TubeArchivist's downloads that are a JRWI episode's YouTube video are kept with their episode,
 *   so they're listed in JRWI's Downloads as well as TubeArchivist's.
 */
@Singleton
class JrwiHandOvers
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val jrwiApi: JrwiApi,
    private val jrwiDownloads: JrwiDownloads,
    private val jrwiDownloader: JrwiDownloader,
    private val jrwiProgress: JrwiProgressSync,
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
    private val taApi: TaApi,
    private val lookup: TaJellyfinLookup,
    private val taOfflineStore: TaOfflineStore,
) {
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Looks up each downloaded archived video on the archive and takes off the phone those that
     * were handed over, queues the TubeArchivist videos still waiting, and keeps TubeArchivist's
     * downloads of JRWI episodes with their episode. Without a connection to the archive, nothing
     * is taken off. One check at a time; a second one meanwhile does nothing.
     */
    suspend fun check() {
        if (!mutex.tryLock()) return
        try {
            withContext(Dispatchers.IO) {
                var changed = false
                if (jrwiApi.isConfigured) changed = removeHandedOver()
                if (taApi.isConfigured) queueWaiting()
                if (jrwiApi.isConfigured && taApi.isConfigured) {
                    changed = linkTaDownloads() || changed
                }
                if (changed) MediaChanges.downloadsChanged()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not check the JRWI hand-overs")
        } finally {
            mutex.unlock()
        }
    }

    /** Takes handed-over archived videos off the phone: true when there were any. */
    private suspend fun removeHandedOver(): Boolean {
        // Only the archived videos: TubeArchivist's copies are what's wanted after a hand-over
        val saved = jrwiDownloads.savedVideos().filter { it.youtubeId == null }
        if (saved.isEmpty()) return false
        var removed = false
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
            removed = true
            Timber.i("JRWI item ${video.item.id} was handed over to TubeArchivist ($youtubeId)")
            if (youtubeId != null) addWaiting(youtubeId, storageIndex, video.withFiles)
        }
        return removed
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
            // The post's files come back with TubeArchivist's copy, as they were with the archive's
            else if (entry.withFiles) addToSet(KEY_FILES, entry.youtubeId)
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

    /**
     * Keeps each TubeArchivist download that's a JRWI episode's YouTube video with its episode, and
     * forgets those whose download is gone. Videos found not to be JRWI's are asked about again
     * once a day for a month, as the archive can find an episode's YouTube post later. True when
     * anything changed.
     */
    private suspend fun linkTaDownloads(): Boolean {
        var changed = false
        val episodes = repository.getDownloadedEpisodes()
        val onPhone = episodes.map { it.id.toString() }.toSet()
        val saved = jrwiDownloads.savedVideos()
        saved
            .filter { it.youtubeId != null && it.episodeId !in onPhone }
            .forEach {
                jrwiDownloads.deleteVideo(it.item.id)
                changed = true
            }
        val known = saved.map { it.episodeId }.toSet()
        val notJrwi = readNotJrwi()
        val now = System.currentTimeMillis()
        val checked = notJrwi.toMutableMap()
        for (episode in episodes) {
            if (episode.id.toString() in known) continue
            val youtubeId = taOfflineStore.videoIdForItem(episode.id) ?: continue
            val seen = notJrwi[youtubeId]
            if (seen != null && (now - seen.last < DAY || now - seen.first > MONTH)) continue
            val found =
                try {
                    jrwiApi.search(youtubeId = youtubeId, pageSize = 5).items.firstOrNull {
                        it.video?.youtubeId == youtubeId
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Offline, or the archive can't say: asked again next time
                    Timber.d(e, "Could not look up YouTube video $youtubeId on the archive")
                    continue
                }
            if (found == null) {
                checked[youtubeId] = NotJrwi(seen?.first ?: now, now)
                continue
            }
            checked.remove(youtubeId)
            // An archived video of the same episode on the phone keeps its place
            val existing = jrwiDownloads.savedVideo(found.id)
            if (existing != null && existing.youtubeId == null) continue
            val full =
                try {
                    jrwiApi.getItem(found.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    found
                }
            val withFiles = removeFromSet(KEY_FILES, youtubeId)
            jrwiDownloads.saveVideo(
                item = full,
                episodeId = episode.id.toString(),
                withFiles = withFiles,
                youtubeId = youtubeId,
                showName = jrwiProgress.showNameOrNull(full.show),
            )
            if (withFiles) jrwiDownloader.keepFilesForVideo(full.id)
            changed = true
        }
        writeNotJrwi(checked)
        return changed
    }

    /** Which of the phone's storages a downloaded file is on, as the downloads count them. */
    private fun storageIndexOf(path: String): Int {
        val dirs = context.getExternalFilesDirs(null)
        val index = dirs.indexOfFirst { dir ->
            dir != null && path.startsWith(dir.path + File.separator)
        }
        return index.coerceAtLeast(0)
    }

    private data class Waiting(
        val youtubeId: String,
        val storageIndex: Int,
        val since: Long,
        val withFiles: Boolean,
    )

    private fun addWaiting(youtubeId: String, storageIndex: Int, withFiles: Boolean) {
        val waiting = readWaiting().filter { it.youtubeId != youtubeId }
        writeWaiting(
            waiting + Waiting(youtubeId, storageIndex, System.currentTimeMillis(), withFiles)
        )
    }

    private fun readWaiting(): List<Waiting> =
        prefs.getStringSet(KEY_WAITING, emptySet()).orEmpty().mapNotNull { line ->
            val parts = line.split('|')
            val id = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Waiting(
                youtubeId = id,
                storageIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                since = parts.getOrNull(2)?.toLongOrNull() ?: System.currentTimeMillis(),
                withFiles = parts.getOrNull(3) == "1",
            )
        }

    private fun writeWaiting(waiting: List<Waiting>) {
        val lines = waiting.map {
            "${it.youtubeId}|${it.storageIndex}|${it.since}|${if (it.withFiles) 1 else 0}"
        }
        prefs.edit().putStringSet(KEY_WAITING, lines.toSet()).apply()
    }

    /** A TubeArchivist video the archive had no episode for: first and last asked. */
    private data class NotJrwi(val first: Long, val last: Long)

    private fun readNotJrwi(): Map<String, NotJrwi> =
        prefs
            .getStringSet(KEY_NOT_JRWI, emptySet())
            .orEmpty()
            .mapNotNull { line ->
                val parts = line.split('|')
                val id = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val first = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                val last = parts.getOrNull(2)?.toLongOrNull() ?: first
                id to NotJrwi(first, last)
            }
            .toMap()

    private fun writeNotJrwi(notJrwi: Map<String, NotJrwi>) {
        val lines = notJrwi.map { (id, seen) -> "$id|${seen.first}|${seen.last}" }
        prefs.edit().putStringSet(KEY_NOT_JRWI, lines.toSet()).apply()
    }

    private fun addToSet(key: String, value: String) {
        val values = prefs.getStringSet(key, emptySet()).orEmpty() + value
        prefs.edit().putStringSet(key, values).apply()
    }

    /** Takes [value] out of the set: true when it was in it. */
    private fun removeFromSet(key: String, value: String): Boolean {
        val values = prefs.getStringSet(key, emptySet()).orEmpty()
        if (value !in values) return false
        prefs.edit().putStringSet(key, values - value).apply()
        return true
    }

    private companion object {
        const val PREFS_NAME = "jrwi_hand_overs"
        const val KEY_WAITING = "waiting"
        // TubeArchivist videos queued after a hand-over whose post's files come with them
        const val KEY_FILES = "with_files"
        const val KEY_NOT_JRWI = "not_jrwi"
        // A TubeArchivist video Jellyfin still doesn't have after this long isn't waited for
        val GIVE_UP_MILLIS = TimeUnit.DAYS.toMillis(14)
        val DAY = TimeUnit.DAYS.toMillis(1)
        val MONTH = TimeUnit.DAYS.toMillis(30)
    }
}
