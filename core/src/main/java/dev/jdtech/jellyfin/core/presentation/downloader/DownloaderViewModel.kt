package dev.jdtech.jellyfin.core.presentation.downloader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.utils.DownloadInfo
import dev.jdtech.jellyfin.utils.DownloadStatus
import dev.jdtech.jellyfin.utils.Downloader
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class DownloaderViewModel @Inject constructor(private val downloader: Downloader) : ViewModel() {
    private val _state = MutableStateFlow(DownloaderState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<DownloaderEvent>()
    val events = eventsChannel.receiveAsFlow()

    private var observedItemId: UUID? = null
    private var observeJob: Job? = null

    // While a download is being set up, the stored state can briefly look like nothing is
    // happening. Only active states are shown during that time.
    private var preparing = false

    /** Starts following the download state of [item]. Safe to call again for the same item. */
    fun update(item: FindroidItem) {
        if (observedItemId == item.id && observeJob?.isActive == true) return
        observedItemId = item.id
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            downloader.observeDownload(item.id).collect { info -> applyInfo(info) }
        }
    }

    private suspend fun applyInfo(info: DownloadInfo) {
        if (
            preparing &&
                info.status != DownloadStatus.PENDING &&
                info.status != DownloadStatus.RUNNING
        ) {
            return
        }
        val previous = _state.value.status
        _state.value =
            DownloaderState(
                status = info.status,
                progress = info.progress,
                errorText = info.errorText,
                canResume = info.canResume,
            )
        if (
            info.status == DownloadStatus.SUCCESSFUL &&
                (previous == DownloadStatus.PENDING || previous == DownloadStatus.RUNNING)
        ) {
            eventsChannel.send(DownloaderEvent.Successful)
        }
    }

    private fun download(item: FindroidItem, storageIndex: Int = 0) {
        update(item)
        viewModelScope.launch {
            _state.value = DownloaderState(status = DownloadStatus.PENDING)
            preparing = true
            val error =
                try {
                    downloader.downloadItem(
                        item = item,
                        sourceId = item.sources.first().id,
                        storageIndex = storageIndex,
                    )
                } finally {
                    preparing = false
                }
            if (error != null) {
                _state.value = DownloaderState(status = DownloadStatus.FAILED, errorText = error)
            }
        }
    }

    private fun pauseDownload(item: FindroidItem) {
        viewModelScope.launch { downloader.pauseDownload(item) }
    }

    private fun resumeDownload(item: FindroidItem) {
        update(item)
        viewModelScope.launch {
            _state.update { it.copy(status = DownloadStatus.PENDING, errorText = null) }
            preparing = true
            val error =
                try {
                    downloader.resumeDownload(item)
                } finally {
                    preparing = false
                }
            if (error != null) {
                _state.update {
                    it.copy(status = DownloadStatus.FAILED, errorText = error, canResume = true)
                }
            }
        }
    }

    private fun cancelDownload(item: FindroidItem) {
        viewModelScope.launch {
            downloader.cancelDownload(item)
            _state.value = DownloaderState()
        }
    }

    private fun deleteDownload(item: FindroidItem) {
        viewModelScope.launch {
            downloader.deleteItem(
                item = item,
                source = item.sources.first { it.type == FindroidSourceType.LOCAL },
            )
            eventsChannel.send(DownloaderEvent.Deleted)
        }
    }

    fun onAction(action: DownloaderAction) {
        when (action) {
            is DownloaderAction.Download -> download(action.item, action.storageIndex)
            is DownloaderAction.DeleteDownload -> deleteDownload(action.item)
            is DownloaderAction.CancelDownload -> cancelDownload(action.item)
            is DownloaderAction.PauseDownload -> pauseDownload(action.item)
            is DownloaderAction.ResumeDownload -> resumeDownload(action.item)
        }
    }
}
