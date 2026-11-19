package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaDownloadItem
import dev.jdtech.jellyfin.tubearchivist.TaNotification
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalDownload
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.ConfirmDialog
import dev.jdtech.tubedroid.desktop.ui.ListFooter
import dev.jdtech.tubedroid.desktop.ui.ListScrollbar
import dev.jdtech.tubedroid.desktop.ui.LoadMoreEffect
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PagedList
import dev.jdtech.tubedroid.desktop.ui.ServerSideTheme
import dev.jdtech.tubedroid.desktop.ui.SideSwitch
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.TaImage
import dev.jdtech.tubedroid.desktop.ui.errorText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class QueueFilter(val label: String, val apiValue: String) {
    PENDING("Pending", "pending"),
    IGNORED("Ignored", "ignore"),
}

/** TubeArchivist's download queue, and what it's doing right now. */
class QueueModel(private val api: TaApi, private val scope: CoroutineScope) {
    var filter by mutableStateOf(QueueFilter.PENDING)
        private set

    var onServer by mutableStateOf(false)
    var notifications by mutableStateOf<List<TaNotification>>(emptyList())
        private set

    val lists =
        QueueFilter.entries.associateWith { filter ->
            PagedList(scope, key = TaDownloadItem::youtubeId) { page ->
                api.getDownloads(page = page, filter = filter.apiValue)
            }
        }
    val listState = LazyListState()
    val localListState = LazyListState()

    fun select(next: QueueFilter) {
        filter = next
    }

    /** Keeps the progress of TubeArchivist's tasks current while the queue is shown. */
    suspend fun followNotifications() {
        while (true) {
            val before = notifications
            val now =
                try {
                    api.getNotifications()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    before
                }
            notifications = now
            val downloadBefore = before.firstOrNull { it.group == DOWNLOAD_GROUP }?.title
            val download = now.firstOrNull { it.group == DOWNLOAD_GROUP }?.title
            // A task finished or the next video started, so the queue has changed
            if (now.size < before.size || (downloadBefore != null && download != downloadBefore)) {
                lists.getValue(filter).reload()
            }
            delay(if (now.isEmpty()) 5000 else 1500)
        }
    }

    fun run(
        onMessage: (String) -> Unit,
        message: String? = null,
        reload: Boolean = true,
        action: suspend () -> Unit,
    ) {
        scope.launch {
            try {
                action()
                message?.let(onMessage)
                if (reload) lists.getValue(filter).reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(errorText(e))
            }
        }
    }

    fun add(links: List<String>, now: Boolean, onMessage: (String) -> Unit) =
        run(onMessage, "TubeArchivist is adding them to the queue") {
            api.addToQueue(links, autostart = now)
        }

    fun startDownloads(onMessage: (String) -> Unit) =
        run(onMessage, "Started downloading", reload = false) { api.startTask("download_pending") }

    fun rescan(onMessage: (String) -> Unit) =
        run(onMessage, "Looking for new videos on your subscriptions", reload = false) {
            api.startTask("update_subscribed")
        }

    fun downloadNow(item: TaDownloadItem, onMessage: (String) -> Unit) =
        run(onMessage, "Moved to the top of the queue") {
            api.setDownloadStatus(item.youtubeId, "priority")
        }

    fun ignore(item: TaDownloadItem, onMessage: (String) -> Unit) =
        run(onMessage) { api.setDownloadStatus(item.youtubeId, "ignore") }

    fun addBack(item: TaDownloadItem, onMessage: (String) -> Unit) =
        run(onMessage) { api.setDownloadStatus(item.youtubeId, "pending") }

    fun remove(item: TaDownloadItem, onMessage: (String) -> Unit) =
        run(onMessage) { api.deleteDownload(item.youtubeId) }

    fun clear(onMessage: (String) -> Unit) = run(onMessage) { api.clearQueue(filter.apiValue) }

    /** Clears the errors of the videos that failed, so the next run tries them again. */
    fun clearErrors(onMessage: (String) -> Unit) =
        run(onMessage, "Errors cleared, they'll be tried again next time") {
            api.clearQueueErrors(filter.apiValue)
        }

    private companion object {
        const val DOWNLOAD_GROUP = "download:run"
    }
}

@Composable
fun DownloadsScreen() {
    val app = LocalApp.current
    val model = rememberPageModel("queue") { scope -> QueueModel(app.api, scope) }
    ServerSideTheme(onServer = model.onServer) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                PageBar(title = "Downloads", onBack = null) {
                    SideSwitch(
                        onServer = model.onServer,
                        onToggle = { model.onServer = !model.onServer },
                        localLabel = "This PC",
                    )
                }
                AnimatedContent(
                    targetState = model.onServer,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "downloads side",
                ) { server ->
                    if (server) QueueContent(model) else LocalDownloads(model.localListState)
                }
            }
        }
    }
}

/** The videos saved to this computer in this session. */
@Composable
private fun LocalDownloads(state: LazyListState) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val items = app.downloads.items
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(Spacings.default),
            verticalArrangement = Arrangement.spacedBy(Spacings.small),
        ) {
            item(key = "folder") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = Spacings.small),
                ) {
                    Text(
                        text = "Videos are saved to ${app.settings.downloadFolder}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            runCatching {
                                java.awt.Desktop.getDesktop()
                                    .open(java.io.File(app.settings.downloadFolder))
                            }
                        }
                    ) {
                        Text("Open folder")
                    }
                }
            }
            if (items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "Nothing downloaded yet. A video's download button saves it here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacings.large),
                    )
                }
            }
            items(items, key = { it.videoId }) { download ->
                LocalDownloadRow(
                    download = download,
                    onOpen = { navigator.open(Route.Video(download.videoId)) },
                    onCancel = { app.downloads.cancel(download.videoId) },
                    onForget = { app.downloads.forget(download.videoId) },
                )
            }
        }
        ListScrollbar(state)
    }
}

@Composable
private fun LocalDownloadRow(
    download: LocalDownload,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onForget: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.widthIn(max = 1000.dp).fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(Spacings.medium),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = download.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when (download.state) {
                    LocalDownload.State.RUNNING -> {
                        val progress = download.progress
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier =
                                    Modifier.fillMaxWidth().padding(vertical = Spacings.small),
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier =
                                    Modifier.fillMaxWidth().padding(vertical = Spacings.small)
                            )
                        }
                    }
                    LocalDownload.State.DONE -> Detail(download.file.path)
                    LocalDownload.State.FAILED -> Detail("Failed: ${download.error.orEmpty()}")
                    LocalDownload.State.CANCELLED -> Detail("Cancelled")
                }
            }
            Spacer(Modifier.width(Spacings.medium))
            TextButton(onClick = onOpen) { Text("Video page") }
            when (download.state) {
                LocalDownload.State.RUNNING -> TextButton(onClick = onCancel) { Text("Cancel") }
                LocalDownload.State.DONE -> {
                    TextButton(
                        onClick = {
                            runCatching { java.awt.Desktop.getDesktop().open(download.file) }
                        }
                    ) {
                        Text("Open")
                    }
                    TextButton(onClick = onForget) { Text("Remove from list") }
                }
                else -> TextButton(onClick = onForget) { Text("Remove from list") }
            }
        }
    }
}

@Composable
private fun Detail(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** TubeArchivist's download queue: what's waiting or ignored, and adding more. */
@Composable
private fun QueueContent(model: QueueModel) {
    val navigator = LocalNavigator.current
    var dialog by remember { mutableStateOf<String?>(null) }
    val list = model.lists.getValue(model.filter)
    LaunchedEffect(Unit) { model.followNotifications() }
    LaunchedEffect(model.filter) { if (list.items.isEmpty() && !list.endReached) list.loadMore() }
    LoadMoreEffect(model.listState) { model.lists.getValue(model.filter).loadMore() }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = model.listState,
            contentPadding = PaddingValues(Spacings.default),
            verticalArrangement = Arrangement.spacedBy(Spacings.small),
        ) {
            item(key = "actions") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacings.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QueueFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = model.filter == filter,
                            onClick = { model.select(filter) },
                            label = { Text(filter.label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = { dialog = "add" }) { Text("Add to queue") }
                    OutlinedButton(onClick = { model.startDownloads(navigator::message) }) {
                        Text("Start downloading")
                    }
                    OutlinedButton(onClick = { model.rescan(navigator::message) }) {
                        Text("Rescan subscriptions")
                    }
                    if (model.filter == QueueFilter.PENDING) {
                        TextButton(onClick = { model.clearErrors(navigator::message) }) {
                            Text("Clear errors")
                        }
                    }
                    TextButton(onClick = { dialog = "clear" }, enabled = list.items.isNotEmpty()) {
                        Text("Remove all")
                    }
                }
            }
            items(model.notifications, key = { "n" + it.id }) { notification ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(Spacings.medium)) {
                        Text(text = notification.title, style = MaterialTheme.typography.titleSmall)
                        notification.messages.forEach {
                            Text(text = it, style = MaterialTheme.typography.bodySmall)
                        }
                        notification.progress?.let { progress ->
                            LinearProgressIndicator(
                                progress = { progress.toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = Spacings.small),
                            )
                        }
                    }
                }
            }
            items(list.items, key = { it.youtubeId }) { item ->
                QueueRow(
                    item = item,
                    ignored = model.filter == QueueFilter.IGNORED,
                    onDownloadNow = { model.downloadNow(item, navigator::message) },
                    onIgnore = { model.ignore(item, navigator::message) },
                    onAddBack = { model.addBack(item, navigator::message) },
                    onRemove = { model.remove(item, navigator::message) },
                    onOpenChannel = { navigator.open(Route.Channel(it)) },
                )
            }
            item(key = "footer") {
                ListFooter(
                    list,
                    emptyText =
                        if (model.filter == QueueFilter.PENDING) "Nothing in the queue"
                        else "Nothing ignored",
                )
            }
        }
        ListScrollbar(model.listState)
    }

    when (dialog) {
        "add" ->
            TextInputDialog(
                title = "Add to the queue",
                label = "Video, playlist or channel links, one per line",
                confirmLabel = "Add",
                multiLine = true,
                onConfirm = { text ->
                    val links = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (links.isNotEmpty())
                        model.add(links, now = false, onMessage = navigator::message)
                },
                onDismiss = { dialog = null },
            )
        "clear" ->
            ConfirmDialog(
                title = "Remove all ${model.filter.label.lowercase()} videos?",
                text = "Everything in this list is removed from TubeArchivist's queue.",
                confirmLabel = "Remove all",
                onConfirm = { model.clear(navigator::message) },
                onDismiss = { dialog = null },
            )
    }
}

@Composable
private fun QueueRow(
    item: TaDownloadItem,
    ignored: Boolean,
    onDownloadNow: () -> Unit,
    onIgnore: () -> Unit,
    onAddBack: () -> Unit,
    onRemove: () -> Unit,
    onOpenChannel: (channelId: String) -> Unit,
) {
    // Clicking it opens the video's channel, as on the phone
    val channelId = item.channelId?.takeIf { it.isNotBlank() }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier =
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(
                enabled = channelId != null
            ) {
                channelId?.let(onOpenChannel)
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(Spacings.small),
        ) {
            TaImage(
                path = item.thumbUrl,
                modifier =
                    Modifier.width(144.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small),
            )
            Spacer(Modifier.width(Spacings.medium))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        listOfNotNull(item.channelName, item.duration, item.published)
                            .joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                item.message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (ignored) {
                TextButton(onClick = onAddBack) { Text("Add back") }
            } else {
                TextButton(onClick = onDownloadNow) {
                    AppIcon(
                        name = "ic_download",
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Download now")
                }
                TextButton(onClick = onIgnore) { Text("Ignore") }
            }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}
