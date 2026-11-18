package dev.jdtech.jellyfin.tubearchivist

import android.content.Context
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import timber.log.Timber

/** What TubeArchivist knows about a downloaded video, kept for when there is no connection. */
@Serializable
data class TaOfflineEntry(
    val itemId: String,
    val video: TaVideo,
    val comments: List<TaComment> = emptyList(),
)

/**
 * Keeps TubeArchivist's details of downloaded videos (description, comments, SponsorBlock segments)
 * on the phone, and progress made without a connection until it can be sent.
 */
class TaOfflineStore(
    context: Context,
    private val taApi: TaApi,
    private val lookup: TaJellyfinLookup,
    private val database: ServerDatabaseDao,
) {
    private val dir = File(context.filesDir, "tubearchivist")
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val flushLock = Mutex()

    /** The TubeArchivist video ID of a downloaded Jellyfin item, if its details were saved. */
    fun videoIdForItem(itemId: UUID): String? =
        prefs.getString(itemKey(itemId), null)?.takeIf { it.isNotEmpty() }

    suspend fun load(videoId: String): TaOfflineEntry? =
        withContext(Dispatchers.IO) {
            val file = file(videoId)
            if (!file.exists()) return@withContext null
            try {
                taApi.json.decodeFromString(TaOfflineEntry.serializer(), file.readText())
            } catch (e: Exception) {
                Timber.w(e, "Could not read saved TubeArchivist details")
                null
            }
        }

    /** The saved details of a video downloaded to the phone, if it was. */
    fun savedVideo(videoId: String): TaVideo? = savedEntry(videoId)?.video

    /** The Jellyfin item of a video downloaded to the phone, if it was. */
    fun savedItemId(videoId: String): UUID? =
        savedEntry(videoId)?.itemId?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun savedEntry(videoId: String): TaOfflineEntry? {
        val file = file(videoId)
        if (!file.exists()) return null
        return try {
            taApi.json.decodeFromString(TaOfflineEntry.serializer(), file.readText())
        } catch (e: Exception) {
            Timber.w(e, "Could not read saved TubeArchivist details")
            null
        }
    }

    /**
     * Saves TubeArchivist's details of a downloaded Jellyfin item. Items that aren't from
     * TubeArchivist are remembered as such, so they aren't looked up again.
     */
    suspend fun saveForDownload(itemId: UUID) {
        withContext(Dispatchers.IO) {
            if (!taApi.isConfigured) return@withContext
            val videoId = lookup.videoIdForItem(itemId)
            if (videoId == null) {
                prefs.edit().putString(itemKey(itemId), "").apply()
                return@withContext
            }
            val video = taApi.getVideo(videoId)
            val comments =
                try {
                    taApi.getComments(videoId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            dir.mkdirs()
            val entry =
                TaOfflineEntry(itemId = itemId.toString(), video = video, comments = comments)
            file(videoId).writeText(taApi.json.encodeToString(TaOfflineEntry.serializer(), entry))
            prefs.edit().putString(itemKey(itemId), videoId).apply()
        }
    }

    /** Forgets a download's details when it is deleted. */
    fun delete(itemId: UUID) {
        val videoId = videoIdForItem(itemId)
        prefs.edit().remove(itemKey(itemId)).apply()
        videoId?.let { file(it).delete() }
    }

    /** Keeps a position that couldn't be sent, to send later. */
    fun addPendingProgress(videoId: String, seconds: Double) {
        prefs.edit().putFloat(PROGRESS_PREFIX + videoId, seconds.toFloat()).apply()
    }

    fun clearPendingProgress(videoId: String) {
        prefs.edit().remove(PROGRESS_PREFIX + videoId).apply()
    }

    /**
     * Sends progress that was kept while offline, and saves the details of downloads made before
     * this existed. Does nothing when TubeArchivist can't be reached.
     */
    suspend fun sync() {
        if (!flushPendingProgress()) return
        saveEarlierDownloads()
    }

    /**
     * Sends progress that was kept while offline, so TubeArchivist has the newest position. Returns
     * false when TubeArchivist can't be reached.
     */
    suspend fun flushPendingProgress(): Boolean {
        if (!taApi.isConfigured) return false
        return flushLock.withLock {
            withContext(Dispatchers.IO) {
                val pending =
                    prefs.all
                        .filterKeys { it.startsWith(PROGRESS_PREFIX) }
                        .mapNotNull { (key, value) ->
                            (value as? Float)?.let {
                                key.removePrefix(PROGRESS_PREFIX) to it.toDouble()
                            }
                        }
                for ((videoId, seconds) in pending) {
                    try {
                        taApi.postProgress(videoId, seconds)
                        clearPendingProgress(videoId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TaApiException) {
                        // The video is gone from TubeArchivist
                        if (e.code == 404) clearPendingProgress(videoId)
                    } catch (e: IOException) {
                        Timber.w(e, "TubeArchivist can't be reached, sending progress later")
                        return@withContext false
                    }
                }
                true
            }
        }
    }

    private suspend fun saveEarlierDownloads() {
        withContext(Dispatchers.IO) {
            val unknown =
                database
                    .getEpisodes()
                    .filter { !prefs.contains(itemKey(it.id)) }
                    .take(BACKFILL_LIMIT)
            for (episode in unknown) {
                try {
                    saveForDownload(episode.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TaApiException) {
                    // Not in TubeArchivist (any more), so not looked up again
                    prefs.edit().putString(itemKey(episode.id), "").apply()
                } catch (e: Exception) {
                    Timber.w(e, "Could not save TubeArchivist details of a download")
                    return@withContext
                }
            }
        }
    }

    private fun file(videoId: String) = File(dir, "$videoId.json")

    private fun itemKey(itemId: UUID) = ITEM_PREFIX + itemId

    companion object {
        private const val PREFS_NAME = "tubearchivist_offline"
        private const val ITEM_PREFIX = "item_"
        private const val PROGRESS_PREFIX = "progress_"
        // Downloads from before this was added, looked up per start
        private const val BACKFILL_LIMIT = 50
    }
}
