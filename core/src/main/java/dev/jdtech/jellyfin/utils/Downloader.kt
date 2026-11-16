package dev.jdtech.jellyfin.utils

import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.UiText
import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface Downloader {
    /** Prepares a download and queues the media file. Returns an error, or null on success. */
    suspend fun downloadItem(item: FindroidItem, sourceId: String, storageIndex: Int = 0): UiText?

    /** Stops the download but keeps what was downloaded so far. */
    suspend fun pauseDownload(item: FindroidItem)

    /** Continues a paused or failed download. Returns an error, or null on success. */
    suspend fun resumeDownload(item: FindroidItem): UiText?

    /** Stops the download and removes everything that was downloaded for it. */
    suspend fun cancelDownload(item: FindroidItem)

    suspend fun deleteItem(item: FindroidItem, source: FindroidSource)

    fun observeDownload(itemId: UUID): Flow<DownloadInfo>

    /** Items waiting for another download to finish, next one first. */
    fun queuedItemIds(): List<UUID>
}

enum class DownloadStatus {
    NONE,
    PENDING,
    /** Waiting for another download to finish first. */
    QUEUED,
    RUNNING,
    PAUSED,
    FAILED,
    SUCCESSFUL,
}

data class DownloadInfo(
    val status: DownloadStatus,
    val progress: Float = 0f,
    val errorText: UiText? = null,
    /** True when part of the file is on the device, so the download can continue from there. */
    val canResume: Boolean = false,
)
