package dev.jdtech.jellyfin.core.presentation.downloader

import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.utils.DownloadStatus

data class DownloaderState(
    val status: DownloadStatus = DownloadStatus.NONE,
    val progress: Float = 0f,
    val errorText: UiText? = null,
    /** True when part of the file is on the device, so the download can continue from there. */
    val canResume: Boolean = false,
) {
    val isDownloading: Boolean
        get() =
            status in
                arrayOf(
                    DownloadStatus.PENDING,
                    DownloadStatus.RUNNING,
                    DownloadStatus.PAUSED,
                    DownloadStatus.FAILED,
                )
}
