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
import java.util.concurrent.ConcurrentHashMap
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
 * the device so it survives the app being closed. Whenever a download finishes, fails or is
 * cancelled, the next one in the queue starts.
 *
 * A paused download keeps its place in the queue, and the queue never moves past it by itself: when
 * a paused download is next in line, nothing starts until it is resumed or another download is
 * moved in front of it. Pausing the running download puts it back at the front.
 */
object MediaDownloadQueue {
    data class Entry(
        val itemId: UUID,
        val sourceId: String,
        val url: String,
        val title: String,
        val networkType: NetworkType,
        val paused: Boolean = false,
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

    // Downloads the app is stopping itself; it takes care of their place in the queue. Any other
    // stop by the app comes from the Pause button in the download notification.
    private val stoppingOnPurpose = ConcurrentHashMap.newKeySet<UUID>()

    /** Called before the app stops a download, so it isn't taken for a pause. */
    fun stoppingOnPurpose(itemId: UUID) {
        stoppingOnPurpose.add(itemId)
    }

    /**
     * Called by a download that was stopped by the app. Unless the app did that on purpose, it came
     * from the notification's Pause button, which then works like pausing in the app.
     */
    suspend fun onStopped(context: Context, entry: Entry) {
        if (stoppingOnPurpose.remove(entry.itemId)) return
        pause(context, entry.itemId, entry)
    }

    /** Items waiting to download, paused ones included, next one first. */
    fun queuedItemIds(context: Context): List<UUID> = read(context).map { it.itemId }

    /** The queue entry of an item, or null when it isn't in the queue. */
    fun entry(context: Context, itemId: UUID): Entry? {
        return read(context).firstOrNull { it.itemId == itemId }
    }

    /**
     * Pauses a download. One that is waiting keeps its place. One that is running is passed as
     * [running] and goes back to the front of the queue, so the queue waits for it.
     */
    suspend fun pause(context: Context, itemId: UUID, running: Entry?) {
        mutex.withLock {
            val queue = read(context)
            val updated =
                when {
                    queue.any { it.itemId == itemId } ->
                        queue.map { if (it.itemId == itemId) it.copy(paused = true) else it }
                    running != null -> listOf(running.copy(paused = true)) + queue
                    else -> queue
                }
            write(context, updated)
        }
        _changes.update { it + 1 }
    }

    /**
     * Starts the queued download of [itemId] right away with a fresh [url], taking it out of the
     * queue. [interrupted] is the download it replaces, which must already be stopped: it goes back
     * into the queue so it continues later. The queue is put in [order], the order the user chose,
     * which can include both of them.
     */
    suspend fun startNow(
        context: Context,
        workManager: WorkManager,
        itemId: UUID,
        url: String,
        networkType: NetworkType,
        interrupted: Entry?,
        order: List<UUID>,
    ) {
        mutex.withLock {
            val queue = read(context)
            val entry = queue.firstOrNull { it.itemId == itemId }
            // The interrupted download's new entry replaces any old one
            val all = (queue + listOfNotNull(interrupted)).filter { it.itemId != itemId }
            val byId = all.associateBy { it.itemId }
            val ordered = order.mapNotNull { byId[it] }
            val unordered = all.filter { it.itemId !in order }.distinctBy { it.itemId }
            write(context, ordered + unordered)
            if (entry != null && isIdle(workManager)) {
                startWork(
                    workManager,
                    entry.copy(url = url, networkType = networkType, paused = false),
                )
            }
        }
        _changes.update { it + 1 }
    }

    /**
     * Resumes a paused download with a fresh [url]. It starts right away if nothing else is
     * downloading, otherwise it waits in its place. Returns false when it isn't in the queue.
     */
    suspend fun resume(
        context: Context,
        workManager: WorkManager,
        itemId: UUID,
        url: String,
        networkType: NetworkType,
    ): Boolean {
        val found = mutex.withLock {
            val queue = read(context)
            val entry = queue.firstOrNull { it.itemId == itemId } ?: return@withLock false
            val resumed = entry.copy(paused = false, url = url, networkType = networkType)
            if (isIdle(workManager)) {
                write(context, queue.filterNot { it.itemId == itemId })
                startWork(workManager, resumed)
            } else {
                write(context, queue.map { if (it.itemId == itemId) resumed else it })
            }
            true
        }
        _changes.update { it + 1 }
        return found
    }

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
            // The queue waits at a paused download instead of skipping it
            if (next.paused) return@withLock
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
        stoppingOnPurpose.remove(entry.itemId)
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

    // The queue as last read or written. The download page asks for it on every progress update,
    // so it is kept here instead of being read and parsed from storage each time. Everything that
    // changes the queue runs in this process and goes through write().
    @Volatile private var cached: List<Entry>? = null

    private fun read(context: Context): List<Entry> {
        cached?.let {
            return it
        }
        return parse(context).also { cached = it }
    }

    private fun parse(context: Context): List<Entry> {
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
                    paused = entry.optBoolean("paused", false),
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
        cached = entries
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put("itemId", entry.itemId.toString())
                    .put("sourceId", entry.sourceId)
                    .put("url", entry.url)
                    .put("title", entry.title)
                    .put("networkType", entry.networkType.name)
                    .put("paused", entry.paused)
            )
        }
        prefs(context).edit().putString(KEY_QUEUE, array.toString()).apply()
    }
}
