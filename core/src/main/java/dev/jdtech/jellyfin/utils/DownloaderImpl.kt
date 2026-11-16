package dev.jdtech.jellyfin.utils

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.FindroidSources
import dev.jdtech.jellyfin.models.FindroidTrickplayInfo
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.toFindroidEpisodeDto
import dev.jdtech.jellyfin.models.toFindroidMediaStreamDto
import dev.jdtech.jellyfin.models.toFindroidMovieDto
import dev.jdtech.jellyfin.models.toFindroidSeasonDto
import dev.jdtech.jellyfin.models.toFindroidSegmentsDto
import dev.jdtech.jellyfin.models.toFindroidShowDto
import dev.jdtech.jellyfin.models.toFindroidSource
import dev.jdtech.jellyfin.models.toFindroidSourceDto
import dev.jdtech.jellyfin.models.toFindroidTrickplayInfoDto
import dev.jdtech.jellyfin.models.toFindroidUserDataDto
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.ImagesDownloaderWorker
import dev.jdtech.jellyfin.work.MediaDownloadWorker
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

class DownloaderImpl(
    private val context: Context,
    private val database: ServerDatabaseDao,
    private val jellyfinRepository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val workManager: WorkManager,
) : Downloader {
    // Preparing a download keeps going when the screen that started it is closed
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Changed whenever this class changes download state in the database, so observers re-check
    private val changes = MutableStateFlow(0)

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    override suspend fun downloadItem(
        item: FindroidItem,
        sourceId: String,
        storageIndex: Int,
    ): UiText? = scope.async { prepareDownload(item, sourceId, storageIndex) }.await()

    private suspend fun prepareDownload(
        item: FindroidItem,
        sourceId: String,
        storageIndex: Int,
    ): UiText? {
        try {
            val source =
                jellyfinRepository.getMediaSources(item.id, true).first {
                    it.id == sourceId && it.type == FindroidSourceType.REMOTE
                }
            if (source.path.isBlank()) {
                return UiText.StringResource(CoreR.string.downloading_error)
            }
            val segments = jellyfinRepository.getSegments(item.id)
            val trickplayInfo =
                if (item is FindroidSources) {
                    item.trickplayInfo?.get(sourceId)
                } else {
                    null
                }
            val storageLocation = context.getExternalFilesDirs(null).getOrNull(storageIndex)
            if (
                storageLocation == null ||
                    Environment.getExternalStorageState(storageLocation) !=
                        Environment.MEDIA_MOUNTED
            ) {
                return UiText.StringResource(CoreR.string.storage_unavailable)
            }
            val stats = StatFs(storageLocation.path)
            if (stats.availableBytes < source.size) {
                return UiText.StringResource(
                    CoreR.string.not_enough_storage,
                    Formatter.formatFileSize(context, source.size),
                    Formatter.formatFileSize(context, stats.availableBytes),
                )
            }

            val file =
                File(
                    storageLocation,
                    "downloads/${item.id}.${source.id}${MediaDownloadWorker.DOWNLOAD_SUFFIX}",
                )
            file.parentFile?.mkdirs()
            if (file.exists()) {
                file.delete()
            }

            when (item) {
                is FindroidMovie -> {
                    database.insertMovie(
                        item.toFindroidMovieDto(
                            appPreferences.getValue(appPreferences.currentServer),
                        ),
                    )
                }
                is FindroidEpisode -> {
                    val show = jellyfinRepository.getShow(item.seriesId)
                    database.insertShow(
                        show.toFindroidShowDto(
                            appPreferences.getValue(appPreferences.currentServer),
                        ),
                    )
                    val season = jellyfinRepository.getSeason(item.seasonId)
                    database.insertSeason(season.toFindroidSeasonDto())
                    database.insertEpisode(
                        item.toFindroidEpisodeDto(
                            appPreferences.getValue(appPreferences.currentServer),
                        ),
                    )

                    startImagesDownloader(show)
                    startImagesDownloader(season)
                }
            }

            database.insertSource(source.toFindroidSourceDto(item.id, file.path))
            database.insertUserData(item.toFindroidUserDataDto(jellyfinRepository.getUserId()))

            downloadExternalMediaStreams(item, source, storageLocation)

            segments.forEach { database.insertSegment(it.toFindroidSegmentsDto(item.id)) }

            if (trickplayInfo != null) {
                downloadTrickplayData(item.id, sourceId, trickplayInfo)
            }

            startImagesDownloader(item)

            MediaDownloadWorker.clearDownloadState(context, source.id)
            MediaDownloadWorker.saveExpectedBytes(context, source.id, source.size)
            enqueueMediaDownload(
                itemId = item.id,
                sourceId = source.id,
                title = item.name,
                url = source.path,
                policy = ExistingWorkPolicy.REPLACE,
            )
            changes.update { it + 1 }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e)
            try {
                val source = jellyfinRepository.getMediaSources(item.id).first { it.id == sourceId }
                deleteItem(item, source)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {}
            return if (e.message != null) UiText.DynamicString(e.message!!)
            else UiText.StringResource(CoreR.string.unknown_error)
        }
    }

    override suspend fun pauseDownload(item: FindroidItem) {
        withContext(Dispatchers.IO) {
            cancelMediaDownload(item.id)
            changes.update { it + 1 }
        }
    }

    /** Cancels the worker and waits until WorkManager has stored that, so observers see it. */
    private suspend fun cancelMediaDownload(itemId: UUID) {
        val name = MediaDownloadWorker.uniqueWorkName(itemId)
        workManager.cancelUniqueWork(name)
        withTimeoutOrNull(STATE_WAIT_MS) {
            workManager.getWorkInfosForUniqueWorkFlow(name).first { infos ->
                infos.all { it.state.isFinished }
            }
        }
    }

    override suspend fun resumeDownload(item: FindroidItem): UiText? =
        withContext(Dispatchers.IO) {
            val partial =
                database.getSources(item.id).firstOrNull {
                    it.path.endsWith(MediaDownloadWorker.DOWNLOAD_SUFFIX)
                } ?: return@withContext UiText.StringResource(CoreR.string.downloading_error)

            // Ask the server for a fresh address, so an expired login doesn't block the download
            val url =
                try {
                    jellyfinRepository
                        .getMediaSources(item.id, true)
                        .firstOrNull { it.type == FindroidSourceType.REMOTE && it.id == partial.id }
                        ?.path
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e)
                    null
                }
            if (url.isNullOrBlank()) {
                return@withContext UiText.StringResource(
                    CoreR.string.download_error_server_unreachable,
                )
            }

            MediaDownloadWorker.resetFailures(context, partial.id)
            enqueueMediaDownload(
                itemId = item.id,
                sourceId = partial.id,
                title = item.name,
                url = url,
                // Never start a second worker on the file while one is queued or running
                policy = ExistingWorkPolicy.KEEP,
            )
            changes.update { it + 1 }
            null
        }

    override suspend fun cancelDownload(item: FindroidItem) {
        withContext(Dispatchers.IO) {
            cancelMediaDownload(item.id)
            val source = database.getSources(item.id).firstOrNull()?.toFindroidSource(database)
            if (source != null) {
                deleteItem(item, source)
            } else {
                changes.update { it + 1 }
            }
        }
    }

    override suspend fun deleteItem(item: FindroidItem, source: FindroidSource) {
        withContext(Dispatchers.IO) { deleteItemFiles(item, source) }
    }

    private suspend fun deleteItemFiles(item: FindroidItem, source: FindroidSource) {
        cancelMediaDownload(item.id)

        when (item) {
            is FindroidMovie -> {
                database.deleteMovie(item.id)
            }
            is FindroidEpisode -> {
                database.deleteEpisode(item.id)
                val remainingEpisodes = database.getEpisodesBySeasonId(item.seasonId)
                if (remainingEpisodes.isEmpty()) {
                    database.deleteSeason(item.seasonId)
                    database.deleteUserData(item.seasonId)
                    File(context.filesDir, "trickplay/${item.seasonId}").deleteRecursively()
                    File(context.filesDir, "images/${item.seasonId}").deleteRecursively()
                    val remainingSeasons = database.getSeasonsByShowId(item.seriesId)
                    if (remainingSeasons.isEmpty()) {
                        database.deleteShow(item.seriesId)
                        database.deleteUserData(item.seriesId)
                        File(context.filesDir, "trickplay/${item.seriesId}").deleteRecursively()
                        File(context.filesDir, "images/${item.seriesId}").deleteRecursively()
                    }
                }
            }
        }

        database.deleteSource(source.id)
        File(source.path).delete()
        MediaDownloadWorker.clearDownloadState(context, source.id)

        val mediaStreams = database.getMediaStreamsBySourceId(source.id)
        for (mediaStream in mediaStreams) {
            File(mediaStream.path).delete()
        }
        database.deleteMediaStreamsBySourceId(source.id)

        database.deleteUserData(item.id)

        File(context.filesDir, "trickplay/${item.id}").deleteRecursively()
        File(context.filesDir, "images/${item.id}").deleteRecursively()

        changes.update { it + 1 }
    }

    override fun observeDownload(itemId: UUID): Flow<DownloadInfo> =
        combine(
                workManager.getWorkInfosForUniqueWorkFlow(
                    MediaDownloadWorker.uniqueWorkName(itemId),
                ),
                changes,
            ) { workInfos, _ ->
                workInfos.firstOrNull { !it.state.isFinished } ?: workInfos.lastOrNull()
            }
            .map { workInfo -> toDownloadInfo(itemId, workInfo) }
            .flowOn(Dispatchers.IO)

    private suspend fun toDownloadInfo(itemId: UUID, workInfo: WorkInfo?): DownloadInfo {
        val state = workInfo?.state

        if (workInfo != null && state == WorkInfo.State.RUNNING) {
            val downloaded = workInfo.progress.getLong(MediaDownloadWorker.PROGRESS_DOWNLOADED, -1L)
            val total = workInfo.progress.getLong(MediaDownloadWorker.PROGRESS_TOTAL, -1L)
            if (downloaded >= 0) {
                return DownloadInfo(
                    status = DownloadStatus.RUNNING,
                    progress = progressOf(downloaded, total),
                    canResume = true,
                )
            }
        }

        val partial =
            database.getSources(itemId).firstOrNull {
                it.path.endsWith(MediaDownloadWorker.DOWNLOAD_SUFFIX)
            }
        if (partial == null) {
            return if (state == WorkInfo.State.SUCCEEDED) {
                DownloadInfo(status = DownloadStatus.SUCCESSFUL, progress = 1f)
            } else {
                DownloadInfo(status = DownloadStatus.NONE)
            }
        }

        val progress =
            progressOf(
                File(partial.path).length(),
                MediaDownloadWorker.getExpectedBytes(context, partial.id),
            )
        return when (state) {
            WorkInfo.State.RUNNING ->
                DownloadInfo(DownloadStatus.RUNNING, progress = progress, canResume = true)
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED ->
                DownloadInfo(DownloadStatus.PENDING, progress = progress, canResume = true)
            WorkInfo.State.FAILED ->
                DownloadInfo(
                    status = DownloadStatus.FAILED,
                    progress = progress,
                    errorText =
                        workInfo
                            ?.outputData
                            ?.let { MediaDownloadWorker.errorMessage(it) }
                            ?.let { UiText.DynamicString(it) },
                    canResume = true,
                )
            else -> DownloadInfo(DownloadStatus.PAUSED, progress = progress, canResume = true)
        }
    }

    private fun progressOf(downloaded: Long, total: Long): Float =
        if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f

    private suspend fun enqueueMediaDownload(
        itemId: UUID,
        sourceId: String,
        title: String,
        url: String,
        policy: ExistingWorkPolicy,
    ) {
        val networkType =
            when {
                !appPreferences.getValue(appPreferences.downloadOverMobileData) ->
                    NetworkType.UNMETERED
                !appPreferences.getValue(appPreferences.downloadWhenRoaming) ->
                    NetworkType.NOT_ROAMING
                // No constraint: the worker retries by itself when the server can't be reached
                else -> NetworkType.NOT_REQUIRED
            }
        val request =
            OneTimeWorkRequestBuilder<MediaDownloadWorker>()
                .setInputData(
                    workDataOf(
                        MediaDownloadWorker.KEY_ITEM_ID to itemId.toString(),
                        MediaDownloadWorker.KEY_SOURCE_ID to sourceId,
                        MediaDownloadWorker.KEY_URL to url,
                        MediaDownloadWorker.KEY_TITLE to title,
                    ),
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(MediaDownloadWorker.TAG)
                .build()
        val name = MediaDownloadWorker.uniqueWorkName(itemId)
        workManager.enqueueUniqueWork(name, policy, request)
        // Wait until WorkManager has stored the request, so observers see it straight away
        withTimeoutOrNull(STATE_WAIT_MS) {
            workManager.getWorkInfosForUniqueWorkFlow(name).first { infos ->
                infos.any { !it.state.isFinished }
            }
        }
    }

    companion object {
        private const val STATE_WAIT_MS = 2000L
    }

    /**
     * Subtitle files are small, so they are downloaded right away. A subtitle that fails is skipped
     * instead of failing the whole download.
     */
    private suspend fun downloadExternalMediaStreams(
        item: FindroidItem,
        source: FindroidSource,
        storageLocation: File,
    ) {
        for (mediaStream in source.mediaStreams.filter { it.isExternal }) {
            val url = mediaStream.path
            if (url.isNullOrBlank()) continue
            val id = UUID.randomUUID()
            val file = File(storageLocation, "downloads/${item.id}.${source.id}.$id")
            val downloaded = withContext(Dispatchers.IO) { downloadSmallFile(url, file) }
            if (downloaded) {
                database.insertMediaStream(
                    mediaStream.toFindroidMediaStreamDto(id, source.id, file.path),
                )
            }
        }
    }

    private fun downloadSmallFile(url: String, file: File): Boolean {
        val temp = File(file.path + MediaDownloadWorker.DOWNLOAD_SUFFIX)
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Failed to download ${file.name}: HTTP ${response.code}")
                    return false
                }
                temp.outputStream().use { output ->
                    response.body.byteStream().use { input -> input.copyTo(output) }
                }
            }
            if (file.exists()) {
                file.delete()
            }
            return temp.renameTo(file)
        } catch (e: IOException) {
            Timber.e(e)
            temp.delete()
            return false
        } catch (e: IllegalArgumentException) {
            Timber.e(e)
            temp.delete()
            return false
        }
    }

    private suspend fun downloadTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: FindroidTrickplayInfo,
    ) {
        val maxIndex =
            ceil(
                    trickplayInfo.thumbnailCount
                        .toDouble()
                        .div(trickplayInfo.tileWidth * trickplayInfo.tileHeight),
                )
                .toInt()
        val byteArrays = mutableListOf<ByteArray>()
        for (i in 0..maxIndex) {
            jellyfinRepository.getTrickplayData(itemId, trickplayInfo.width, i)?.let { byteArray ->
                byteArrays.add(byteArray)
            }
        }
        saveTrickplayData(itemId, sourceId, trickplayInfo, byteArrays)
    }

    private suspend fun saveTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: FindroidTrickplayInfo,
        byteArrays: List<ByteArray>,
    ) {
        val basePath = "trickplay/$itemId/$sourceId"
        database.insertTrickplayInfo(trickplayInfo.toFindroidTrickplayInfoDto(sourceId))
        File(context.filesDir, basePath).mkdirs()
        for ((i, byteArray) in byteArrays.withIndex()) {
            val file = File(context.filesDir, "$basePath/$i")
            file.writeBytes(byteArray)
        }
    }

    private fun startImagesDownloader(item: FindroidItem) {
        val downloadImagesRequest =
            OneTimeWorkRequestBuilder<ImagesDownloaderWorker>()
                .setInputData(workDataOf(ImagesDownloaderWorker.KEY_ITEM_ID to item.id.toString()))
                .build()

        workManager.enqueue(downloadImagesRequest)
    }
}
