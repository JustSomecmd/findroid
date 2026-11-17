package dev.jdtech.jellyfin.work

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

/**
 * Runs media downloads one at a time.
 *
 * A download that is started while another one is active waits in this queue, which is stored on
 * the device so it survives the app being closed. Whenever a download finishes, fails, is paused or
 * is cancelled, the next one in the queue starts.
 */
object MediaDownloadQueue {
    data class Entry(
        val itemId: UUID,
        val sourceId: String,
        val url: String,
        val title: String,
        val networkType: NetworkType,
    )

    private const val PREFS_NAME = "media_download_queue"
    private const val KEY_QUEUE = "queue"
    private const val STATE_WAIT_MS = 2000L

    private val mutex = Mutex()

    // Workers that have finished their download but are still marked as running. They no longer
    // count as busy, so a download added at that moment starts instead of waiting with nothing to
    // start it. Only accessed while holding [mutex].
    private val finishingWorkIds = mutableSetOf<UUID>()

    private val _changes = MutableStateFlow(0)

    /** Changes whenever the queue changes, so download screens can refresh. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun isQueued(context: Context, itemId: UUID): Boolean =
        read(context).any { it.itemId == itemId }

    /** Items waiting to download, next one first. */
    fun queuedItemIds(context: Context): List<UUID> = read(context).map { it.itemId }

    /** Starts the download right away if nothing else is downloading, otherwise queues it. */
    suspend fun add(context: Context, workManager: WorkManager, entry: Entry) {
        mutex.withLock {
            val queue = read(context).filterNot { it.itemId == entry.itemId }
            if (isIdle(workManager)) {
                write(context, queue)
                startWork(workManager, entry)
            } else {
                write(context, queue + entry)
            }
        }
        _changes.update { it + 1 }
    }

    /** Puts the queue in the given order. Items not in [itemIds] keep their place at the end. */
    suspend fun reorder(context: Context, itemIds: List<UUID>) {
        mutex.withLock {
            val queue = read(context)
            val byId = queue.associateBy { it.itemId }
            write(
                context,
                itemIds.mapNotNull { byId[it] } + queue.filter { it.itemId !in itemIds },
            )
        }
        _changes.update { it + 1 }
    }

    suspend fun remove(context: Context, itemId: UUID) {
        mutex.withLock { write(context, read(context).filterNot { it.itemId == itemId }) }
        _changes.update { it + 1 }
    }

    /**
     * Starts the next queued download if nothing is downloading. A worker that is about to finish
     * passes its own id as [excludeWorkId], because it still counts as running at that point.
     */
    suspend fun startNext(context: Context, workManager: WorkManager, excludeWorkId: UUID? = null) {
        mutex.withLock {
            if (excludeWorkId != null) finishingWorkIds.add(excludeWorkId)
            if (!isIdle(workManager)) return@withLock
            val queue = read(context)
            val next = queue.firstOrNull() ?: return@withLock
            write(context, queue.drop(1))
            startWork(workManager, next)
        }
        _changes.update { it + 1 }
    }

    private suspend fun isIdle(workManager: WorkManager): Boolean {
        val infos = workManager.getWorkInfosByTagFlow(MediaDownloadWorker.TAG).first()
        val active = infos.filter { !it.state.isFinished }.map { it.id }.toSet()
        finishingWorkIds.retainAll(active)
        return active.none { it !in finishingWorkIds }
    }

    private suspend fun startWork(workManager: WorkManager, entry: Entry) {
        val request =
            OneTimeWorkRequestBuilder<MediaDownloadWorker>()
                .setInputData(
                    workDataOf(
                        MediaDownloadWorker.KEY_ITEM_ID to entry.itemId.toString(),
                        MediaDownloadWorker.KEY_SOURCE_ID to entry.sourceId,
                        MediaDownloadWorker.KEY_URL to entry.url,
                        MediaDownloadWorker.KEY_TITLE to entry.title,
                    )
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(entry.networkType).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(MediaDownloadWorker.TAG)
                .build()
        val name = MediaDownloadWorker.uniqueWorkName(entry.itemId)
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
        // Wait until WorkManager has stored the request. Otherwise a second download started right
        // after this one would not see it and would start as well.
        withTimeoutOrNull(STATE_WAIT_MS) {
            workManager.getWorkInfosForUniqueWorkFlow(name).first { infos ->
                infos.any { !it.state.isFinished }
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun read(context: Context): List<Entry> {
        val json = prefs(context).getString(KEY_QUEUE, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val entry = array.getJSONObject(i)
                Entry(
                    itemId = UUID.fromString(entry.getString("itemId")),
                    sourceId = entry.getString("sourceId"),
                    url = entry.getString("url"),
                    title = entry.optString("title"),
                    networkType =
                        NetworkType.entries.firstOrNull {
                            it.name == entry.optString("networkType")
                        } ?: NetworkType.CONNECTED,
                )
            }
        } catch (e: JSONException) {
            Timber.e(e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            Timber.e(e)
            emptyList()
        }
    }

    private fun write(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put("itemId", entry.itemId.toString())
                    .put("sourceId", entry.sourceId)
                    .put("url", entry.url)
                    .put("title", entry.title)
                    .put("networkType", entry.networkType.name)
            )
        }
        prefs(context).edit().putString(KEY_QUEUE, array.toString()).apply()
    }
}
