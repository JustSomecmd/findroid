package dev.jdtech.jellyfin.film.presentation.downloads

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidServerSize
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.utils.Downloader
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/** A show or movie with downloads, and the space they take on this phone. */
data class StorageEntry(
    /** The show or the movie. */
    val item: FindroidItem,
    /** The downloaded episodes, or the movie itself. */
    val downloads: List<FindroidItem>,
    val localBytes: Long,
)

/** What is downloaded to this phone. */
data class StorageState(
    val isLoading: Boolean = true,
    val entries: List<StorageEntry> = emptyList(),
    /** Space taken by all downloads together. */
    val totalBytes: Long = 0L,
    /** Size of the storage where downloads go, or 0 when unknown. */
    val deviceTotalBytes: Long = 0L,
    val deviceFreeBytes: Long = 0L,
)

/** A show on the server and the space its episodes take there. */
data class ServerShowEntry(val show: FindroidShow, val size: FindroidServerSize)

/** How much space the shows take on the server. */
data class ServerStorageState(
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false,
    val failed: Boolean = false,
    /** Episodes counted so far and how many there are, while loading. */
    val counted: Int = 0,
    val total: Int = 0,
    /** Biggest first. */
    val shows: List<ServerShowEntry> = emptyList(),
    val totalBytes: Long = 0L,
    val episodeCount: Int = 0,
)

@HiltViewModel
class StorageViewModel
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: JellyfinRepository,
    private val downloader: Downloader,
) : ViewModel() {
    private val _state = MutableStateFlow(StorageState())
    val state = _state.asStateFlow()

    private val _serverState = MutableStateFlow(ServerStorageState())
    val serverState = _serverState.asStateFlow()

    private var serverJob: Job? = null

    fun load() {
        viewModelScope.launch {
            val (entries, device) =
                withContext(Dispatchers.IO) {
                    val downloads = repository.getDownloads()
                    val episodesByShow = repository.getDownloadedEpisodes().groupBy { it.seriesId }

                    val shows =
                        downloads.filterIsInstance<FindroidShow>().mapNotNull { show ->
                            val showEpisodes = episodesByShow[show.id] ?: return@mapNotNull null
                            StorageEntry(
                                item = show,
                                downloads = showEpisodes,
                                localBytes = showEpisodes.sumOf { localSize(it) },
                            )
                        }
                    val movies =
                        downloads.filterIsInstance<FindroidMovie>().map { movie ->
                            StorageEntry(
                                item = movie,
                                downloads = listOf(movie),
                                localBytes = localSize(movie),
                            )
                        }

                    (shows + movies).sortedByDescending { it.localBytes } to deviceSpace()
                }

            _state.update {
                it.copy(
                    isLoading = false,
                    entries = entries,
                    totalBytes = entries.sumOf { entry -> entry.localBytes },
                    deviceTotalBytes = device.first,
                    deviceFreeBytes = device.second,
                )
            }
        }
    }

    /** Adds up every show on the server. Only runs once, unless [retry] is set after a failure. */
    fun loadServer(retry: Boolean = false) {
        val current = _serverState.value
        if (current.isLoading || (current.isLoaded && !retry)) return

        serverJob?.cancel()
        _serverState.value = ServerStorageState(isLoading = true)
        serverJob = viewModelScope.launch {
            val sizes = repository.getServerShowSizes { counted, total ->
                _serverState.update { it.copy(counted = counted, total = total) }
            }
            val shows =
                try {
                    repository
                        .getItems(includeTypes = listOf(BaseItemKind.SERIES), recursive = true)
                        .filterIsInstance<FindroidShow>()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e)
                    null
                }

            if (sizes == null || shows == null) {
                _serverState.value = ServerStorageState(failed = true)
                return@launch
            }

            val entries =
                shows
                    .map { show ->
                        ServerShowEntry(
                            show = show,
                            size = sizes[show.id] ?: FindroidServerSize(0, 0L),
                        )
                    }
                    .sortedByDescending { it.size.bytes }

            _serverState.value =
                ServerStorageState(
                    isLoaded = true,
                    shows = entries,
                    totalBytes = entries.sumOf { it.size.bytes },
                    episodeCount = entries.sumOf { it.size.episodeCount },
                )
        }
    }

    /** Deletes every download of a show, or a downloaded movie. */
    fun delete(entry: StorageEntry) {
        viewModelScope.launch {
            for (download in entry.downloads) {
                val source =
                    download.sources.firstOrNull { it.type == FindroidSourceType.LOCAL } ?: continue
                try {
                    downloader.deleteItem(download, source)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
            load()
        }
    }

    /** The space one downloaded item takes: the video, its subtitles, and its preview pictures. */
    private fun localSize(item: FindroidItem): Long {
        var bytes = 0L
        for (source in item.sources.filter { it.type == FindroidSourceType.LOCAL }) {
            bytes += File(source.path).length()
            for (mediaStream in source.mediaStreams) {
                val path = mediaStream.path ?: continue
                if (path.startsWith("/")) bytes += File(path).length()
            }
        }
        bytes += folderSize(File(context.filesDir, "trickplay/${item.id}"))
        bytes += folderSize(File(context.filesDir, "images/${item.id}"))
        return bytes
    }

    private fun folderSize(folder: File): Long =
        if (folder.exists()) folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    /** Total and free space of the storage where downloads go by default. */
    private fun deviceSpace(): Pair<Long, Long> {
        val location =
            context.getExternalFilesDirs(null).firstOrNull { dir ->
                dir != null && Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED
            } ?: return 0L to 0L
        return try {
            val stats = StatFs(location.path)
            stats.totalBytes to stats.availableBytes
        } catch (e: IllegalArgumentException) {
            Timber.e(e)
            0L to 0L
        }
    }
}
