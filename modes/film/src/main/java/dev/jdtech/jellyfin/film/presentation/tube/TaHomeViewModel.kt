package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaOfflineStore
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class TaHomeViewModel
@Inject
constructor(private val taApi: TaApi, private val taOfflineStore: TaOfflineStore) : ViewModel() {
    private val _continueWatching = MutableStateFlow<List<TaVideo>>(emptyList())
    val continueWatching = _continueWatching.asStateFlow()

    private val _latest = MutableStateFlow(TaListState<TaVideo>())
    val latest = _latest.asStateFlow()

    private var lastLoad = 0L

    /** Loads the page the first time, and refreshes it when coming back after a while. */
    fun onShown() {
        // Anything that couldn't be sent before
        viewModelScope.launch { taOfflineStore.sync() }
        val now = System.currentTimeMillis()
        if (_latest.value.page == 0) {
            lastLoad = now
            loadMore()
            loadContinueWatching()
        } else if (now - lastLoad > STALE_AFTER_MS) {
            refresh()
        } else {
            // Progress may have changed while watching
            loadContinueWatching()
        }
    }

    fun refresh() {
        lastLoad = System.currentTimeMillis()
        _latest.value = TaListState()
        loadMore()
        loadContinueWatching()
    }

    fun loadMore() {
        viewModelScope.launch {
            _latest.loadNextPage(key = { it.youtubeId }) { page ->
                taApi.getVideos(page = page, sort = "downloaded", order = "desc")
            }
        }
    }

    private fun loadContinueWatching() {
        viewModelScope.launch {
            try {
                _continueWatching.value = taApi.getVideos(watch = "continue").data
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not load continue watching")
            }
        }
    }

    companion object {
        private const val STALE_AFTER_MS = 60_000L
    }
}
