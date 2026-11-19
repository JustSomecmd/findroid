package dev.jdtech.tubedroid.desktop.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.tubearchivist.TaApi
import dev.jdtech.jellyfin.tubearchivist.TaComment
import dev.jdtech.jellyfin.tubearchivist.TaPlaylist
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.tubedroid.desktop.LocalApp
import dev.jdtech.tubedroid.desktop.LocalCanGoBack
import dev.jdtech.tubedroid.desktop.LocalDownload
import dev.jdtech.tubedroid.desktop.LocalNavigator
import dev.jdtech.tubedroid.desktop.Route
import dev.jdtech.tubedroid.desktop.player.PlayerRequest
import dev.jdtech.tubedroid.desktop.rememberPageModel
import dev.jdtech.tubedroid.desktop.ui.AppIcon
import dev.jdtech.tubedroid.desktop.ui.ColumnScrollbar
import dev.jdtech.tubedroid.desktop.ui.ConfirmDialog
import dev.jdtech.tubedroid.desktop.ui.InfoRow
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PageState
import dev.jdtech.tubedroid.desktop.ui.SectionCard
import dev.jdtech.tubedroid.desktop.ui.SectionTitle
import dev.jdtech.tubedroid.desktop.ui.ServerSideTheme
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.TaAvatar
import dev.jdtech.tubedroid.desktop.ui.VideoRow
import dev.jdtech.tubedroid.desktop.ui.VideoThumbnail
import dev.jdtech.tubedroid.desktop.ui.anyDate
import dev.jdtech.tubedroid.desktop.ui.errorText
import dev.jdtech.tubedroid.desktop.ui.formatCount
import dev.jdtech.tubedroid.desktop.ui.formatDuration
import dev.jdtech.tubedroid.desktop.ui.formatSize
import dev.jdtech.tubedroid.desktop.ui.fullDate
import dev.jdtech.tubedroid.desktop.ui.rememberLinkedText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class VideoModel(private val api: TaApi, private val scope: CoroutineScope, val videoId: String) {
    var video by mutableStateOf<TaVideo?>(null)
        private set

    var comments by mutableStateOf<List<TaComment>?>(null)
        private set

    var similar by mutableStateOf<List<TaVideo>>(emptyList())
        private set

    var customPlaylists by mutableStateOf<List<TaPlaylist>?>(null)
        private set

    var metricSizes by mutableStateOf(false)
        private set

    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var onServer by mutableStateOf(false)
    var deleted by mutableStateOf(false)
        private set

    val scroll = ScrollState(0)
    val serverScroll = ScrollState(0)

    init {
        load()
    }

    fun load() {
        loading = true
        error = null
        scope.launch {
            try {
                video = api.getVideo(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
        scope.launch { comments = quietly { api.getComments(videoId) } ?: emptyList() }
        scope.launch { similar = quietly { api.getSimilar(videoId) }.orEmpty() }
        scope.launch { metricSizes = api.usesMetricSizes() }
    }

    fun setWatched(watched: Boolean, onMessage: (String) -> Unit) {
        scope.launch {
            try {
                api.setWatched(videoId, watched)
                video = api.getVideo(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(errorText(e))
            }
        }
    }

    fun loadCustomPlaylists() {
        scope.launch {
            customPlaylists = quietly { api.getPlaylists(type = "custom").data }.orEmpty()
        }
    }

    fun setInPlaylist(playlist: TaPlaylist, inPlaylist: Boolean, onMessage: (String) -> Unit) {
        scope.launch {
            try {
                val updated =
                    if (inPlaylist) api.addToCustomPlaylist(playlist.id, videoId)
                    else api.removeFromCustomPlaylist(playlist.id, videoId)
                customPlaylists = customPlaylists?.map { if (it.id == updated.id) updated else it }
                onMessage(
                    if (inPlaylist) "Added to ${playlist.name}" else "Taken out of ${playlist.name}"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(errorText(e))
            }
        }
    }

    fun createPlaylist(name: String, onMessage: (String) -> Unit) {
        scope.launch {
            try {
                val created = api.createCustomPlaylist(name)
                val withVideo = api.addToCustomPlaylist(created.id, videoId)
                customPlaylists = customPlaylists.orEmpty() + withVideo
                onMessage("Made ${created.name} and added the video")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(errorText(e))
            }
        }
    }

    fun runAction(action: VideoAction, onMessage: (String) -> Unit) {
        scope.launch {
            try {
                onMessage(api.runVideoAction(action, listOf(videoId)))
                if (action == VideoAction.DELETE) deleted = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage(errorText(e))
            }
        }
    }

    private suspend fun <T> quietly(load: suspend () -> T): T? =
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
}

@Composable
fun VideoScreen(videoId: String, playlistId: String?) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    val model = rememberPageModel("video") { scope -> VideoModel(app.api, scope, videoId) }
    val canGoBack = LocalCanGoBack.current

    ServerSideTheme(onServer = model.onServer) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                PageBar(title = null, onBack = if (canGoBack) navigator::back else null) {
                    // The video on the server, in the server's colors
                    FilterChip(
                        selected = model.onServer,
                        onClick = { model.onServer = !model.onServer },
                        label = { Text("Manage") },
                        leadingIcon = {
                            AppIcon(
                                name = "ic_server",
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    OpenInWebButton("/video/$videoId/")
                }
                val video = model.video
                when {
                    model.deleted ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("This video was deleted from the server.")
                        }
                    video == null ->
                        PageState(
                            loading = model.loading,
                            error = model.error,
                            onRetry = model::load,
                        )
                    else ->
                        AnimatedContent(
                            targetState = model.onServer,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "video side",
                        ) { server ->
                            if (server) VideoServerSide(model, video)
                            else
                                VideoContent(
                                    model = model,
                                    video = video,
                                    onPlay = { fromStart ->
                                        navigator.play(
                                            PlayerRequest.Ta(video.youtubeId, playlistId, fromStart)
                                        )
                                    },
                                )
                        }
                }
            }
        }
    }
}

/**
 * The thumbnail on the left with the title, channel and buttons beside it, then the description and
 * comments, with similar videos down the right.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoContent(model: VideoModel, video: TaVideo, onPlay: (fromStart: Boolean) -> Unit) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    var saveDialog by remember { mutableStateOf(false) }
    var descriptionExpanded by rememberSaveable { mutableStateOf(false) }
    val download = app.downloads.stateOf(video.youtubeId)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(model.scroll).padding(Spacings.default)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacings.default)) {
                Box(
                    modifier =
                        Modifier.weight(0.5f).widthIn(max = 720.dp).clickable { onPlay(false) }
                ) {
                    VideoThumbnail(video = video, modifier = Modifier.fillMaxWidth())
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                        modifier = Modifier.align(Alignment.Center).size(64.dp),
                    ) {
                        AppIcon(
                            name = "ic_play",
                            contentDescription = "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(18.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(0.5f)) {
                    Text(text = video.title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(Spacings.extraSmall))
                    Text(
                        text = videoDetails(video),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    video.channel?.let { channel ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.padding(vertical = Spacings.medium)
                                    .clip(MaterialTheme.shapes.medium)
                                    .clickable { navigator.open(Route.Channel(channel.id)) }
                                    .padding(Spacings.extraSmall),
                        ) {
                            TaAvatar(path = channel.thumbUrl, size = 44.dp)
                            Spacer(Modifier.width(Spacings.medium))
                            Column {
                                Text(
                                    text = channel.name,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                channel.subs?.let {
                                    Text(
                                        text = "${formatCount(it)} subscribers",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    val player = video.player
                    val position = player?.position?.takeIf { player.watched.not() && it > 5 }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacings.small),
                        verticalArrangement = Arrangement.spacedBy(Spacings.small),
                    ) {
                        Button(onClick = { onPlay(false) }) {
                            AppIcon(
                                name = "ic_play",
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(Spacings.small))
                            Text(
                                if (position != null) "Resume from ${formatDuration(position)}"
                                else "Play"
                            )
                        }
                        if (position != null) {
                            OutlinedButton(onClick = { onPlay(true) }) {
                                Text("Play from the start")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                model.setWatched(player?.watched != true, navigator::message)
                            }
                        ) {
                            AppIcon(
                                name = if (player?.watched == true) "ic_eye_off" else "ic_eye",
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(Spacings.small))
                            Text(
                                if (player?.watched == true) "Mark as unwatched"
                                else "Mark as watched"
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                model.loadCustomPlaylists()
                                saveDialog = true
                            }
                        ) {
                            AppIcon(
                                name = "ic_list_plus",
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(Spacings.small))
                            Text("Save to playlist")
                        }
                        DownloadButton(
                            download = download,
                            canDownload = video.mediaUrl != null,
                            onDownload = { app.downloads.start(video) },
                            onCancel = { app.downloads.cancel(video.youtubeId) },
                        )
                    }
                    if (download != null) DownloadProgress(download)
                }
            }

            Spacer(Modifier.height(Spacings.large))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacings.large)) {
                Column(modifier = Modifier.weight(2f)) {
                    video.description
                        ?.takeIf { it.isNotBlank() }
                        ?.let { description ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = MaterialTheme.shapes.medium,
                                modifier =
                                    Modifier.fillMaxWidth().clickable {
                                        descriptionExpanded = !descriptionExpanded
                                    },
                            ) {
                                Text(
                                    text = rememberLinkedText(description),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = if (descriptionExpanded) Int.MAX_VALUE else 6,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier =
                                        Modifier.padding(Spacings.medium).animateContentSize(),
                                )
                            }
                        }
                    Comments(model.comments)
                }
                if (model.similar.isNotEmpty()) {
                    Column(modifier = Modifier.weight(1f)) {
                        SectionTitle("Similar videos")
                        model.similar.forEach { similar ->
                            VideoRow(
                                video = similar,
                                onClick = { navigator.open(Route.Video(similar.youtubeId)) },
                            )
                        }
                    }
                }
            }
        }
        ColumnScrollbar(model.scroll)
    }

    if (saveDialog) {
        SaveToPlaylistDialog(model = model, onDismiss = { saveDialog = false })
    }
}

@Composable
private fun DownloadButton(
    download: LocalDownload?,
    canDownload: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    when (download?.state) {
        LocalDownload.State.RUNNING ->
            OutlinedButton(onClick = onCancel) { Text("Cancel download") }
        LocalDownload.State.DONE ->
            OutlinedButton(
                onClick = {
                    runCatching { java.awt.Desktop.getDesktop().open(download.file.parentFile) }
                }
            ) {
                AppIcon(
                    name = "ic_check",
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacings.small))
                Text("Show in folder")
            }
        else ->
            OutlinedButton(onClick = onDownload, enabled = canDownload) {
                AppIcon(
                    name = "ic_download",
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacings.small))
                Text("Download to this PC")
            }
    }
}

@Composable
fun DownloadProgress(download: LocalDownload) {
    Column(modifier = Modifier.padding(top = Spacings.small).widthIn(max = 420.dp)) {
        when (download.state) {
            LocalDownload.State.RUNNING -> {
                val progress = download.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = "Saving to ${download.file.parent}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LocalDownload.State.DONE ->
                Text(
                    text = "Saved as ${download.file.path}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            LocalDownload.State.FAILED ->
                Text(
                    text = "The download failed: ${download.error.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            LocalDownload.State.CANCELLED -> {}
        }
    }
}

@Composable
private fun Comments(comments: List<TaComment>?) {
    val count = comments?.sumOf { 1 + it.replies.size }
    SectionTitle(
        if (count != null && count > 0) "Comments ($count)" else "Comments",
        Modifier.padding(top = Spacings.medium),
    )
    when {
        comments == null -> CircularProgressIndicator(modifier = Modifier.padding(Spacings.medium))
        comments.isEmpty() ->
            Text(
                text = "No comments saved",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else -> comments.forEach { CommentItem(it) }
    }
}

@Composable
private fun CommentItem(comment: TaComment) {
    var showReplies by rememberSaveable(comment.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        CommentBody(comment)
        if (comment.replies.isNotEmpty()) {
            TextButton(
                onClick = { showReplies = !showReplies },
                modifier = Modifier.padding(start = 40.dp),
            ) {
                Text(if (showReplies) "Hide replies" else "Show ${comment.replies.size} replies")
            }
            if (showReplies) {
                Column(modifier = Modifier.padding(start = 40.dp)) {
                    comment.replies.forEach { CommentBody(it) }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = Spacings.extraSmall))
    }
}

@Composable
private fun CommentBody(comment: TaComment) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacings.small)) {
        TaAvatar(path = comment.authorThumbnail, size = 32.dp)
        Spacer(Modifier.width(Spacings.small))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.author,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (comment.authorIsUploader) FontWeight.Bold else null,
                    color =
                        if (comment.authorIsUploader) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                comment.timeText?.let {
                    Text(
                        text = " • $it",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = rememberLinkedText(comment.text),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacings.extraSmall),
                modifier = Modifier.padding(top = Spacings.extraSmall),
            ) {
                AppIcon(
                    name = "ic_thumbs_up",
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = formatCount(comment.likeCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (comment.isFavorited) {
                    Text(
                        text = " • Hearted by the creator",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Puts the video in or takes it out of TubeArchivist's own playlists, or makes a new one. */
@Composable
private fun SaveToPlaylistDialog(model: VideoModel, onDismiss: () -> Unit) {
    val navigator = LocalNavigator.current
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextInputDialog(
            title = "New playlist",
            label = "Name",
            confirmLabel = "Make",
            onConfirm = { name ->
                if (name.isNotBlank()) model.createPlaylist(name.trim(), navigator::message)
            },
            onDismiss = { creating = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save to playlist") },
        text = {
            val playlists = model.customPlaylists
            when {
                playlists == null -> CircularProgressIndicator()
                playlists.isEmpty() -> Text("You have no playlists of your own yet.")
                else ->
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            val inPlaylist = playlist.entries.any { it.youtubeId == model.videoId }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            model.setInPlaylist(
                                                playlist,
                                                !inPlaylist,
                                                navigator::message,
                                            )
                                        }
                                        .padding(vertical = Spacings.small),
                            ) {
                                androidx.compose.material3.Checkbox(
                                    checked = inPlaylist,
                                    onCheckedChange = {
                                        model.setInPlaylist(playlist, it, navigator::message)
                                    },
                                )
                                Text(playlist.name, modifier = Modifier.weight(1f))
                            }
                        }
                    }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { creating = true }) { Text("New playlist") } },
    )
}

/** The video's side on the server: its file, streams, subtitles and what can be done to it. */
@Composable
private fun VideoServerSide(model: VideoModel, video: TaVideo) {
    val navigator = LocalNavigator.current
    var confirm by remember { mutableStateOf<VideoAction?>(null) }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacings.medium),
            modifier =
                Modifier.fillMaxSize().verticalScroll(model.serverScroll).padding(Spacings.default),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacings.medium)) {
                SectionCard("File", modifier = Modifier.weight(1f)) {
                    video.mediaUrl?.let { InfoRow("Path", it) }
                    video.mediaSize?.let { InfoRow("Size", formatSize(it, model.metricSizes)) }
                    video.player
                        ?.duration
                        ?.takeIf { it > 0 }
                        ?.let { InfoRow("Length", formatDuration(it)) }
                    video.dateDownloaded?.let {
                        InfoRow("Downloaded", anyDate(it.toString()).orEmpty())
                    }
                    video.streams.forEach { stream ->
                        val size =
                            if (stream.width != null && stream.height != null)
                                " ${stream.width}×${stream.height}"
                            else ""
                        val rate = stream.bitrate?.let { " · ${it / 1000} kbit/s" }.orEmpty()
                        InfoRow(
                            stream.type.replaceFirstChar { it.uppercase() },
                            "${stream.codec}$size$rate",
                        )
                    }
                    if (video.subtitles.isNotEmpty()) {
                        InfoRow(
                            "Subtitles",
                            video.subtitles.joinToString { sub ->
                                sub.name
                                    ?: sub.lang + if (sub.source == "auto") " (automatic)" else ""
                            },
                        )
                    }
                }
                SectionCard("Details", modifier = Modifier.weight(1f)) {
                    InfoRow("Video ID", video.youtubeId)
                    video.type?.let { InfoRow("Type", it.replaceFirstChar { c -> c.uppercase() }) }
                    fullDate(video.published)?.let { InfoRow("Published", it) }
                    anyDate(video.lastRefresh)?.let { InfoRow("Last refreshed", it) }
                    video.active?.let { InfoRow("On YouTube", if (it) "Yes" else "No, removed") }
                    if (video.category.isNotEmpty())
                        InfoRow("Category", video.category.joinToString())
                    if (video.tags.isNotEmpty()) InfoRow("Tags", video.tags.take(12).joinToString())
                    video.stats?.dislikeCount?.let { InfoRow("Dislikes", formatCount(it)) }
                }
            }
            SectionCard("Actions") {
                Text(
                    text =
                        "Reindex looks the video up on YouTube again. Redownload replaces its file. Delete removes it and its file from the server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacings.medium))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacings.small)) {
                    VideoAction.entries.forEach { action ->
                        OutlinedButton(onClick = { confirm = action }) { Text(action.label) }
                    }
                }
            }
        }
        ColumnScrollbar(model.serverScroll)
    }
    confirm?.let { action ->
        ConfirmDialog(
            title = "${action.label} this video?",
            text =
                action.confirm
                    .replace("the videos", "the video")
                    .replace("their files", "its file"),
            confirmLabel = action.label,
            onConfirm = { model.runAction(action, navigator::message) },
            onDismiss = { confirm = null },
        )
    }
}

private fun videoDetails(video: TaVideo): String {
    val parts = mutableListOf<String>()
    video.stats?.viewCount?.let { parts.add("${formatCount(it)} views") }
    video.stats?.likeCount?.let { parts.add("${formatCount(it)} likes") }
    fullDate(video.published)?.let { parts.add(it) }
    return parts.joinToString(" • ")
}
