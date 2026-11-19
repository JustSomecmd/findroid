package dev.jdtech.jellyfin.film.presentation.tube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaBiggestChannel
import dev.jdtech.jellyfin.tubearchivist.TaBiggestOrder
import dev.jdtech.jellyfin.tubearchivist.TaCountStats
import dev.jdtech.jellyfin.tubearchivist.TaDownloadDay
import dev.jdtech.jellyfin.tubearchivist.TaDownloadStats
import dev.jdtech.jellyfin.tubearchivist.TaVideoStats
import dev.jdtech.jellyfin.tubearchivist.TaWatchStats
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** The numbers on TubeArchivist's dashboard. Each part is null when it couldn't be loaded. */
data class TaServerStats(
    val videos: TaVideoStats? = null,
    val watch: TaWatchStats? = null,
    val channels: TaCountStats? = null,
    val playlists: TaCountStats? = null,
    val downloads: TaDownloadStats? = null,
    val history: List<TaDownloadDay>? = null,
    val biggest: Map<TaBiggestOrder, List<TaBiggestChannel>> = emptyMap(),
    /** Sizes in GB rather than GiB, as set in TubeArchivist. */
    val metricSizes: Boolean = false,
    val loading: Boolean = false,
    /** True once the stats were asked for, whether or not they came. */
    val loaded: Boolean = false,
    /** Set when nothing at all could be loaded. */
    val error: String? = null,
)

/** The server's stats, for the server side of Home. */
@HiltViewModel
class TaStatsViewModel @Inject constructor(private val taApi: TaApi) : ViewModel() {
    private val _stats = MutableStateFlow(TaServerStats())
    val stats = _stats.asStateFlow()

    private var lastLoad = 0L

    /** Loads the stats the first time they're shown, and again when they're a minute old. */
    fun onShown() {
        if (_stats.value.loading) return
        if (System.currentTimeMillis() - lastLoad < STALE_AFTER_MS && _stats.value.error == null) {
            return
        }
        refresh()
    }

    fun refresh() {
        lastLoad = System.currentTimeMillis()
        _stats.update { it.copy(loading = true) }
        viewModelScope.launch {
            coroutineScope {
                // Everything at once, each on its own, as some answer with an error while they
                // have nothing to count
                val videos = async { part("videos") { taApi.getVideoStats() } }
                val watch = async { part("watch") { taApi.getWatchStats() } }
                val channels = async { part("channels") { taApi.getChannelStats() } }
                val playlists = async { part("playlists") { taApi.getPlaylistStats() } }
                val downloads = async { part("downloads") { taApi.getDownloadStats() } }
                val history = async { part("history") { taApi.getDownloadHistory() } }
                val biggest =
                    TaBiggestOrder.entries.map { order ->
                        async { order to part("biggest") { taApi.getBiggestChannels(order) } }
                    }
                val metric = async { taApi.usesMetricSizes() }

                val parts =
                    listOf(videos, watch, channels, playlists, downloads, history).awaitAll()
                val error = parts.firstOrNull { it.isFailure }?.exceptionOrNull()
                _stats.value =
                    TaServerStats(
                        videos = videos.await().getOrNull(),
                        watch = watch.await().getOrNull(),
                        channels = channels.await().getOrNull(),
                        playlists = playlists.await().getOrNull(),
                        downloads = downloads.await().getOrNull(),
                        history = history.await().getOrNull(),
                        biggest =
                            biggest
                                .awaitAll()
                                .mapNotNull { (order, result) ->
                                    result.getOrNull()?.let { order to it }
                                }
                                .toMap(),
                        metricSizes = metric.await(),
                        loading = false,
                        loaded = true,
                        error = if (parts.all { it.isFailure }) error?.errorText() ?: "" else null,
                    )
            }
        }
    }

    private suspend fun <T> part(name: String, load: suspend () -> T): Result<T> =
        try {
            Result.success(load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not load the $name stats")
            Result.failure(e)
        }

    companion object {
        private const val STALE_AFTER_MS = 60_000L
    }
}
