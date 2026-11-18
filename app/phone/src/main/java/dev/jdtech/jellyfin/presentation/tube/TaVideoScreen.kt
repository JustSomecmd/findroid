package dev.jdtech.jellyfin.presentation.tube

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderAction
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderEvent
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderViewModel
import dev.jdtech.jellyfin.film.presentation.tube.TaVideoViewModel
import dev.jdtech.jellyfin.presentation.film.components.ItemButtonsBar
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.tubearchivist.TaComment
import dev.jdtech.jellyfin.tubearchivist.TaVideo
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import org.jellyfin.sdk.model.api.BaseItemKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaVideoScreen(
    videoId: String,
    onVideoClick: (videoId: String) -> Unit,
    onChannelClick: (channelId: String) -> Unit,
    navigateBack: () -> Unit,
    viewModel: TaVideoViewModel = hiltViewModel(),
    downloaderViewModel: DownloaderViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloaderState by downloaderViewModel.state.collectAsStateWithLifecycle()

    // Loaded again when coming back from the player, for the new progress
    LifecycleResumeEffect(videoId) {
        viewModel.load(videoId)
        onPauseOrDispose {}
    }

    LaunchedEffect(state.episode) { state.episode?.let { downloaderViewModel.update(it) } }

    ObserveAsEvents(downloaderViewModel.events) { event ->
        when (event) {
            is DownloaderEvent.Successful,
            is DownloaderEvent.Deleted -> viewModel.reloadEpisode()
        }
    }

    ObserveAsEvents(viewModel.messages) { message ->
        Toast.makeText(context, taMessageText(context, message), Toast.LENGTH_LONG).show()
    }

    val play: (Boolean) -> Unit = { startFromBeginning ->
        state.episode?.let { episode ->
            val intent = Intent(context, PlayerActivity::class.java)
            intent.putExtra("itemId", episode.id.toString())
            intent.putExtra("itemKind", BaseItemKind.EPISODE.serialName)
            intent.putExtra("startFromBeginning", startFromBeginning)
            // Resume where TubeArchivist says, which may be newer than Jellyfin
            val position = state.video?.player?.position
            if (!startFromBeginning && position != null && state.video?.player?.watched != true) {
                intent.putExtra("position", (position * 1000).toLong())
            }
            context.startActivity(intent)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
            )
        },
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
    ) { innerPadding ->
        val video = state.video
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when {
                video == null && state.error != null ->
                    Text(
                        text = state.error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier.align(Alignment.Center)
                                .padding(MaterialTheme.spacings.default),
                    )
                video == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else ->
                    TaVideoContent(
                        video = video,
                        state = state,
                        downloaderState = downloaderState,
                        onPlay = play,
                        onToggleWatched = { viewModel.toggleWatched() },
                        onToggleFavorite = { viewModel.toggleFavorite() },
                        onDownloaderAction = { downloaderViewModel.onAction(it) },
                        onVideoClick = onVideoClick,
                        onChannelClick = onChannelClick,
                    )
            }
        }
    }
}

@Composable
private fun TaVideoContent(
    video: TaVideo,
    state: dev.jdtech.jellyfin.film.presentation.tube.TaVideoState,
    downloaderState: dev.jdtech.jellyfin.core.presentation.downloader.DownloaderState,
    onPlay: (startFromBeginning: Boolean) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownloaderAction: (DownloaderAction) -> Unit,
    onVideoClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
) {
    var descriptionExpanded by rememberSaveable { mutableStateOf(false) }
    val padding = MaterialTheme.spacings.medium
    val episode = state.episode

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MaterialTheme.spacings.large),
    ) {
        item(key = "thumbnail") {
            Box(
                modifier =
                    Modifier.padding(horizontal = padding).clickable(enabled = episode != null) {
                        onPlay(false)
                    }
            ) {
                TaVideoThumbnail(video = video, modifier = Modifier.fillMaxWidth())
                if (episode != null) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                        modifier = Modifier.align(Alignment.Center).size(56.dp),
                    ) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_play),
                            contentDescription = stringResource(CoreR.string.ta_play),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
        }
        item(key = "info") {
            Column(modifier = Modifier.padding(horizontal = padding, vertical = padding / 2)) {
                Text(text = video.title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
                Text(
                    text = videoDetails(video),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        video.channel?.let { channel ->
            item(key = "channel") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable { onChannelClick(channel.id) }
                            .padding(horizontal = padding, vertical = padding / 2),
                ) {
                    TaAvatar(path = channel.thumbUrl, size = 40.dp)
                    Spacer(Modifier.width(MaterialTheme.spacings.medium))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        channel.subs?.let { subs ->
                            Text(
                                text =
                                    stringResource(CoreR.string.ta_subscribers, formatCount(subs)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item(key = "buttons") {
            Column(modifier = Modifier.padding(horizontal = padding, vertical = padding / 2)) {
                when {
                    episode != null ->
                        ItemButtonsBar(
                            item = episode,
                            downloaderState = downloaderState,
                            onPlayClick = onPlay,
                            onMarkAsPlayedClick = onToggleWatched,
                            onMarkAsFavoriteClick = onToggleFavorite,
                            onTrailerClick = {},
                            onDownloadClick = { storageIndex ->
                                onDownloaderAction(DownloaderAction.Download(episode, storageIndex))
                            },
                            onDownloadCancelClick = {
                                onDownloaderAction(DownloaderAction.CancelDownload(episode))
                            },
                            onDownloadDeleteClick = {
                                onDownloaderAction(DownloaderAction.DeleteDownload(episode))
                            },
                            onDownloadPauseClick = {
                                onDownloaderAction(DownloaderAction.PauseDownload(episode))
                            },
                            onDownloadResumeClick = {
                                onDownloaderAction(DownloaderAction.ResumeDownload(episode))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    state.lookingUp ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(MaterialTheme.spacings.small))
                            Text(
                                text = stringResource(CoreR.string.ta_finding_in_jellyfin),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    else ->
                        Text(
                            text = stringResource(CoreR.string.ta_not_in_jellyfin),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                if (episode == null && !state.lookingUp) {
                    TextButton(onClick = onToggleWatched) {
                        Text(
                            stringResource(
                                if (video.player?.watched == true) CoreR.string.ta_mark_unwatched
                                else CoreR.string.ta_mark_watched
                            )
                        )
                    }
                }
            }
        }
        video.description
            ?.takeIf { it.isNotBlank() }
            ?.let { description ->
                item(key = "description") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = padding, vertical = padding / 2)
                                .clickable { descriptionExpanded = !descriptionExpanded },
                    ) {
                        Text(
                            text = rememberLinkedText(description),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (descriptionExpanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(padding).animateContentSize(),
                        )
                    }
                }
            }
        if (state.similar.isNotEmpty()) {
            item(key = "similar") {
                Column(modifier = Modifier.padding(vertical = padding / 2)) {
                    TaSectionTitle(
                        text = stringResource(CoreR.string.ta_similar_videos),
                        modifier = Modifier.padding(horizontal = padding),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = padding),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
                    ) {
                        items(state.similar, key = { it.youtubeId }) { similar ->
                            TaVideoSmallCard(
                                video = similar,
                                onClick = { onVideoClick(similar.youtubeId) },
                            )
                        }
                    }
                }
            }
        }
        item(key = "comments_title") {
            val count = state.comments?.let { comments -> comments.sumOf { 1 + it.replies.size } }
            TaSectionTitle(
                text =
                    if (count != null && count > 0) {
                        stringResource(CoreR.string.ta_comments_count, count)
                    } else {
                        stringResource(CoreR.string.ta_comments)
                    },
                modifier = Modifier.padding(horizontal = padding),
            )
        }
        val comments = state.comments
        when {
            comments == null ->
                item(key = "comments_loading") {
                    Box(Modifier.fillMaxWidth().padding(padding)) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }
            comments.isEmpty() ->
                item(key = "comments_empty") {
                    Text(
                        text = stringResource(CoreR.string.ta_no_comments),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = padding),
                    )
                }
            else ->
                items(comments, key = { "c" + it.id }) { comment ->
                    TaCommentItem(
                        comment = comment,
                        modifier = Modifier.padding(horizontal = padding),
                    )
                }
        }
    }
}

@Composable
private fun TaCommentItem(comment: TaComment, modifier: Modifier = Modifier) {
    var showReplies by rememberSaveable(comment.id) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        TaCommentBody(comment = comment)
        if (comment.replies.isNotEmpty()) {
            TextButton(
                onClick = { showReplies = !showReplies },
                modifier = Modifier.padding(start = 40.dp),
            ) {
                Text(
                    stringResource(
                        if (showReplies) CoreR.string.ta_hide_replies
                        else CoreR.string.ta_show_replies,
                        comment.replies.size,
                    )
                )
            }
            if (showReplies) {
                Column(modifier = Modifier.padding(start = 40.dp).animateContentSize()) {
                    comment.replies.forEach { reply -> TaCommentBody(comment = reply) }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacings.extraSmall))
    }
}

@Composable
private fun TaCommentBody(comment: TaComment) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacings.small)) {
        TaAvatar(path = comment.authorThumbnail, size = 32.dp)
        Spacer(Modifier.width(MaterialTheme.spacings.small))
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
                    modifier = Modifier.weight(1f, fill = false),
                )
                comment.timeText?.let {
                    Text(
                        text = " • $it",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = rememberLinkedText(comment.text),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
                modifier = Modifier.padding(top = MaterialTheme.spacings.extraSmall),
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_thumbs_up),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatCount(comment.likeCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (comment.isFavorited) {
                    Text(
                        text = " • " + stringResource(CoreR.string.ta_hearted),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun videoDetails(video: TaVideo): String {
    val parts = mutableListOf<String>()
    video.stats?.viewCount?.let {
        parts.add(stringResource(CoreR.string.ta_views, formatCount(it)))
    }
    video.stats?.likeCount?.let {
        parts.add(stringResource(CoreR.string.ta_likes, formatCount(it)))
    }
    fullDate(video.published)?.let { parts.add(it) }
    return parts.joinToString(" • ")
}
