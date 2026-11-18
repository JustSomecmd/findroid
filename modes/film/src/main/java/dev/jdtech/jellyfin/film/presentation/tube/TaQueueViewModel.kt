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

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var pollJob: Job? = null

    fun selectFilter(filter: TaQueueFilter) {
        if (filter == _filter.value) return
        _filter.value = filter
        refresh()
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

    fun refresh() {
        _items.value = TaListState()
        loadMore()
    }

    /** Follows the server's tasks while the queue is on screen. */
    fun startWatchingTasks() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                val before = _tasks.value
                val now =
                    try {
                        taApi.getNotifications()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Could not load TubeArchivist tasks")
                        before
                    }
                _tasks.value = now
                // A task finished, so the queue has probably changed
                if (before.isNotEmpty() && now.size < before.size) refresh()
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
        private const val ACTIVE_POLL_MS = 2_000L
        private const val IDLE_POLL_MS = 10_000L
    }
}
