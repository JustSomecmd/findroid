package dev.jdtech.jellyfin.film.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.isDownloading
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.DownloadInfo
import dev.jdtech.jellyfin.utils.DownloadStatus
import dev.jdtech.jellyfin.utils.Downloader
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A download that hasn't finished yet: running, waiting in the queue, paused or failed. */
/**
 * A download that hasn't finished. [inQueue] is true while it waits in the queue, paused or not.
 */
data class ActiveDownload(
    val item: FindroidItem,
    val info: DownloadInfo,
    val inQueue: Boolean = false,
)

@HiltViewModel
class DownloadsViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val downloader: Downloader,
) : ViewModel() {
    private val _state = MutableStateFlow(CollectionState())
    val state = _state.asStateFlow()

    private val _activeDownloads = MutableStateFlow<List<ActiveDownload>>(emptyList())
    val activeDownloads = _activeDownloads.asStateFlow()

    private var activeDownloadsJob: Job? = null
    private var startNowJob: Job? = null

    fun loadItems() {
        viewModelScope.launch {
            _state.emit(_state.value.copy(isLoading = true, error = null))

            try {
                val allItems = repository.getDownloads()
                val allEpisodes = repository.getDownloadedEpisodes()
                val showEpisodes = appPreferences.getValue(appPreferences.downloadsShowEpisodes)

                observeActiveDownloads(allItems.filterIsInstance<FindroidMovie>() + allEpisodes)

                // Unfinished downloads are listed separately at the top
                val items = allItems.filter { it !is FindroidMovie || !it.isDownloading() }
                val episodes = allEpisodes.filter { !it.isDownloading() }

                val sections = mutableListOf<CollectionSection>()

                withContext(Dispatchers.Default) {
                    CollectionSection(
                            Constants.FAVORITE_TYPE_MOVIES,
                            UiText.StringResource(CoreR.string.movies_label),
                            items.filterIsInstance<FindroidMovie>(),
                        )
                        .let {
                            if (it.items.isNotEmpty()) {
                                sections.add(it)
                            }
                        }
                    if (showEpisodes) {
                        sections.addAll(episodeSections(episodes))
                    } else {
                        CollectionSection(
                                Constants.FAVORITE_TYPE_SHOWS,
                                UiText.StringResource(CoreR.string.shows_label),
                                items.filterIsInstance<FindroidShow>(),
                            )
                            .let {
                                if (it.items.isNotEmpty()) {
                                    sections.add(it)
                                }
                            }
                    }
                }

                _state.emit(_state.value.copy(isLoading = false, sections = sections))
            } catch (e: Exception) {
                _state.emit(_state.value.copy(isLoading = false, error = e))
            }
        }
    }

    /**
     * Saves the order the user dragged the downloads into. The list includes the running download.
     * When a waiting download is put above it and stays there for two seconds, it starts, and the
     * running one continues right after it.
     */
    fun reorderQueue(itemIds: List<UUID>) {
        startNowJob?.cancel()
        val running =
            _activeDownloads.value.filter {
                it.info.status == DownloadStatus.RUNNING || it.info.status == DownloadStatus.PENDING
            }
        val runningIds = running.map { it.item.id }.toSet()
        val first = itemIds.firstOrNull()

        if (running.isEmpty() || first == null || first in runningIds) {
            val byId = _activeDownloads.value.associate { it.item.id to it.item }
            viewModelScope.launch {
                downloader.reorderQueue(
                    itemIds.filter { it !in runningIds }.mapNotNull { byId[it] }
                )
            }
            return
        }

        // Nothing is saved yet, so the list keeps showing the new order while waiting. Moving
        // things again before the two seconds are up cancels this.
        val next = _activeDownloads.value.firstOrNull { it.item.id == first }?.item ?: return
        startNowJob = viewModelScope.launch {
            delay(START_NOW_DELAY_MS)
            downloader.startNow(next, interrupted = running.first().item, order = itemIds)
        }
    }

    /** Follows the progress of every unfinished download, running ones first, then the queue. */
    private fun observeActiveDownloads(items: List<FindroidItem>) {
        activeDownloadsJob?.cancel()
        val unfinished = items.filter { it.isDownloading() }
        if (unfinished.isEmpty()) {
            _activeDownloads.value = emptyList()
            return
        }
        activeDownloadsJob = viewModelScope.launch {
            combine(
                    unfinished.map { item ->
                        downloader.observeDownload(item.id).map { ActiveDownload(item, it) }
                    }
                ) {
                    it.toList()
                }
                .collect { downloads ->
                    val queue = downloader.queuedItemIds()
                    _activeDownloads.value =
                        downloads
                            .filter {
                                it.info.status != DownloadStatus.NONE &&
                                    it.info.status != DownloadStatus.SUCCESSFUL
                            }
                            .map { download ->
                                // Every paused download can be moved, also one that
                                // isn't in the queue yet
                                val waiting =
                                    (download.item.id in queue &&
                                        download.info.status == DownloadStatus.QUEUED) ||
                                        download.info.status == DownloadStatus.PAUSED
                                download.copy(inQueue = waiting)
                            }
                            .sortedWith(
                                compareBy(
                                    { sortGroup(it) },
                                    {
                                        queue.indexOf(it.item.id).let { index ->
                                            if (index == -1) Int.MAX_VALUE else index
                                        }
                                    },
                                )
                            )
                }
        }
    }

    /**
     * Running first, then the queue in its own order with paused downloads in their place, then
     * downloads outside the queue.
     */
    private fun sortGroup(download: ActiveDownload): Int =
        when {
            download.info.status == DownloadStatus.RUNNING -> 0
            download.info.status == DownloadStatus.PENDING -> 1
            download.inQueue -> 2
            download.info.status == DownloadStatus.PAUSED -> 3
            download.info.status == DownloadStatus.FAILED -> 4
            else -> 5
        }

    /** One section per show, named after the show, with its episodes in season/episode order. */
    private fun episodeSections(episodes: List<FindroidEpisode>): List<CollectionSection> =
        episodes
            .groupBy { it.seriesId }
            .values
            .sortedBy { it.first().seriesName.lowercase() }
            .mapIndexed { index, showEpisodes ->
                CollectionSection(
                    id = EPISODE_SECTION_ID_START + index,
                    name = UiText.DynamicString(showEpisodes.first().seriesName),
                    items =
                        showEpisodes.sortedWith(
                            compareBy<FindroidEpisode>(
                                { it.parentIndexNumber },
                                { it.indexNumber },
                            )
                        ),
                )
            }

    companion object {
        // Keeps per-show section ids apart from the fixed movie/show section ids
        private const val EPISODE_SECTION_ID_START = 1000
    }
}

// How long a download must stay above the running one before it takes over
private const val START_NOW_DELAY_MS = 2_000L
