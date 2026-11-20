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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.blur
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
import dev.jdtech.tubedroid.desktop.ui.DetailButton
import dev.jdtech.tubedroid.desktop.ui.DetailHeader
import dev.jdtech.tubedroid.desktop.ui.DetailLine
import dev.jdtech.tubedroid.desktop.ui.DetailMoreButton
import dev.jdtech.tubedroid.desktop.ui.InfoRow
import dev.jdtech.tubedroid.desktop.ui.ItemAction
import dev.jdtech.tubedroid.desktop.ui.ManageButton
import dev.jdtech.tubedroid.desktop.ui.PageBar
import dev.jdtech.tubedroid.desktop.ui.PagePadding
import dev.jdtech.tubedroid.desktop.ui.PageState
import dev.jdtech.tubedroid.desktop.ui.SectionCard
import dev.jdtech.tubedroid.desktop.ui.SectionRow
import dev.jdtech.tubedroid.desktop.ui.SectionTitle
import dev.jdtech.tubedroid.desktop.ui.ServerSideTheme
import dev.jdtech.tubedroid.desktop.ui.Spacings
import dev.jdtech.tubedroid.desktop.ui.TaAvatar
import dev.jdtech.tubedroid.desktop.ui.VideoCard
import dev.jdtech.tubedroid.desktop.ui.VideoThumbnail
import dev.jdtech.tubedroid.desktop.ui.anyDate
import dev.jdtech.tubedroid.desktop.ui.errorText
import dev.jdtech.tubedroid.desktop.ui.formatCount
import dev.jdtech.tubedroid.desktop.ui.formatDuration
import dev.jdtech.tubedroid.desktop.ui.formatSize
import dev.jdtech.tubedroid.desktop.ui.fullDate
import dev.jdtech.tubedroid.desktop.ui.openInBrowser
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
                    ManageButton(
                        on = model.onServer,
                        onClick = { model.onServer = !model.onServer },
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
 * The video as Jellyfin shows an item: the thumbnail as the backdrop and the poster, the title with
 * its facts, round buttons, the channel and description, then similar videos and comments.
 */
@Composable
fun VideoContent(model: VideoModel, video: TaVideo, onPlay: (fromStart: Boolean) -> Unit) {
    val app = LocalApp.current
    val navigator = LocalNavigator.current
    var saveDialog by remember { mutableStateOf(false) }
    var descriptionExpanded by rememberSaveable { mutableStateOf(false) }
    val download = app.downloads.stateOf(video.youtubeId)
    val player = video.player
    val watched = player?.watched == true
    val position = player?.position?.takeIf { !watched && it > 5 }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(model.scroll)) {
            DetailHeader(
                title = video.title,
                info = videoInfo(video),
                backdrop = {
                    TaImage(path = video.thumbUrl, modifier = Modifier.fillMaxSize().blur(16.dp))
                },
                poster = {
                    Box(modifier = Modifier.clickable { onPlay(false) }) {
                        VideoThumbnail(video = video, modifier = Modifier.fillMaxWidth())
                    }
                },
                posterWidth = 440.dp,
                buttons = {
                    DetailButton(
                        icon = "ic_play",
                        label = position?.let { "Resume from ${formatDuration(it)}" } ?: "Play",
                        onClick = { onPlay(false) },
                        main = true,
                    )
                    if (position != null) {
                        DetailButton(
                            icon = "ic_rotate_ccw",
                            label = "Play from the start",
                            onClick = { onPlay(true) },
                        )
                    }
                    DetailButton(
                        icon = "ic_check",
                        label = if (watched) "Mark as unwatched" else "Mark as watched",
                        onClick = { model.setWatched(!watched, navigator::message) },
                        on = watched,
                    )
                    DetailButton(
                        icon = "ic_list_plus",
                        label = "Save to playlist",
                        onClick = {
                            model.loadCustomPlaylists()
                            saveDialog = true
                        },
                    )
                    DownloadButton(
                        download = download,
                        canDownload = video.mediaUrl != null,
                        onDownload = { app.downloads.start(video) },
                        onCancel = { app.downloads.cancel(video.youtubeId) },
                    )
                    DetailMoreButton(
                        actions =
                            listOfNotNull(
                                video.channel?.let { channel ->
                                    ItemAction("Go to ${channel.name}") {
                                        navigator.open(Route.Channel(channel.id))
                                    }
                                },
                                ItemAction("Manage on the server") { model.onServer = true },
                                ItemAction("Open in TubeArchivist") {
                                    app.api
                                        .mediaUrl("/video/${video.youtubeId}/")
                                        ?.let(::openInBrowser)
                                },
                            )
                    )
                },
                details = {
                    video.channel?.let { channel ->
                        DetailLine(label = "Channel", value = channel.name) {
                            navigator.open(Route.Channel(channel.id))
                        }
                    }
                    if (download != null) DownloadProgress(download)
                    video.description
                        ?.takeIf { it.isNotBlank() }
                        ?.let { description ->
                            Text(
                                text = rememberLinkedText(description),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = if (descriptionExpanded) Int.MAX_VALUE else 5,
                                overflow = TextOverflow.Ellipsis,
                                modifier =
                                    Modifier.padding(top = Spacings.small)
                                        .widthIn(max = 900.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable { descriptionExpanded = !descriptionExpanded }
                                        .animateContentSize(),
                            )
                        }
                },
            )
            if (model.similar.isNotEmpty()) {
                SectionRow(
                    title = "More like this",
                    modifier = Modifier.padding(top = Spacings.large),
                ) {
                    items(model.similar, key = { it.youtubeId }) { similar ->
                        VideoCard(
                            video = similar,
                            onClick = { navigator.open(Route.Video(similar.youtubeId)) },
                            modifier = Modifier.width(ROW_CARD_WIDTH),
                        )
                    }
                }
            }
            Column(
                modifier =
                    Modifier.padding(horizontal = PagePadding, vertical = Spacings.large)
                        .widthIn(max = 1000.dp)
            ) {
                Comments(model.comments)
            }
        }
        ColumnScrollbar(model.scroll)
    }

    if (saveDialog) {
        SaveToPlaylistDialog(model = model, onDismiss = { saveDialog = false })
    }
}

/** Downloading the video to this PC, as a round button: start, cancel, or show the file. */
@Composable
private fun DownloadButton(
    download: LocalDownload?,
    canDownload: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    when (download?.state) {
        LocalDownload.State.RUNNING ->
            DetailButton(
                icon = "ic_x",
                label = "Cancel the download",
                onClick = onCancel,
                on = true,
            )
        LocalDownload.State.DONE ->
            DetailButton(
                icon = "ic_download",
                label = "On this PC: show in folder",
                onClick = {
                    runCatching { java.awt.Desktop.getDesktop().open(download.file.parentFile) }
                },
                on = true,
            )
        else ->
            DetailButton(
                icon = "ic_download",
                label = "Download to this PC",
                onClick = onDownload,
                enabled = canDownload,
            )
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

/** The facts under the title, as on Jellyfin: the date, the length, views and likes. */
private fun videoInfo(video: TaVideo): List<String> =
    listOfNotNull(
        fullDate(video.published),
        video.player?.duration?.takeIf { it > 0 }?.let { formatDuration(it) },
        video.stats?.viewCount?.let { "${formatCount(it)} views" },
        video.stats?.likeCount?.let { "${formatCount(it)} likes" },
    )
