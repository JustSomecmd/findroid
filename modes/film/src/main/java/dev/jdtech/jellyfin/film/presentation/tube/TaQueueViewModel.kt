package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaDownloadItem
import dev.jdtech.jellyfin.tubearchivist.TaNotification
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** The video TubeArchivist is downloading right now. */
data class TaDownloading(
    val taskId: String,
    val title: String,
    /** Such as "45.3% of 120MiB at 5MiB/s - time left: 00:20". */
    val detail: String?,
    val progress: Double?,
    /** The video in the queue, when it could be matched by its title. */
    val item: TaDownloadItem?,
)

enum class TaQueueFilter(val apiValue: String) {
    PENDING("pending"),
    IGNORED("ignore"),
}

/** TubeArchivist's download queue: what it will download next, and what was set aside. */
@HiltViewModel
class TaQueueViewModel @Inject constructor(private val taApi: TaApi) : ViewModel() {
    private val _filter = MutableStateFlow(TaQueueFilter.PENDING)
    val filter = _filter.asStateFlow()

    private val _items = MutableStateFlow(TaListState<TaDownloadItem>())
    val items = _items.asStateFlow()

    /** Tasks running on the server, such as a rescan or downloads, with their progress. */
    private val _tasks = MutableStateFlow<List<TaNotification>>(emptyList())
    val tasks = _tasks.asStateFlow()

    private val _downloading = MutableStateFlow<TaDownloading?>(null)
    val downloading = _downloading.asStateFlow()

    // Everything the server reported last time, the downloads included
    private var lastNotifications: List<TaNotification> = emptyList()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var pollJob: Job? = null

    fun selectFilter(filter: TaQueueFilter) {
        if (filter == _filter.value) return
        _filter.value = filter
        reset()
    }

    /** Switches between the queue and the videos that were set aside. */
    fun toggleIgnored() =
        selectFilter(
            if (_filter.value == TaQueueFilter.IGNORED) TaQueueFilter.PENDING
            else TaQueueFilter.IGNORED
        )

    fun loadMore() {
        val filter = _filter.value
        viewModelScope.launch {
            _items.loadNextPage(key = { it.youtubeId }) { page ->
                taApi.getDownloads(page = page, filter = filter.apiValue)
            }
        }
    }

    /** Starts the list over, for a different list. */
    private fun reset() {
        _items.value = TaListState()
        loadMore()
    }

    /**
     * Brings the list up to date in place, so videos that finished or were added slide out or in
     * instead of the whole list loading again.
     */
    fun refresh() {
        if (_items.value.page == 0) {
            loadMore()
            return
        }
        val filter = _filter.value
        viewModelScope.launch {
            _items.reloadInPlace(key = { it.youtubeId }, isCurrent = { _filter.value == filter }) {
                page ->
                taApi.getDownloads(page = page, filter = filter.apiValue)
            }
        }
    }

    /** Follows the server's tasks while the queue is on screen. */
    fun startWatchingTasks() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                val before = lastNotifications
                val now =
                    try {
                        taApi.getNotifications()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Could not load TubeArchivist tasks")
                        before
                    }
                lastNotifications = now
                val downloadBefore = _downloading.value
                val download = now.firstOrNull { it.group == DOWNLOAD_GROUP }?.toDownloading()
                // The video before is done once another starts, or once the downloads end
                // without being stopped. It leaves the list as the card moves on, rather than at
                // the next refresh.
                val finished =
                    downloadBefore?.item?.takeIf {
                        download?.title != downloadBefore.title &&
                            downloadBefore.taskId != stoppedTaskId
                    }
                if (finished != null) {
                    _items.update { state ->
                        state.copy(
                            items = state.items.filter { it.youtubeId != finished.youtubeId },
                            total = state.total?.let { (it - 1).coerceAtLeast(0) },
                        )
                    }
                }
                _downloading.value = download
                // The downloads are shown on their own, not with the other tasks
                _tasks.value = now.filter { it.group != DOWNLOAD_GROUP }
                when {
                    // A task finished, so the queue has probably changed
                    before.isNotEmpty() && now.size < before.size -> refresh()
                    // The next video started, so the last one is done and leaves the queue
                    downloadBefore != null && download?.title != downloadBefore.title -> refresh()
                }
                delay(if (now.isEmpty()) IDLE_POLL_MS else ACTIVE_POLL_MS)
            }
        }
    }

    fun stopWatchingTasks() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Adds videos, playlists or whole channels, given as links or IDs. [fastAdd] skips looking up
     * their details, [redownload] gets videos again that are already downloaded.
     */
    fun add(input: String, downloadNow: Boolean, fastAdd: Boolean, redownload: Boolean) {
        val urls = input.split(Regex("""[\s,]+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) return
        runAction(TaMessages.ADDED_TO_QUEUE) {
            taApi.addToQueue(urls, autostart = downloadNow, flat = fastAdd, force = redownload)
        }
    }

    // The downloads stopped from here, whose video stays in the queue
    private var stoppedTaskId: String? = null

    /** Stops downloading after the current step. */
    fun stopDownloading() {
        val taskId = _downloading.value?.taskId ?: return
        stoppedTaskId = taskId
        viewModelScope.launch {
            try {
                taApi.stopTask(taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            }
        }
    }

    private fun TaNotification.toDownloading(): TaDownloading? {
        val first = messages.firstOrNull() ?: return null
        // Named by the video's title, and "Processing Video: title" once it is downloaded
        val title = first.replace(Regex("""^Processing \w+: """), "")
        return TaDownloading(
            taskId = id,
            title = title,
            detail = messages.getOrNull(1),
            progress = progress?.takeIf { it > 0 },
            item = _items.value.items.firstOrNull { it.title == title },
        )
    }

    fun startDownloading() =
        runAction(TaMessages.TASK_STARTED) { taApi.startTask("download_pending") }

    fun rescanSubscriptions() =
        runAction(TaMessages.TASK_STARTED) { taApi.startTask("update_subscribed") }

    /** Downloads this one before everything else. */
    fun downloadNow(item: TaDownloadItem) =
        updateItem(item) { taApi.setDownloadStatus(item.youtubeId, "priority") }

    fun ignore(item: TaDownloadItem) =
        updateItem(item, removeFromList = true) {
            taApi.setDownloadStatus(item.youtubeId, "ignore")
        }

    /** Puts an ignored video back in the queue. */
    fun unignore(item: TaDownloadItem) =
        updateItem(item, removeFromList = true) {
            taApi.setDownloadStatus(item.youtubeId, "pending")
        }

    fun remove(item: TaDownloadItem) =
        updateItem(item, removeFromList = true) { taApi.deleteDownload(item.youtubeId) }

    /** Removes everything in the current list from the queue. */
    fun clearAll() {
        val filter = _filter.value
        viewModelScope.launch {
            try {
                taApi.clearQueue(filter.apiValue)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            }
        }
    }

    private fun updateItem(
        item: TaDownloadItem,
        removeFromList: Boolean = false,
        action: suspend () -> Unit,
    ) {
        val previous = _items.value
        if (removeFromList) {
            _items.update { state -> state.copy(items = state.items.filter { it != item }) }
        }
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _items.value = previous
                _messages.send(e.errorText())
            }
        }
    }

    private fun runAction(successMessage: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                _messages.send(successMessage)
                // Show the task right away instead of at the next check
                stopWatchingTasks()
                startWatchingTasks()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(e.errorText())
            }
        }
    }

    companion object {
        private const val DOWNLOAD_GROUP = "download:run"
        private const val ACTIVE_POLL_MS = 2_000L
        private const val IDLE_POLL_MS = 10_000L
    }
}
