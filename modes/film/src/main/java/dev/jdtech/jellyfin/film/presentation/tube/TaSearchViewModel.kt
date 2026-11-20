package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaSearchResults
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaSearchState(
    val query: String = "",
    val results: TaSearchResults? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class TaSearchViewModel @Inject constructor(private val taApi: TaApi) : ViewModel() {
    private val _state = MutableStateFlow(TaSearchState())
    val state = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Watched in the player or on a video's page
        taApi.followWatchedVideos(
            viewModelScope,
            shows = { id -> _state.value.results?.videos?.any { it.youtubeId == id } == true },
        ) { id, player ->
            _state.update { state ->
                state.copy(
                    results =
                        state.results?.let { it.copy(videos = it.videos.withPlayer(id, player)) }
                )
            }
        }
    }

    /** Searches a moment after typing stops. */
    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(results = null, loading = false, error = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DELAY_MS)
            _state.update { it.copy(loading = true, error = null) }
            try {
                val results = taApi.search(query.trim())
                _state.update { it.copy(results = results, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.errorText()) }
            }
        }
    }

    companion object {
        private const val SEARCH_DELAY_MS = 400L
    }
}
